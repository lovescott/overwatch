package com.databricks.labs.overwatch.env

import com.databricks.dbutils_v1.DBUtilsHolder.dbutils
import com.databricks.labs.overwatch.ApiCallV2
import com.databricks.labs.overwatch.utils._
import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions._

import scala.collection.parallel.ForkJoinTaskSupport
import scala.concurrent.forkjoin.ForkJoinPool

/**
 * The Workspace class gets instantiated once per run per Databricks workspace. THe need for this class evolved
 * over time and is being restructured as part of an outstanding refactor branch which is likely not to be completed
 * until after v1.0 release.
 *
 * @param config
 */
class Workspace(config: Config) extends SparkSessionWrapper {

  private val logger: Logger = Logger.getLogger(this.getClass)
  private var _database: Database = _
  private var _validated: Boolean = false
  private[overwatch] val overwatchRunClusterId = spark.conf.get("spark.databricks.clusterUsageTags.clusterId")

  private[overwatch] def database: Database = _database

  private def setDatabase(value: Database): this.type = {
    _database = value
    this
  }

  private[overwatch] def setValidated(value: Boolean): this.type = {
    _validated = value
    this
  }

  def copy(_config: Config = config): Workspace = {
    val db = this.database
    Workspace(db, _config)
  }

  def isValidated: Boolean = _validated

  /**
   * Most of the jobs data comes from the audit logs but there are several edge cases that result in incomplete
   * jobs log data in the audit logs (same true for cluster specs). As such, each time the jobs module executes
   * a snapshot of actively defined jobs is captured and used to fill in the blanks in the silver+ layers.
   *
   * @return
   */
  def getJobsDF: DataFrame = {

    val jobsEndpoint = "jobs/list"
    val query = Map(
      "limit" -> "25",
      "expand_tasks" -> "true",
      "offset" -> "0"
    )
    ApiCallV2(config.apiEnv, jobsEndpoint,query,2.1)
      .execute()
      .asDF()
      .withColumn("organization_id", lit(config.organizationId))
  }

  /**
   * Exposed config as a public getter to enable access to config for testing. This should not be public facing
   * public function.
   *
   * @return
   */
  def getConfig: Config = config

  def getClustersDF: DataFrame = {
    val clustersEndpoint = "clusters/list"
    ApiCallV2(config.apiEnv, clustersEndpoint)
      .execute()
      .asDF()
      .withColumn("organization_id", lit(config.organizationId))
  }

  /**
   * For future development
   *
   * @param dbfsPath
   * @return
   */
  def getDBFSPaths(dbfsPath: String): DataFrame = {
    val dbfsEndpoint = "dbfs/list"
    val jsonQuery = s"""{"path":"${dbfsPath}"}"""
    ApiCallV2(config.apiEnv, dbfsEndpoint, jsonQuery)
      .execute()
      .asDF()
      .withColumn("organization_id", lit(config.organizationId))

  }

  /**
   * Placeholder for future dev
   *
   * @return
   */
  def getPoolsDF: DataFrame = {
    val poolsEndpoint = "instance-pools/list"
    ApiCallV2(config.apiEnv, poolsEndpoint)
      .execute()
      .asDF()
      .withColumn("organization_id", lit(config.organizationId))
  }

  /**
   * Placeholder for future dev
   *
   * @return
   */
  def getProfilesDF: DataFrame = {
    val profilesEndpoint = "instance-profiles/list"
    ApiCallV2(config.apiEnv, profilesEndpoint).execute().asDF().withColumn("organization_id", lit(config.organizationId))

  }

  /**
   * Placeholder for future dev
   *
   * @return
   */
  def getWorkspaceUsersDF: DataFrame = {
    val workspaceEndpoint = "workspace/list"
    ApiCallV2(config.apiEnv, workspaceEndpoint).execute().asDF().withColumn("organization_id", lit(config.organizationId))
  }

  def getSqlQueryHistoryDF(fromTime: TimeTypes, untilTime: TimeTypes): DataFrame = {
    val sqlQueryHistoryEndpoint = "sql/history/queries"
    val acc = sc.longAccumulator("sqlQueryHistoryAccumulator")
    val startTime = fromTime.asUnixTimeMilli - (1000 * 60 * 60 * 24 * 2) // subtract 2 days for running query merge
    val jsonQuery = Map(
      "max_results" -> "50",
      "include_metrics" -> "true",
      "filter_by.query_start_time_range.start_time_ms" ->  s"$startTime",
      "filter_by.query_start_time_range.end_time_ms" ->  s"${untilTime.asUnixTimeMilli}"
      )
    ApiCallV2(
      config.apiEnv,
      sqlQueryHistoryEndpoint,
      jsonQuery,
      tempSuccessPath = s"${config.tempWorkingDir}/sqlqueryhistory_silver/${System.currentTimeMillis()}",
      accumulator = acc
    )
      .execute()
      .asDF()
      .withColumn("organization_id", lit(config.organizationId))
  }

