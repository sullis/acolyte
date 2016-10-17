package acolyte.reactivemongo

import scala.concurrent.{
  ExecutionContext,
  Future
}, ExecutionContext.Implicits.global
import scala.util.{ Failure, Success, Try }

import com.typesafe.config.ConfigFactory
import akka.actor.{ ActorRef, ActorSystem ⇒ AkkaSystem, Props }

import reactivemongo.api.commands.GetLastError
import reactivemongo.bson.{ BSONArray, BSONDocument, BSONString }
import reactivemongo.core.actors.{
  Close,
  CheckedWriteRequestExpectingResponse ⇒ CheckedWriteRequestExResp,
  PrimaryAvailable,
  RequestMakerExpectingResponse,
  RegisterMonitor,
  SetAvailable
}
import reactivemongo.core.protocol.{
  CheckedWriteRequest,
  Query ⇒ RQuery,
  RequestMaker,
  Response
}
import reactivemongo.core.nodeset.ProtocolMetadata

private[reactivemongo] class Actor(handler: ConnectionHandler)
    extends reactivemongo.core.actors.MongoDBSystem {

  import reactivemongo.core.nodeset.{ Authenticate, ChannelFactory, Connection }

  lazy val initialAuthenticates = Seq.empty[Authenticate]

  protected def authReceive: PartialFunction[Any, Unit] = { case _ ⇒ () }

  val supervisor = "Acolyte"
  val name = "AcolyteConnection"
  lazy val seeds = Seq.empty[String]

  val options = reactivemongo.api.MongoConnectionOptions()

  protected def sendAuthenticate(connection: Connection, authentication: Authenticate): Connection = connection

  protected def newChannelFactory(effect: Unit): ChannelFactory =
    new ChannelFactory(options)

  private def handleWrite(chanId: Int, op: WriteOp, req: Request): Option[Response] = Try(handler.writeHandler(chanId, op, req)) match {
    case Failure(cause) ⇒ Some(InvalidWriteHandler(chanId, cause.getMessage))

    case Success(res) ⇒ res.map {
      case Success(r) ⇒ r
      case Failure(e) ⇒ MongoDB.WriteError(chanId, Option(e.getMessage).
        getOrElse(e.getClass.getName)) match {
        case Success(err) ⇒ err
        case _            ⇒ MongoDB.MkWriteError(chanId)
      }
    }
  }

  override lazy val receive: Receive = {
    case msg @ CheckedWriteRequestExResp(
      r @ CheckedWriteRequest(op, doc, GetLastError(_, _, _, _))) ⇒ {

      val req = Request(op.fullCollectionName, doc.merged)
      val exp = new ExpectingResponse(msg)
      val cid = r()._1.channelIdHint getOrElse 1
      val resp = MongoDB.WriteOp(op).fold({
        MongoDB.WriteError(cid, s"No write operator: $msg") match {
          case Success(err) ⇒ err
          case _            ⇒ MongoDB.MkWriteError(cid)
        }
      })(handleWrite(cid, _, req).
        getOrElse(NoWriteResponse(cid, msg.toString)))

      exp.promise.success(resp)
    }

    case msg @ RequestMakerExpectingResponse(RequestMaker(
      op @ RQuery(_ /*flags*/ , coln, off, len),
      doc, _ /*pref*/ , chanId), _) ⇒ {
      val exp = new ExpectingResponse(msg)
      val cid = chanId getOrElse 1
      val req = Request(coln, doc.merged)

      val resp = req match {
        case Request(_, SimpleBody((k @ WriteQuery(op),
          BSONString(cn)) :: es)) if (coln endsWith ".$cmd") ⇒ {

          val opBody = if (k == "insert") {
            es.collectFirst {
              case ("documents", a @ BSONArray(_)) ⇒
                a.values.toList.collect {
                  case doc @ BSONDocument(_) ⇒ doc
                }

              case _ ⇒ List.empty[BSONDocument]
            }
          } else {
            val Key = k + "s"

            es.collectFirst {
              case (Key, a @ BSONArray(_)) ⇒
                a.values.headOption match {
                  case Some(ValueDocument(("q", sel @ BSONDocument(_)) ::
                    ("u", fil @ BSONDocument(_)) :: _)) ⇒ List(sel, fil)

                  case Some(ValueDocument(("q", sel @ BSONDocument(_)) :: _)) ⇒
                    List(sel)

                  case _ ⇒ List.empty[BSONDocument]
                }
            }
          }

          val wreq = new Request {
            val collection = coln.dropRight(4) + cn
            val body = opBody.getOrElse(List.empty)
          }

          handleWrite(cid, op, wreq).getOrElse(
            NoWriteResponse(cid, msg.toString)
          )
        }

        case Request(coln, SimpleBody(ps)) ⇒ {
          val qreq = new Request {
            val collection = coln
            val body = ps.collectFirst {
              case ("$query", q @ BSONDocument(_)) ⇒ q
            }.fold(req.body)(List(_))
          }

          Try(handler.queryHandler(cid, qreq)) match {
            case Failure(cause) ⇒ InvalidQueryHandler(cid, cause.getMessage)

            case Success(res) ⇒ res.fold(NoQueryResponse(cid, msg.toString)) {
              case Success(r) ⇒ r
              case Failure(e) ⇒ MongoDB.QueryError(cid, Option(e.getMessage).
                getOrElse(e.getClass.getName)) match {
                case Success(err) ⇒ err
                case _            ⇒ MongoDB.MkQueryError(cid)
              }
            }
          }
        }
      }

      resp.error.fold(exp.promise.success(resp))(exp.promise.failure(_))
    }

    case RegisterMonitor ⇒ {
      // TODO: configure protocol metadata
      sender ! PrimaryAvailable(ProtocolMetadata.Default)
      sender ! SetAvailable(ProtocolMetadata.Default)
    }

    case Close ⇒ postStop()

    case msg ⇒
      //println(s"message = $msg")

      //next forward msg
      ()
  }

  // ---

  // Write operations sent as `Query`
  private object WriteQuery {
    def unapply(repr: String): Option[WriteOp] = repr match {
      case "insert" ⇒ Some(InsertOp)
      case "update" ⇒ Some(UpdateOp)
      case "delete" ⇒ Some(DeleteOp)
      case _        ⇒ None
    }
  }

  // Fallback response when no handler provides a query response.
  @inline private def NoQueryResponse(chanId: Int, req: String): Response =
    MongoDB.QueryError(chanId, s"No response: $req") match {
      case Success(resp) ⇒ resp
      case _             ⇒ MongoDB.MkQueryError(chanId)
    }

  // Fallback response when write handler is failing.
  @inline private def InvalidWriteHandler(chanId: Int, msg: String): Response =
    MongoDB.WriteError(chanId, s"Invalid write handler: $msg") match {
      case Success(resp) ⇒ resp
      case _             ⇒ MongoDB.MkWriteError(chanId)
    }

  // Fallback response when no handler provides a write response.
  @inline private def NoWriteResponse(chanId: Int, req: String): Response =
    MongoDB.WriteError(chanId, s"No response: $req") match {
      case Success(resp) ⇒ resp
      case _             ⇒ MongoDB.MkWriteError(chanId)
    }

  // Fallback response when query handler is failing.
  @inline private def InvalidQueryHandler(chanId: Int, msg: String): Response =
    MongoDB.QueryError(chanId, s"Invalid query handler: $msg") match {
      case Success(resp) ⇒ resp
      case _             ⇒ MongoDB.MkQueryError(chanId)
    }

}