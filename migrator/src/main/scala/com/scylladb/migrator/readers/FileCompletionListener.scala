package com.scylladb.migrator.readers

import org.apache.log4j.LogManager
import org.apache.spark.scheduler.{ SparkListener, SparkListenerTaskEnd }
import org.apache.spark.Success

import scala.collection.concurrent.TrieMap

/**
  * SparkListener that tracks partition completion and aggregates it to file-level completion.
  *
  * This listener monitors Spark task completion events and maintains mappings between
  * partitions and files. When all partitions belonging to a file have been successfully
  * completed, it marks the file as processed via the ParquetSavepointsManager.
  *
  * Thread-safety: This class uses TrieMap (Scala's concurrent map) to ensure thread-safe updates
  * from multiple Spark executor threads calling onTaskEnd concurrently.
  *
  * @param partitionToFile Mapping from Spark partition ID to source file path
  * @param fileToPartitions Mapping from file path to the set of partition IDs reading from it
  * @param savepointsManager Manager to notify when files are completed
  */
class FileCompletionListener(
  partitionToFile: Map[Int, String],
  fileToPartitions: Map[String, Set[Int]],
  savepointsManager: ParquetSavepointsManager
) extends SparkListener {

  private val log = LogManager.getLogger("com.scylladb.migrator.readers.FileCompletionListener")

  // Thread-safe tracking of completed partitions
  private val completedPartitions = TrieMap.empty[Int, Boolean]

  // Thread-safe tracking of completed files (to avoid duplicate marking)
  private val completedFiles = TrieMap.empty[String, Boolean]

  log.info(
    s"FileCompletionListener initialized: tracking ${fileToPartitions.size} files " +
      s"across ${partitionToFile.size} partitions")

  /**
    * Called when a Spark task completes (successfully or not).
    * We track successful task completions and check if files are complete.
    */
  override def onTaskEnd(taskEnd: SparkListenerTaskEnd): Unit =
    // Only track successfully completed tasks
    if (taskEnd.reason == Success) {
      val partitionId = taskEnd.taskInfo.partitionId

      // Check if this partition is one we're tracking
      partitionToFile.get(partitionId) match {
        case Some(filename) =>
          // Mark partition as complete (idempotent - only process if new)
          // putIfAbsent returns None if key was absent (successfully inserted)
          if (completedPartitions.putIfAbsent(partitionId, true).isEmpty) {
            log.debug(s"Partition $partitionId completed (file: $filename)")
            checkFileCompletion(filename)
          }

        case None =>
          // This partition is not in our tracking map
          // This can happen for partitions created by Spark operations after the initial read
          log.trace(s"Task completed for untracked partition $partitionId")
      }
    } else {
      // Task failed or was killed
      log.debug(
        s"Task for partition ${taskEnd.taskInfo.partitionId} did not complete successfully: ${taskEnd.reason}")
    }

  /**
    * Check if all partitions for a given file have been completed.
    * If so, mark the file as processed in the savepoints manager.
    *
    * This method is thread-safe and idempotent.
    */
  private def checkFileCompletion(filename: String): Unit = {
    // Skip if we've already marked this file as complete
    if (completedFiles.contains(filename)) {
      return
    }

    // Get all partitions that belong to this file
    fileToPartitions.get(filename) match {
      case Some(allPartitions) =>
        // Check if ALL partitions for this file are complete
        val allComplete = allPartitions.forall(completedPartitions.contains)

        if (allComplete) {
          // Atomically mark file as complete (only first thread succeeds)
          // putIfAbsent returns None if key was absent (successfully inserted)
          if (completedFiles.putIfAbsent(filename, true).isEmpty) {
            // Notify savepoints manager
            savepointsManager.markFileAsProcessed(filename)

            val progress = s"${completedFiles.size}/${fileToPartitions.size}"
            log.info(s"File completed: $filename (progress: $progress)")

            // Log a milestone for every 10% completion
            val completionPercentage =
              (completedFiles.size.toDouble / fileToPartitions.size * 100).toInt
            if (completionPercentage % 10 == 0 && completionPercentage > 0) {
              log.info(s"Migration progress: $completionPercentage% ($progress files)")
            }
          }
        } else {
          val completedCount = allPartitions.count(completedPartitions.contains)
          log.trace(s"File $filename: $completedCount/${allPartitions.size} partitions complete")
        }

      case None =>
        log.warn(s"File $filename not found in fileToPartitions map (this shouldn't happen)")
    }
  }

  def getCompletedFilesCount: Int = completedFiles.size

  def getTotalFilesCount: Int = fileToPartitions.size

  def getCompletionPercentage: Double =
    if (fileToPartitions.isEmpty) 100.0
    else (completedFiles.size.toDouble / fileToPartitions.size) * 100.0

  def getProgressReport: String = {
    val filesCompleted = completedFiles.size
    val totalFiles = fileToPartitions.size
    val partitionsCompleted = completedPartitions.size
    val totalPartitions = partitionToFile.size
    val percentage = f"${getCompletionPercentage}%.1f"

    s"Progress: $filesCompleted/$totalFiles files ($percentage%%), " +
      s"$partitionsCompleted/$totalPartitions partitions"
  }

  def getCompletedFiles: Set[String] =
    completedFiles.keySet.toSet
}
