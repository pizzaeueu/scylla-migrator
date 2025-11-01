package com.scylladb.migrator.readers

import com.scylladb.migrator.config.{ MigratorConfig, SourceSettings, TargetSettings }
import com.scylladb.migrator.scylla.{ ScyllaMigrator, SourceDataFrame }
import org.apache.log4j.LogManager
import org.apache.spark.sql.SparkSession

/**
  * Parallel Parquet processing strategy.
  *
  * Reads all Parquet files at once using Spark's union operation, maximizing parallelism.
  * This is the traditional approach that provides the best performance but does NOT support
  * savepoints - if the migration is interrupted, it must restart from the beginning.
  *
  * Use this strategy when:
  * - Maximum performance is required
  * - The migration is expected to complete without interruption
  * - Savepoints are not needed
  */
class ParallelParquetStrategy extends ParquetProcessingStrategy {
  private val log = LogManager.getLogger("com.scylladb.migrator.readers.ParallelParquetStrategy")

  override def migrate(config: MigratorConfig,
                       source: SourceSettings.Parquet,
                       target: TargetSettings.Scylla)(implicit spark: SparkSession): Unit = {
    log.info("Using PARALLEL processing mode (no savepoints, maximum performance)")

    val preparedReader = Parquet.prepareParquetReader(
      spark,
      source,
      config.getSkipParquetFilesOrEmptySet
    )

    preparedReader.configureHadoop(spark)

    val filesToRead = preparedReader.filesToProcess

    if (filesToRead.isEmpty) {
      log.warn("No files to process after filtering. Migration may be complete.")
      return
    }

    log.info(s"Reading ${filesToRead.size} Parquet files in parallel")

    val df = if (filesToRead.size == 1) {
      spark.read.parquet(filesToRead.head)
    } else {
      val dataFrames = filesToRead.map(spark.read.parquet)
      dataFrames.reduce(_.union(_))
    }

    val sourceDF = SourceDataFrame(df, None, savepointsSupported = false)

    log.info("Created unified DataFrame from all Parquet files")

    ScyllaMigrator.migrate(config, target, sourceDF)

    log.info("Parallel Parquet migration completed successfully")
  }
}
