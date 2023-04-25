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
import kyo.chatgpt.embeddings.Embeddings
import java.lang.ref.WeakReference

object ais {

  import Model._

  val apiKey = {
    val apiKeyProp = "OPENAI_API_KEY"
    Option(System.getenv(apiKeyProp))
      .orElse(Option(System.getProperty(apiKeyProp)))
      .getOrElse(throw new Exception(s"Missing $apiKeyProp"))
  }

  opaque type State = Map[AIRef, Context]

  opaque type AIs = Sums[State] | Requests | Tries | IOs | Aspects | Consoles

  object AIs {

    type Iso = Sums[State] | Requests | Tries | IOs | Aspects | Consoles | AIs
    def iso[T, S](v: T > (S | Iso)): T > (S | AIs) =
      v

    val askAspect: Aspect[(AI, String), String, AIs] =
      Aspects.init[(AI, String), String, AIs]

    def init: AI > IOs = IOs(new AI())

    def init(ctx: Context): AI > AIs =
      init.map { ai =>
        ai.restore(ctx).map(_ => ai)
      }

    def fail[T](cause: String): T > AIs =
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

    private val ref = AIRef(this)

    private def add(role: Role, content: String): Unit > AIs =
      save.map { ctx =>
        ctx.add(role, content).map(restore)
      }

    val save: Context > AIs = Sums[State].get.map(_.getOrElse(ref, Contexts.init))

    def restore[T, S](ctx: Context): Unit > (S | AIs) =
      Sums[State].get.map { st =>
        Sums[State].set(st + (ref -> ctx)).unit
      }

    @targetName("cloneAI")
    val clone: AI > AIs =
      for {
        res <- AIs.init
        st  <- Sums[State].get
        _   <- Sums[State].set(st + (res.ref -> st.getOrElse(ref, Contexts.init)))
      } yield res

    val dump: String > AIs =
      save.map { ctx =>
        ctx.messages.reverse.map(msg => s"${msg.role}: ${msg.content}")
          .mkString("\n")
      }

    def user(msg: String): Unit > AIs      = add(Role.user, msg)
    def system(msg: String): Unit > AIs    = add(Role.system, msg)
    def assistant(msg: String): Unit > AIs = add(Role.assistant, msg)

    def ask(msg: String, maxTokens: Int = -1): String > AIs =
      def doIt(ai: AI, msg: String): String > AIs =
        for {
          _   <- add(Role.user, msg)
          _   <- Consoles.println(s"**************")
          _   <- Consoles.println(dump)
          ctx <- save.map(_.compact)
          _   <- restore(ctx)
          response <- Tries(Requests(
              _.contentType("application/json")
                .header("Authorization", s"Bearer $apiKey")
                .post(uri"https://api.openai.com/v1/chat/completions")
                .body(Request(ctx, maxTokens))
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
      AIs.askAspect((this, msg))(doIt)
  }

  private class AIRef(ai: AI) extends WeakReference[AI](ai) {
    def isValid(): Boolean = get() != null
    override def equals(obj: Any): Boolean = obj match {
      case other: AIRef => get() == other.get()
      case _            => false
    }
    override def hashCode(): Int = get().hashCode()
  }

  case class AIException(cause: String) extends Exception(cause) with NoStackTrace

  private given Summer[State] with
    val init: State = Map.empty
    def add(x: State, y: State): State =
      val merged = x ++ y.map { case (k, v) => k -> (x.get(k).getOrElse(Contexts.init) ++ v) }
      merged.filter { case (k, v) => k.isValid() && v.messages.nonEmpty }

  private object Model {
    case class Entry(role: String, content: String)
    case class Request(model: String, messages: List[Entry], max_tokens: Int)

    object Request {
      def apply(ctx: Context, maxTokens: Int): Request =
        val entries =
          ctx.messages.reverse.map(msg => Entry(msg.role.name, msg.content))
        val mt =
          if (maxTokens <= 0) ctx.model.maxTokens
          else maxTokens
        Request(ctx.model.name, entries, mt)
    }

    case class Choice(message: Entry)
    case class Response(choices: List[Choice])

    given JsonEncoder[Entry]    = DeriveJsonEncoder.gen[Entry]
    given JsonEncoder[Request]  = DeriveJsonEncoder.gen[Request]
    given JsonDecoder[Entry]    = DeriveJsonDecoder.gen[Entry]
    given JsonDecoder[Choice]   = DeriveJsonDecoder.gen[Choice]
    given JsonDecoder[Response] = DeriveJsonDecoder.gen[Response]
  }
}
