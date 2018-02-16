package filodb.http

import scala.util.{Failure, Success}

import akka.actor.ActorRef
import akka.http.scaladsl.model.{StatusCodes => Codes}
import akka.http.scaladsl.server.Directives._
import com.typesafe.scalalogging.StrictLogging
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport

import filodb.coordinator.{CurrentShardSnapshot, NodeClusterActor}
import filodb.core.{DatasetRef, ErrorResponse}
import filodb.core.store.IngestionConfig
import filodb.http.apiv1.{HttpSchema, HttpShardState}

class ClusterApiRoute(clusterProxy: ActorRef) extends FiloRoute with StrictLogging {
  import FailFastCirceSupport._
  import io.circe.generic.auto._
  import HttpSchema._
  import filodb.coordinator.client.Client._
  import NodeClusterActor._

  val route = pathPrefix("api" / "v1" / "cluster") {
    // GET /api/v1/cluster/<dataset>/status - shard health status report
    path(Segment / "status") { dataset =>
      get {
        onSuccess(asyncAsk(clusterProxy, GetShardMap(DatasetRef.fromDotString(dataset)))) {
          case CurrentShardSnapshot(_, map) =>
            val statusList = map.shardValues.zipWithIndex.map { case ((ref, status), idx) =>
                               HttpShardState(idx, status.toString,
                                              if (ref == ActorRef.noSender) "" else ref.path.address.toString) }
            complete(httpList(statusList))
          case DatasetUnknown(_)            =>
            complete(Codes.NotFound -> httpErr("DatasetUnknown", s"Dataset $dataset is not registered"))
        }
      }
    } ~
    // POST /api/v1/cluster/<dataset> - set up dataset for streaming ingestion
    path(Segment) { dataset =>
      post {
        entity(as[String]) { sourceConfig =>
          IngestionConfig(sourceConfig, noOpSource.streamFactoryClass) match {
            case Success(ingestionConfig) =>
              try onSuccess(asyncAsk(clusterProxy, SetupDataset(ingestionConfig))) {
                case DatasetVerified  => complete(httpList(Seq.empty[String]))
                case e: ErrorResponse => complete(Codes.Conflict -> httpErr(e.toString, e.toString))
              }
              catch { case e: Exception =>
                complete(Codes.InternalServerError -> httpErr(e))
              }

            case Failure(e) =>
              logger.error(s"Unable to parse configuration to setup dataset.", e)
              complete(Codes.BadRequest -> httpErr(s"Configuration parsing error: ${e.getClass.getName}", e.getMessage))
          }
        }
      }
    } ~
    // GET /api/v1/cluster - List the datasets registered for streaming ingestion
    pathEnd {
      get {
        complete {
          asyncTypedAsk[Seq[DatasetRef]](clusterProxy, ListRegisteredDatasets).map { refs =>
            httpList(refs.map(_.toString))
          }
        }
      }
    }
  }
}