package kyo

import kyo.consoles._
import kyo.core._
import kyo.envs._
import kyo.frames._
import kyo.ios._
import kyo.requests._
import kyo.sums._
import kyo.tries._
import sttp.client3._

import java.util.ArrayList
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import scala.annotation.targetName
import scala.collection.mutable.ListBuffer
import scala.io.Source
import sttp.client3.ziojson._
import zio.json._

object ais {
  val apiKeyProp = "OPENAI_API_KEY"
  val apiKey =
    Option(System.getenv(apiKeyProp))
      .orElse(Option(System.getProperty(apiKeyProp)))
      .getOrElse(throw new Exception(s"Missing $apiKeyProp"))

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

  opaque type State = Map[AI, Context]

  case class AI private[ais] (id: Int) {

    private def add(role: String, msg: String): State > AIs =
      Sums.add(Map(this -> Context(messages = List(Message(role, msg)))))

    def user(msg: String): Unit > AIs      = add("user", msg).unit
    def system(msg: String): Unit > AIs    = add("system", msg).unit
    def assistant(msg: String): Unit > AIs = add("assistant", msg).unit

    def ask(inputs: Any*): String > AIs =
      for {
        st <- add("user", inputs.mkString(" "))(_.getOrElse(this, Context()))
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
        _ <- assistant(content)
      } yield content
  }

  private given Summer[State] with
    def init: State = Map.empty
    def add(x: State, y: State): State =
      x ++ y.map { case (k, v) => k -> (x.get(k).getOrElse(Context()) ++ v) }

  type AIs = Sums[State] | Requests

  object AIs {
    private val nextId = new AtomicInteger

    def init: AI > IOs =
      IOs(AI(nextId.incrementAndGet()))

    def run[T, S](v: T > (S | AIs)): T > (S | Requests) =
      Sums.drop[State](v)
  }
}
