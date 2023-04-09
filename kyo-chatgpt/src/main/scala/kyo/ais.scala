package kyo

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

  opaque type AIs = Sums[State] | Requests | Tries | IOs | Aspects

  object AIs {

    val askAspect: Aspect[(AI, String), String, AIs] =
      Aspects.init[(AI, String), String, AIs]

    def init: AI > IOs = IOs(AI())

    def iso[T, S](v: T > (S | Requests | Tries | IOs | Aspects)): T > (S | AIs) =
      v

    def fail[T](cause: Any*): T > AIs =
      Tries.fail(AIException(cause))

    def transactional[T, S](f: => T > (S | Requests | Tries | IOs | AIs)): T > (S | AIs) =
      Sums[State].get { st =>
        IOs.attempt(f) {
          case Failure(ex) =>
            Sums[State].set(st) { _ =>
              Tries.fail(ex)
            }
          case Success(value) =>
            value
        }
      }

    def ephemeral[T, S](f: => T > (S | Requests | Tries | IOs | AIs)): T > (S | AIs) =
      Sums[State].get { st =>
        (f < Tries)(r => Sums[State].set(st)(_ => r.get))
      }

    def clone[S](ai: AI > (S | Requests | Tries | IOs | AIs)): AI > (S | AIs) =
      for {
        orig <- ai
        res  <- init
        st   <- Sums[State].get
        _    <- Sums[State].set(st + (res -> st.getOrElse(orig, Context())))
      } yield res

    def run[T, S](v: T > (S | Requests | Tries | IOs | AIs)): T > (S | Requests) =
      Requests.iso(Aspects.run(Sums[State].drop(Tries.run(v))(_.get)))
  }

  class AI private[ais] () {

    private def add(role: String, msg: Any*): State > AIs =
      // println(s"$role: ${msg.mkString(" ")}")
      Sums[State].add(Map(this -> Context(messages = List(Message(role, msg.mkString(" "))))))

    def dump: String > AIs =
      Sums[State].get(
          _.getOrElse(this, Context()).messages.map(msg => s"${msg.role}: ${msg.content}").mkString(
              "\n"
          )
      )

    def user(msg: Any*): AI > AIs      = add("user", msg: _*)(_ => this)
    def system(msg: Any*): AI > AIs    = add("system", msg: _*)(_ => this)
    def assistant(msg: Any*): AI > AIs = add("assistant", msg: _*)(_ => this)

    def ask(msg: Any*): String > AIs =
      def doIt(ai: AI, msg: String): String > AIs =
        for {
          st <- ai.add("user", msg)(_.getOrElse(this, Context()))
          d  <- dump
          _  <- println("*********************")
          _  <- println(d)
          response <- Requests(
              _.contentType("application/json")
                .header("Authorization", s"Bearer $apiKey")
                .post(uri"https://api.openai.com/v1/chat/completions")
                .body(st)
                .response(asJson[Response])
          )(_.body {
            case Left(error)  => throw new Exception(error)
            case Right(value) => value
          })
          content <- response.choices match {
            case Nil => throw new Exception("No choices")
            case xs  => xs.head.message.content
          }
          // _ <- println(content)
          _ <- assistant(content)
        } yield content
      AIs.askAspect((this, msg.mkString(" ")))(doIt)
  }

  private given Summer[State] with
    def init: State = Map.empty
    def add(x: State, y: State): State =
      x ++ y.map { case (k, v) => k -> (x.get(k).getOrElse(Context()) ++ v) }

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

  given JsonEncoder[Message]  = DeriveJsonEncoder.gen[Message]
  given JsonEncoder[Context]  = DeriveJsonEncoder.gen[Context]
  given JsonDecoder[Message]  = DeriveJsonDecoder.gen[Message]
  given JsonDecoder[Choice]   = DeriveJsonDecoder.gen[Choice]
  given JsonDecoder[Response] = DeriveJsonDecoder.gen[Response]
}