  def resizeCluster(apiEnv: ApiEnv, numWorkers: Int): Unit = {
    val endpoint = "clusters/resize"
    val jsonQuery = s"""{"cluster_id":"${overwatchRunClusterId}","num_workers":${numWorkers}}"""
    try {
      ApiCallV2(apiEnv, endpoint, jsonQuery).execute()
    } catch {
      case e: ApiCallFailure if e.httpResponse.code == 400 &&
        e.httpResponse.body.contains("cannot transition from Reconfiguring to Reconfiguring") =>
        val resizeErrorAttemptMsg = s"The Overwatch cluster cannot resize to $numWorkers nodes at this time " +
          s"as it is still resizing from a previous module. For smaller workspaces and daily runs " +
          s"intelligent scaling may not be necessary."
        if (config.debugFlag) println(resizeErrorAttemptMsg)
        logger.warn(resizeErrorAttemptMsg)
    }
  }

  /**
   * get EXISTING dataset[s] metadata within the configured Overwatch workspace
   *
   * @return Seq[WorkspaceDataset]
   */
  def getWorkspaceDatasets: Seq[WorkspaceDataset] = {
    dbutils.fs.ls(config.etlDataPathPrefix)
      .filter(_.isDir)
      .map(dataset => {
        val path = dataset.path
        val uri = Helpers.getURI(path)
        val name = if (dataset.name.endsWith("/")) dataset.name.dropRight(1) else dataset.name
        WorkspaceDataset(uri.getPath, name)
      })
  }

  /**
   * Create a backup of the Overwatch datasets
   *
   * @param targetPrefix prefix of path target to send the snap
   * @param cloneLevel   Deep or Shallow
   * @param asOfTS       appends asOfTimestamp option to Delta reader to limit data on clone. This will only go back as
   *                     far as the latest vacuum by design.
   * @param excludes     Array of table names to exclude from the snapshot
   *                     this is the table name only - without the database prefix
   * @return
   */
  def snap(
            targetPrefix: String,
            cloneLevel: String = "DEEP",
            asOfTS: Option[String] = None,
            excludes: Array[String] = Array()
          ): Seq[CloneReport] = {
    val acceptableCloneLevels = Array("DEEP", "SHALLOW")
    require(acceptableCloneLevels.contains(cloneLevel.toUpperCase), s"SNAP CLONE ERROR: cloneLevel provided is " +
      s"$cloneLevel. CloneLevels supported are ${acceptableCloneLevels.mkString(",")}.")

    val sourcesToSnap = getWorkspaceDatasets
      .filterNot(dataset => excludes.map(_.toLowerCase).contains(dataset.name.toLowerCase))
    val cloneSpecs = sourcesToSnap.map(dataset => {
      val sourceName = dataset.name
      val sourcePath = dataset.path
      val targetPath = if (targetPrefix.takeRight(1) == "/") s"$targetPrefix$sourceName" else s"$targetPrefix/$sourceName"
      CloneDetail(sourcePath, targetPath, asOfTS, cloneLevel)
    }).toArray.toSeq
    Helpers.parClone(cloneSpecs)
  }

  /**
   * add existing tables to the metastore in the configured database.
   *
   * @return Seq[WorkspaceMetastoreRegistrationReport]
   */
  def addToMetastore(): Seq[WorkspaceMetastoreRegistrationReport] = {
    require(Helpers.pathExists(config.etlDataPathPrefix), s"This function can only register the Overwatch data tables " +
      s"to the Data Target configured in Overwatch. The location ${config.etlDataPathPrefix} does not exist.")
    val datasets = getWorkspaceDatasets.par
    val taskSupport = new ForkJoinTaskSupport(new ForkJoinPool(getDriverCores * 2))
    datasets.tasksupport = taskSupport
    logger.log(Level.INFO, s"BEGINNING METASTORE REGISTRATION: Database Name ${config.databaseName}")
    val addReport = datasets.map(dataset => {
      val fullTableName = s"${config.databaseName}.${dataset.name}"
      val stmt = s"CREATE TABLE $fullTableName USING DELTA LOCATION '${dataset.path}'"
      logger.log(Level.INFO, stmt)
      try {
        if (spark.catalog.tableExists(fullTableName)) throw new BadConfigException(s"TABLE EXISTS: SKIPPING")
        spark.sql(stmt)
        WorkspaceMetastoreRegistrationReport(dataset, stmt, "SUCCESS")
      } catch {
        case e: BadConfigException =>
          WorkspaceMetastoreRegistrationReport(dataset, stmt, e.getMessage)
        case e: Throwable =>
          val msg = s"TABLE REGISTRATION FAILED: ${e.getMessage}"
          logger.log(Level.ERROR, msg)
          WorkspaceMetastoreRegistrationReport(dataset, stmt, msg)
      }
    }).toArray.toSeq
    spark.sql(s"refresh ${config.databaseName}")
    addReport
  }

}


object Workspace {
  /**
   * Workspace companion object initializer.
   */
  def apply(database: Database, config: Config): Workspace = {
    new Workspace(config).setDatabase(database)
  }

}
