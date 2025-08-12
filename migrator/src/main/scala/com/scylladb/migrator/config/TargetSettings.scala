package com.scylladb.migrator.config

import cats.implicits._
import com.scylladb.migrator.AwsUtils
import io.circe.{ Decoder, DecodingFailure, Encoder, Json }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.syntax._
import software.amazon.awssdk.services.dynamodb.model.BillingMode
import com.scylladb.migrator.config.BillingModeCodec._

sealed trait TargetSettings
object TargetSettings {
  case class Scylla(host: String,
                    port: Int,
                    localDC: Option[String],
                    credentials: Option[Credentials],
                    sslOptions: Option[SSLOptions],
                    keyspace: String,
                    table: String,
                    connections: Option[Int],
                    stripTrailingZerosForDecimals: Boolean,
                    writeTTLInS: Option[Int],
                    writeWritetimestampInuS: Option[Long],
                    consistencyLevel: String)
      extends TargetSettings

  case class DynamoDB(endpoint: Option[DynamoDBEndpoint],
                      region: Option[String],
                      credentials: Option[AWSCredentials],
                      table: String,
                      writeThroughput: Option[Int],
                      throughputWritePercent: Option[Float],
                      streamChanges: Boolean,
                      skipInitialSnapshotTransfer: Option[Boolean],
                      removeConsumedCapacity: Option[Boolean] = None,
                      billingMode: Option[BillingMode] = None)
      extends TargetSettings {
    lazy val finalCredentials: Option[com.scylladb.migrator.AWSCredentials] =
      AwsUtils.computeFinalCredentials(credentials, endpoint, region)
  }

  implicit val decoder: Decoder[TargetSettings] =
    Decoder.instance { cursor =>
      cursor.get[String]("type").flatMap {
        case "scylla" | "cassandra" =>
          deriveDecoder[Scylla].apply(cursor)
        case "dynamodb" | "dynamo" =>
          deriveDecoder[DynamoDB].apply(cursor)
        case otherwise =>
          Left(DecodingFailure(s"Invalid target type: ${otherwise}", cursor.history))
      }
    }

  implicit val encoder: Encoder[TargetSettings] =
    Encoder.instance {
      case t: Scylla =>
        deriveEncoder[Scylla].encodeObject(t).add("type", Json.fromString("scylla")).asJson

      case t: DynamoDB =>
        deriveEncoder[DynamoDB].encodeObject(t).add("type", Json.fromString("dynamodb")).asJson
    }
}
