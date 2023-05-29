package kyo.chatgpt

import kyo.aspects._
import kyo.chatgpt.contexts._
import kyo.consoles._
import kyo._
import kyo.ios._
import kyo.requests._
import kyo.sums._
import kyo.tries._
import kyo.locals._
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
import kyo.options.Options

object ais {

  import Model._

  type State = Map[AIRef, Context]

  type AIs = Sums[State] with Requests with Tries with IOs with Aspects with Consoles

  object AIs {

    val askAspect: Aspect[(AI, String), String, AIs] =
      Aspects.init[(AI, String), String, AIs]

    val init: AI > IOs = IOs(new AI())

    def restore[S](ctx: Context > S): AI > (AIs with S) =
      init.map { ai =>
        ai.restore(ctx).map(_ => ai)
      }

    def fail[T, S](cause: String > S): T > (AIs with S) =
      cause.map(cause => Tries.fail(AIException(cause)))

    def transactional[T, S](f: => T > S): T > (AIs with S) =
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

    def ephemeral[T, S](f: => T > S): T > (AIs with S) =
      Sums[State].get.map { st =>
        Tries.run(f).map(r => Sums[State].set(st).map(_ => r.get))
      }

    def run[T, S](v: T > (AIs with S)): T > (Requests with Consoles with Tries with S) =
      val a: T > (Requests with Consoles with Tries with Aspects with S) = Sums[State].run(v)
      val b: T > (Requests with Consoles with Tries with S)              = Aspects.run(a)
      b

    object ApiKey {
      private val local = Locals.init[Option[String]] {
        val apiKeyProp = "OPENAI_API_KEY"
        Option(System.getenv(apiKeyProp))
          .orElse(Option(System.getProperty(apiKeyProp)))
      }
      private val example = "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"

      val get: String > AIs =
        Options.getOrElse(local.get, AIs.fail("No API key found"))

      def let[T, S1, S2](key: String > S1)(f: => T > S2): T > (S1 with S2 with AIs) =
        key.map { k =>
          if (k.size != example.size) {
            AIs.fail(s"Invalid API key: $k")
          } else {
            Tries.run(AIs.init.map(_.ask("0", 1))).map {
              case Failure(_) =>
                AIs.fail(s"Invalid API key: $k")
              case Success(_) =>
                local.let(Some(k))(f)
            }
          }
        }
    }
  }

  class AI private[ais] () {

    private val ref = AIRef(this)

    private def add[S](role: Role, content: String > S): Unit > (AIs with S) =
      content.map { content =>
        save.map { ctx =>
          ctx.add(role, content).map(restore)
        }
      }

    val save: Context > AIs = Sums[State].get.map(_.getOrElse(ref, Contexts.init))

    def restore[T, S](ctx: Context > S): Unit > (AIs with S) =
      ctx.map { ctx =>
        Sums[State].get.map { st =>
          Sums[State].set(st + (ref -> ctx)).unit
        }
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

    def user[S](msg: String > S): Unit > (AIs with S) =
      add(Role.user, msg)
    def system[S](msg: String > S): Unit > (AIs with S) =
      add(Role.system, msg)
    def assistant[S](msg: String > S): Unit > (AIs with S) =
      add(Role.assistant, msg)

    def ask[S](msg: String > S, maxTokens: Int = -1): String > (AIs with S) =
      def doIt(ai: AI, msg: String): String > AIs =
        for {
          _   <- add(Role.user, msg)
          _   <- Consoles.println(s"**************")
          _   <- Consoles.println(dump)
          ctx <- save.map(_.compact)
          _   <- restore(ctx)
          key <- AIs.ApiKey.get
          response <- Tries(Requests(
              _.contentType("application/json")
                .header("Authorization", s"Bearer $key")
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
                AIs.fail("no choices")
              case Some(v) =>
                v.message.content: String > Any
            }
          _ <- Consoles.println("assistant: " + content)
          _ <- assistant(content)
        } yield content
      msg.map(msg => AIs.askAspect((this, msg))(doIt))
  }

  class AIRef(ai: AI) extends WeakReference[AI](ai) {
    def isValid(): Boolean = get() != null
    override def equals(obj: Any): Boolean = obj match {
      case other: AIRef => get() == other.get()
      case _            => false
    }
    override def hashCode(): Int = get().hashCode()
  }

  case class AIException(cause: String) extends Exception(cause) with NoStackTrace

  private implicit val summer: Summer[State] =
    new Summer[State] {
      val init: State = Map.empty
      def add(x: State, y: State): State =
        val merged = x ++ y.map { case (k, v) => k -> (x.get(k).getOrElse(Contexts.init) ++ v) }
        merged.filter { case (k, v) => k.isValid() && v.messages.nonEmpty }
    }

  private object Model {
    case class Entry(role: String, content: String)
    case class Request(model: String, messages: List[Entry], max_tokens: Int)

    object Request {
      def apply(ctx: Context, maxTokens: Int): Request =
        val entries =
          ctx.messages.reverse.map(msg => Entry(msg.role.name, msg.content))
        val mt =
          if (maxTokens <= 0) ctx.model.maxTokens - ctx.tokens - 10
          else maxTokens
        Request(ctx.model.name, entries, mt)
    }

    case class Choice(message: Entry)
    case class Response(choices: List[Choice])

    implicit val entryEncoder: JsonEncoder[Entry]       = DeriveJsonEncoder.gen[Entry]
    implicit val requestEncoder: JsonEncoder[Request]   = DeriveJsonEncoder.gen[Request]
    implicit val entryDecoder: JsonDecoder[Entry]       = DeriveJsonDecoder.gen[Entry]
    implicit val choiceDecoder: JsonDecoder[Choice]     = DeriveJsonDecoder.gen[Choice]
    implicit val responseDecoder: JsonDecoder[Response] = DeriveJsonDecoder.gen[Response]
  }
}
