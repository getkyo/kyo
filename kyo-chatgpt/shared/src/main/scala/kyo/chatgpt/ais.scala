package kyo.chatgpt

import kyo.aspects._
import kyo.chatgpt.contexts._
import kyo.consoles._
import kyo.core._
import kyo.ios._
import kyo.requests._
import kyo.sums._
import kyo.tries._
import sttp.client3._
import sttp.client3.ziojson._
import zio.json._
import zio.json.internal.Write

import scala.annotation.targetName
import scala.util.Failure
import scala.util.Success
import scala.util.control.NoStackTrace

object ais {

  val apiKey = {
    val apiKeyProp = "OPENAI_API_KEY"
    Option(System.getenv(apiKeyProp))
      .orElse(Option(System.getProperty(apiKeyProp)))
      .getOrElse(throw new Exception(s"Missing $apiKeyProp"))
  }

  opaque type State = Map[AI, Context]

  case class AIException(cause: Any*) extends Exception(cause.mkString(" ")) with NoStackTrace

  opaque type AIs = Sums[State] | Requests | Tries | IOs | Aspects | Consoles

  object AIs {

    type Iso = Sums[State] | Requests | Tries | IOs | Aspects | Consoles | AIs
    def iso[T, S](v: T > (S | Iso)): T > (S | AIs) =
      v

    val askAspect: Aspect[(AI, String), String, AIs] =
      Aspects.init[(AI, String), String, AIs]

    def init: AI = AI()

    def init(ctx: Context): AI > AIs =
      val ai = init
      ai.restore(ctx).map(_ => ai)

    def fail[T](cause: Any*): T > AIs =
      Tries.fail(AIException(cause))

    def transactional[T, S](f: => T > (S | Iso)): T > (S | AIs) =
      Sums[State].get.map { st =>
        IOs.attempt(f).map {
          case Failure(ex) =>
            Sums[State].set(st).map { _ =>
              Tries.fail(ex)
            }
          case Success(value) =>
            value
        }
      }

    def ephemeral[T, S](f: => T > (S | Iso)): T > (S | AIs) =
      Sums[State].get.map { st =>
        (f < Tries).map(r => Sums[State].set(st).map(_ => r.get))
      }

    def run[T, S](v: T > (S | Iso)): T > (S | Requests | Consoles | Tries) =
      Requests.iso(Aspects.run(Sums[State].drop(v)))
  }

  class AI private[ais] () {

    private def add(role: Role, msg: Any*): Unit > AIs =
      save.map { ctx =>
        ctx.add(role, msg.mkString(" ")).map(restore)
      }

    val save: Context > AIs = Sums[State].get.map(_.getOrElse(this, Context.init))

    def restore[T, S](ctx: Context): Unit > (S | AIs) =
      Sums[State].get.map { st =>
        Sums[State].set(st + (this -> ctx)).unit
      }

    @targetName("cloneAI")
    val clone: AI > AIs =
      for {
        res <- AIs.init
        st  <- Sums[State].get
        _   <- Sums[State].set(st + (res -> st.getOrElse(this, Context.init)))
      } yield res

    val dump: String > AIs =
      save.map { ctx =>
        ctx.messages.map(msg => s"${msg.role}: ${msg.content}")
          .mkString("\n")
      }

    def user(msg: Any*): AI > AIs      = add(Role.user, msg: _*).map(_ => this)
    def system(msg: Any*): AI > AIs    = add(Role.system, msg: _*).map(_ => this)
    def assistant(msg: Any*): AI > AIs = add(Role.assistant, msg: _*).map(_ => this)

    def ask(msg: Any*): String > AIs =
      def doIt(ai: AI, msg: String): String > AIs =
        for {
          _   <- add(Role.user, msg)
          ctx <- save
          _   <- Consoles.println(s"**************")
          _   <- Consoles.println(dump)
          response <- Tries(Requests(
              _.contentType("application/json")
                .header("Authorization", s"Bearer $apiKey")
                .post(uri"https://api.openai.com/v1/chat/completions")
                .body(ctx)
                .response(asJson[Response])
          )).map(_.body match {
            case Left(error)  => Tries.fail(error)
            case Right(value) => value
          })
          content <-
            response.choices.headOption match {
              case None =>
                AIs.fail[String]("no choices")
              case Some(v) =>
                v.message.content: String > Nothing
            }
          _ <- Consoles.println("assistant: " + content)
          _ <- assistant(content)
        } yield content
      AIs.askAspect((this, msg.mkString(" ")))(doIt)
  }

  private given Summer[State] with
    val init: State = Map.empty
    def add(x: State, y: State): State =
      x ++ y.map { case (k, v) => k -> (x.get(k).getOrElse(Context.init) ++ v) }

  case class Choice(message: Message)
  case class Response(choices: List[Choice])

  given JsonEncoder[Role] = new JsonEncoder[Role] {
    def unsafeEncode(a: Role, indent: Option[Int], out: Write): Unit =
      out.write(a.toString())
  }

  given JsonDecoder[Choice]   = DeriveJsonDecoder.gen[Choice]
  given JsonDecoder[Response] = DeriveJsonDecoder.gen[Response]
}
