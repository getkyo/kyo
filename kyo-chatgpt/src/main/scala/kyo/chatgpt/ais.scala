package kyo.chatgpt

import kyo.aborts._
import kyo.consoles._
import kyo.core._
import kyo.envs._
import kyo.frames._
import kyo.ios._
import kyo.requests._
import kyo.sums._
import kyo.tries._
import kyo.locals._
import kyo.aspects._
import sttp.client3._
import sttp.client3.ziojson._
import zio.json._

import java.util.ArrayList
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import scala.annotation.targetName
import scala.collection.mutable.ListBuffer
import scala.io.Source
import scala.util.Failure
import scala.util.Success
import scala.util.control.NoStackTrace

import kyo.aspects

object ais {
  val apiKeyProp = "OPENAI_API_KEY"
  val apiKey =
    Option(System.getenv(apiKeyProp))
      .orElse(Option(System.getProperty(apiKeyProp)))
      .getOrElse(throw new Exception(s"Missing $apiKeyProp"))

  opaque type State = Map[AI, Context]

  case class AIException(cause: Any*) extends Exception(cause.mkString(" ")) with NoStackTrace

  opaque type AIs = Sums[State] | Requests | Tries | IOs | Aspects | Consoles

  object AIs {

    type Iso = Sums[State] | Requests | Tries | IOs | Aspects | Consoles | AIs
    def iso[T, S](v: T > (S | Iso)): T > (S | AIs) =
      v

    val askAspect: Aspect[(AI, String), String, AIs] =
      Aspects.init[(AI, String), String, AIs]

    def init: AI > IOs = IOs(AI())

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

    private def add(role: String, msg: Any*): State > AIs =
      Sums[State].add(Map(this -> Context(messages = List(Message(role, msg.mkString(" "))))))

    val save: Context > AIs = Sums[State].get.map(_.getOrElse(this, Context.empty))

    def restore[T, S](ctx: Context)(v: T > (S | AIs.Iso)): T > (S | AIs) =
      Sums[State].get.map { st =>
        Sums[State].set(st + (this -> ctx)).map(_ => v)
      }

    @targetName("cloneAI")
    val clone: AI > AIs =
      for {
        res <- AIs.init
        st  <- Sums[State].get
        _   <- Sums[State].set(st + (res -> st.getOrElse(this, Context.empty)))
      } yield res

    val dump: String > AIs =
      save.map { ctx =>
        ctx.messages.map(msg => s"${msg.role}: ${msg.content}")
          .mkString("\n")
      }

    def user(msg: Any*): AI > AIs      = add("user", msg: _*).map(_ => this)
    def system(msg: Any*): AI > AIs    = add("system", msg: _*).map(_ => this)
    def assistant(msg: Any*): AI > AIs = add("assistant", msg: _*).map(_ => this)

    def ask(msg: Any*): String > AIs =
      def doIt(ai: AI, msg: String): String > AIs =
        for {
          st <- ai.add("user", msg).map(_.getOrElse(this, Context.empty))
          _  <- Consoles.println("******************")
          _  <- Consoles.println(dump)
          response <- Tries(Requests(
              _.contentType("application/json")
                .header("Authorization", s"Bearer $apiKey")
                .post(uri"https://api.openai.com/v1/chat/completions")
                .body(st)
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
          _ <- Consoles.println(dump)
          _ <- assistant(content)
        } yield content
      AIs.askAspect((this, msg.mkString(" ")))(doIt)
  }

  private given Summer[State] with
    val init: State = Map.empty
    def add(x: State, y: State): State =
      x ++ y.map { case (k, v) => k -> (x.get(k).getOrElse(Context.empty) ++ v) }

  case class Message(role: String, content: String)
  case class Choice(message: Message)
  case class Response(choices: List[Choice])

  case class Context(
      model: String = "gpt-3.5-turbo",
      messages: List[Message] = Nil
  ) {
    def system(msg: String)    = Context(model, messages :+ Message("system", msg))
    def user(msg: String)      = Context(model, messages :+ Message("user", msg))
    def assistant(msg: String) = Context(model, messages :+ Message("assistant", msg))
    def ++(that: Context)      = Context(model, messages ++ that.messages)
  }

  object Context {
    val empty = Context()
  }

  given JsonEncoder[Message]  = DeriveJsonEncoder.gen[Message]
  given JsonEncoder[Context]  = DeriveJsonEncoder.gen[Context]
  given JsonDecoder[Message]  = DeriveJsonDecoder.gen[Message]
  given JsonDecoder[Choice]   = DeriveJsonDecoder.gen[Choice]
  given JsonDecoder[Response] = DeriveJsonDecoder.gen[Response]
}
