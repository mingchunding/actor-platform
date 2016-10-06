package im.actor.server.push.google

import akka.{ Done, NotUsed }
import akka.actor.{ ActorRef, ActorSystem }
import akka.event.{ Logging, LoggingAdapter }
import akka.stream.actor.ActorPublisher
import akka.stream.scaladsl.{ Flow, Source }
import cats.data.Xor
import im.actor.server.db.DbExtension
import im.actor.server.persist.push.GooglePushCredentialsRepo
import im.actor.server.push.google.GooglePushDelivery.Delivery
import io.circe.parser
import spray.client.pipelining._
import spray.http.{ HttpCharsets, StatusCodes }

import scala.concurrent.Future
import scala.util.{ Failure, Success }

final class DeliveryStream(publisher: ActorRef)(implicit system: ActorSystem) {
  import system.dispatcher

  private val log = Logging(system, getClass)
  private val db = DbExtension(system).db

  private implicit val mat = tolerantMaterializer

  val stream: Future[Done] = Source
    .fromPublisher(ActorPublisher[NotificationDelivery](publisher))
    .via(flow)
    .runForeach {
      // TODO: flatten
      case Xor.Right((body, delivery)) ⇒
        parser.parse(body) match {
          case Xor.Right(json) ⇒
            json.asObject match {
              case Some(obj) ⇒
                obj("error") flatMap (_.asString) match {
                  case Some("InvalidRegistration") ⇒
                    log.warning("Invalid registration, deleting")
                    remove(delivery.m.to)
                  case Some("NotRegistered") ⇒
                    log.warning("Token is not registered, deleting")
                    remove(delivery.m.to)
                  case Some(other) ⇒
                    log.warning("Error in GCM response: {}", other)
                  case None ⇒
                    log.debug("Successfully delivered: {}", delivery)
                }
              case None ⇒
                log.error("Expected JSON Object but got: {}", json)
            }
          case Xor.Left(failure) ⇒ log.error(failure.underlying, "Failed to parse response")
        }
      case Xor.Left(e) ⇒
        log.error(e, "Failed to make request")
    }

  stream onComplete {
    case Failure(e) ⇒
      log.error(e, "Failure in stream")
    case Success(_) ⇒ log.debug("Stream completed")
  }

  private def flow(implicit system: ActorSystem): Flow[NotificationDelivery, Xor[RuntimeException, (String, Delivery)], NotUsed] = {
    import system.dispatcher
    val pipeline = sendReceive
    Flow[NotificationDelivery].mapAsync(2) {
      case (req, del) ⇒
        pipeline(req) map { resp ⇒
          if (resp.status == StatusCodes.OK)
            Xor.Right(resp.entity.data.asString(HttpCharsets.`UTF-8`) → del)
          else
            Xor.Left(new RuntimeException(s"Failed to deliver message, StatusCode was not OK: ${resp.status}"))
        }
    }
  }

  private def remove(regId: String): Future[Int] = db.run(GooglePushCredentialsRepo.deleteByToken(regId))

}
