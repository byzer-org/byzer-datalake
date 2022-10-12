package org.apache.spark.sql.delta.commands

import org.apache.hadoop.fs.{FileStatus, Path}
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.delta.actions.{Action, AddFile, RemoveFile}
import org.apache.spark.sql.delta.schema.ImplicitMetadataOperation
import org.apache.spark.sql.delta._
import org.apache.spark.sql.delta.constraints.{DeltaInvariantCheckerExec, Invariants}
import org.apache.spark.sql.execution.SQLExecution
import org.apache.spark.sql.execution.command.RunnableCommand
import org.apache.spark.sql.execution.datasources.FileFormatWriter
import org.apache.spark.sql.{Dataset, Row, SparkSession, functions => F}
import tech.mlsql.common.PathFun

import scala.collection.mutable.ArrayBuffer

/**
 *
 * CompactTableInDelta is used to compact small files into big files.
 * This class requires the delta table should satisfied the following requirements:
 *
 * 1. There is at least one checkpoint have been generated.
 * 2. The target delta table should be written
 * by SaveMode.Append(Batch) or OutputMode.Append(Stream)
 * 3. The target delta table should not operated by upsert/delete action.
 *
 * @param deltaLog
 * @param options
 * @param partitionColumns
 * @param configuration
 */
case class CompactTableInDelta(
                                deltaLog: DeltaLog,
                                options: DeltaOptions,
                                partitionColumns: Seq[String],
                                configuration: Map[String, String],
                                child:Seq[LogicalPlan] = Seq()
                              )
  extends RunnableCommand
    with ImplicitMetadataOperation
    with DeltaCommand with DeltaCommandsFun {

  import CompactTableInDelta._

  override def run(sparkSession: SparkSession): Seq[Row] = {

    val (items, targetVersion, commitSuccess) = _run(sparkSession)
    if (commitSuccess) {
      // cleanup deltaLog, so once optimized, we can not traval to the version
      // before targetVersion because the data have been deleted
      recordDeltaOperation(deltaLog, "delta.log.compact.cleanup") {
        doLogCleanup(targetVersion)
      }

      // now we can really delete all files.
      // Notice this is not a recovery operation.
      recordDeltaOperation(deltaLog, "delta.data.compact.cleanup") {
        doRemoveFileCleanup(items)
      }
    } else {
      rollback(items)
    }
    if (!commitSuccess) Seq[Row]() else {
      items.map(f => Row.fromSeq(Seq(f.json)))
    }
  }

  protected def _run(sparkSession: SparkSession): (Seq[Action], Long, Boolean) = {

    var compactRetryTimesForLock = configuration.get(COMPACT_RETRY_TIMES_FOR_LOCK)
      .map(_.toInt).getOrElse(0)


    var success = false

    // The transaction should not take too long, so we should generated
    // the new files firstly, and then try to start a transaction and commit,
    // once fails, try again until compactRetryTimesForLock times.
    // In the transaction, we only commit some information(AddFiles/RemoveFiles)
    // So it will not affect the other program to write data.
    val (actions, version) = optimize(sparkSession, false)

    while (!success && compactRetryTimesForLock > 0) {
      try {
        deltaLog.withNewTransaction { txn =>
          txn.readWholeTable()
          val operation = DeltaOperations.Optimize(Seq(), Seq())
          txn.commit(actions, operation)
          success = true
        }
      } catch {
        case e@(_: java.util.ConcurrentModificationException |
                _: DeltaConcurrentModificationException) =>
          logInfo(s"DeltaConcurrentModificationException throwed. " +
            s"tried ${compactRetryTimesForLock}")
          // clean data aready been written
          Thread.sleep(1000)
          compactRetryTimesForLock -= 1
        case e: Exception =>
          throw e

      }
    }

    (actions, version, success)

  }

  protected def doLogCleanup(targetVersion: Long) = {
    val fs = deltaLog.logPath.getFileSystem(deltaLog.newDeltaHadoopConf())
    var numDeleted = 0
    listExpiredDeltaLogs(targetVersion).map(_.getPath).foreach { path =>
      // recursive = false
      if (fs.delete(path, false)) {
        numDeleted += 1
      }
    }
    logInfo(s"Deleted $numDeleted log files earlier than $targetVersion")
  }

  /**
   * Returns an iterator of expired delta logs that can be cleaned up. For a delta log to be
   * considered as expired, it must:
   *  - have a checkpoint file after it
   *  - be earlier than `targetVersion`
   */
  private def listExpiredDeltaLogs(targetVersion: Long): Iterator[FileStatus] = {
    import org.apache.spark.sql.delta.util.FileNames._

    val latestCheckpoint = deltaLog.lastCheckpoint
    if (latestCheckpoint.isEmpty) return Iterator.empty

    def getVersion(filePath: Path): Long = {
      if (isCheckpointFile(filePath)) {
        checkpointVersion(filePath)
      } else {
        deltaVersion(filePath)
      }
    }

    val files = deltaLog.store.listFrom(deltaFile(deltaLog.logPath, 0))
      .filter(f => isCheckpointFile(f.getPath) || isDeltaFile(f.getPath))
      .filter { f =>
        getVersion(f.getPath) < targetVersion
      }
    files
  }

  protected def doRemoveFileCleanup(items: Seq[Action]) = {
    var numDeleted = 0
    items.filter(item => item.isInstanceOf[RemoveFile])
      .map(item => item.asInstanceOf[RemoveFile])
      .foreach { item =>
        val path = new Path(deltaLog.dataPath, item.path)
        val pathCrc = new Path(deltaLog.dataPath, "." + item.path + ".crc")
        val fs = deltaLog.logPath.getFileSystem(deltaLog.newDeltaHadoopConf())
        try {
          fs.delete(path, false)
          fs.delete(pathCrc, false)
          numDeleted += 1
        } catch {
          case e: Exception =>
        }
      }
    logInfo(s"Deleted $numDeleted  files in optimization progress")
  }

  protected def rollback(items: Seq[Action]) = {
    var numDeleted = 0
    items.filter(item => item.isInstanceOf[AddFile])
      .map(item => item.asInstanceOf[AddFile])
      .foreach { item =>
        val path = new Path(deltaLog.dataPath, item.path)
        val pathCrc = new Path(deltaLog.dataPath, "." + item.path + ".crc")
        val fs = deltaLog.logPath.getFileSystem(deltaLog.newDeltaHadoopConf())
        try {
          fs.delete(path, false)
          fs.delete(pathCrc, false)
          numDeleted += 1
        } catch {
          case e: Exception =>
        }
      }
    logInfo(s"Deleted $numDeleted  files in optimization progress")
  }

  protected def optimize(sparkSession: SparkSession,
                         isTry: Boolean): (Seq[Action], Long) = {
    import sparkSession.implicits._

    val metadata = deltaLog.snapshot.metadata
    val readVersion = deltaLog.snapshot.version
    if (readVersion > -1) {
      // For now, we only support the append mode(SaveMode/OutputMode).
      // So check if it satisfied this requirement.
      logInfo(
        s"""
           |${deltaLog.dataPath} is appendOnly?
           |${DeltaConfigs.IS_APPEND_ONLY.fromMetaData(metadata)}
         """.stripMargin)
    }

    // Validate partition predicates
    val replaceWhere = options.replaceWhere
    val partitionFilters = if (replaceWhere.isDefined) {
      val predicates = parsePredicates(sparkSession, replaceWhere.get)
      Some(predicates)
    } else {
      None
    }

    if (readVersion < 0) {
      // Initialize the log path
      DeltaErrors.notADeltaTableException("compact", new DeltaTableIdentifier(Option(deltaLog.dataPath.toString), None))
    }

    val latestCheckpoint = deltaLog.lastCheckpoint
    if (latestCheckpoint.isEmpty) throw new RuntimeException(
      s"""
         |Compact delta log in ${deltaLog.dataPath.toString} should at least:
         |- have a checkpoint file after it
         |- be earlier than `targetVersion`
       """.stripMargin)

    /**
     * No matter the table is a partition table or not,
     * we can pick one version and compact all files
     * before it and then remove all the files compacted and
     * add the new compaction files.
     */
    var version = configuration.get(COMPACT_VERSION_OPTION).map(_.toLong).getOrElse(-1L)
    if (version == -1) version = readVersion

    // check version is valid
    deltaLog.history.checkVersionExists(version)

    val newFiles = ArrayBuffer[AddFile]()
    val deletedFiles = ArrayBuffer[RemoveFile]()

    // find all files before this version
    val snapshot = deltaLog.getSnapshotAt(version, None)

    // here may cost huge memory in driver if people do not optimize their tables frequently,
    // we should optimize it in future
    val filterFiles = partitionFilters match {
      case None =>
        snapshot.allFiles
      case Some(predicates) =>
        DeltaLog.filterFileList(
          metadata.partitionSchema, snapshot.allFiles.toDF(), predicates).as[AddFile]
    }


    val filesShouldBeOptimized = filterFiles
      .map(addFile => PrefixAddFile(extractPathPrefix(addFile.path), addFile))
      .groupBy("prefix").agg(F.collect_list("addFile").as("addFiles")).as[PrefixAddFileList]
      .collect().toSeq

    val compactNumFilePerDir = configuration.get(COMPACT_NUM_FILE_PER_DIR)
      .map(f => f.toInt).getOrElse(1)

    def writeFiles(outputPath: Path,
                   data: Dataset[_],
                   writeOptions: Option[DeltaOptions],
                   isOptimize: Boolean): Seq[AddFile] = {
      val spark = data.sparkSession

      val (queryExecution, output) = normalizeData(metadata, data, metadata.partitionColumns)


      val committer = getCommitter(outputPath)

      val invariants = Invariants.getFromSchema(metadata.schema, spark)

      SQLExecution.withNewExecutionId(queryExecution) {
        val outputSpec = FileFormatWriter.OutputSpec(
          outputPath.toString,
          Map.empty,
          output)

        val physicalPlan = DeltaInvariantCheckerExec(queryExecution.executedPlan, invariants)

        FileFormatWriter.write(
          sparkSession = spark,
          plan = physicalPlan,
          fileFormat = deltaLog.fileFormat(metadata), // TODO doesn't support changing formats.
          committer = committer,
          outputSpec = outputSpec,
          hadoopConf = spark.sessionState.newHadoopConfWithOptions(metadata.configuration),
          partitionColumns = Seq(), // Here, we directly write into this outputPath without partition
          bucketSpec = None,
          statsTrackers = Nil,
          options = Map.empty)
      }

      committer.addedStatuses
    }

    filesShouldBeOptimized.foreach { fileList =>
      val tempFiles = fileList.addFiles.map { addFile =>
        new Path(deltaLog.dataPath, addFile.path).toString
      }
      // if the file num is smaller then we need, skip and do nothing
      if (tempFiles.length >= compactNumFilePerDir) {

        // If the code goes here, means the size of `fileList.addFiles`
        // is larger then 0. We can get the first element and get the file prefix
        // and partitionValues. In the next step when we reconstruct AddFiles, we need them.
        val prefix = extractPathPrefix(fileList.addFiles.head.path)
        val partitionValues = fileList.addFiles.head.partitionValues

        // Using spark datasource API directly,
        // is there any other ways?
        val df = sparkSession.read.parquet(tempFiles: _*)
          .repartition(compactNumFilePerDir)

        val filePath = if (prefix.isEmpty) deltaLog.dataPath
        else new Path(deltaLog.dataPath, prefix)

        // Path in AddFile/RemoveFile should be relative path, and if the prefix
        // is empty, the path will starts with "/" and we should use `stripPrefix` to remove it.
        newFiles ++= writeFiles(filePath, df, Some(options), false).map { addFile =>
          addFile.copy(path = PathFun(prefix).add(addFile.path).toPath.stripPrefix("/"), partitionValues = partitionValues)
        }
        deletedFiles ++= fileList.addFiles.map(_.remove)
      }
    }

    logInfo(s"Add ${newFiles.size} files in optimization progress")
    logInfo(s"Mark remove ${deletedFiles} files in optimization progress")
    return (newFiles ++ deletedFiles, version)
  }

  override protected val canMergeSchema: Boolean = false
  override protected val canOverwriteSchema: Boolean = false

  override protected def withNewChildrenInternal(newChildren: IndexedSeq[LogicalPlan]): LogicalPlan = copy(child = newChildren)
}

object CompactTableInDelta {
  val COMPACT_VERSION_OPTION = "compactVersion"
  val COMPACT_NUM_FILE_PER_DIR = "compactNumFilePerDir"
  val COMPACT_RETRY_TIMES_FOR_LOCK = "compactRetryTimesForLock"

  def extractPathPrefix(path: String): String = {
    if (!path.contains("/")) {
      ""
    } else {
      path.split("/").dropRight(1).mkString("/")
    }
  }
}

case class PrefixAddFile(prefix: String, addFile: AddFile)

case class PrefixAddFileList(prefix: String, addFiles: List[AddFile])
