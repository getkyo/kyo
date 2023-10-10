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
import kyo.concurrent.fibers._
import sttp.client3._
import sttp.client3.ziojson._
import zio.json._
import zio.json.internal.Write

import scala.annotation.targetName
import scala.util.Failure
import scala.util.Success
import scala.util.control.NoStackTrace
import kyo.chatgpt.embeddings.Embeddings
import kyo.chatgpt.plugins._
import java.lang.ref.WeakReference
import kyo.options.Options
import zio.json.ast.Json
import zio.json.internal.Write
import zio.schema.DeriveSchema
import zio.schema.Schema
import zio.schema.codec.JsonCodec
import kyo.chatgpt.util.JsonSchema

object ais {

  import Model._

  type State = Map[AIRef, Context]

  type AIs = Sums[State] with Requests with Tries with IOs with Aspects with Consoles

  object AIs {

    private[kyo] case class Value[T](value: T)

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

    def run[T, S](v: T > (AIs with S)): T > (Requests with Consoles with Tries with S) = {
      val a: T > (Requests with Consoles with Tries with Aspects with S) = Sums[State].run(v)
      val b: T > (Requests with Consoles with Tries with S)              = Aspects.run(a)
      b
    }

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
            local.let(Some(k)) {
              Tries.run(AIs.init.map(_.ask("ping"))).map {
                case Failure(_) =>
                  AIs.fail(s"Invalid API key: $k")
                case Success(_) =>
                  f
              }
            }
          }
        }
    }
  }

  class AI private[ais] () {

    private val ref = AIRef(this)

    private def add[S](
        role: Role,
        content: String > S,
        name: Option[String] > S = None,
        call: Option[Call] > S = None
    ): Unit > (AIs with S) =
      name.map { name =>
        content.map { content =>
          call.map { call =>
            save.map { ctx =>
              ctx.add(role, content, name, call).map(restore)
            }
          }
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
        ctx.messages.reverse.map(msg =>
          s"${msg.role.name}: ${msg.content} ${msg.call.getOrElse("")}"
        )
          .mkString("\n")
      }

    def user[S](msg: String > S): Unit > (AIs with S) =
      add(Role.user, msg, None, None)
    def system[S](msg: String > S): Unit > (AIs with S) =
      add(Role.system, msg, None)
    def assistant[S](msg: String > S): Unit > (AIs with S) =
      assistant(msg, None)
    def assistant[S](msg: String > S, call: Option[Call] > S): Unit > (AIs with S) =
      add(Role.assistant, msg, None, call)
    def function[S](name: String, msg: String > S): Unit > (AIs with S) =
      add(Role.function, msg, Some(name))

    import AIs._

    inline def infer[T](msg: String): T > AIs =
      infer(msg, DeriveSchema.gen[Value[T]])

    private def infer[T](msg: String, t: Schema[Value[T]]): T > AIs = {
      val decoder = JsonCodec.jsonDecoder(t)
      val plugin  = new Plugin("result", "returns the result", JsonSchema(t), identity(_))
      user(msg).andThen {
        fetch(Set(plugin), Some(plugin)).map {
          case (msg, Some(call)) =>
            decoder.decodeJson(call.arguments) match {
              case Left(error) =>
                AIs.fail("Failed to read the inferred value: " + error)
              case Right(value) =>
                value.value
            }
          case (msg, None) =>
            AIs.fail("Expected a function call")
        }
      }
    }

    private def fetch(
        plugins: Set[Plugin],
        constrain: Option[Plugin] = None
    ): (String, Option[Call]) > AIs =
      for {
        _   <- Consoles.println(s"**************")
        _   <- Consoles.println(dump)
        ctx <- save
        key <- AIs.ApiKey.get
        req = Request(ctx, plugins, constrain)
        response <- Tries(Requests(
            _.contentType("application/json")
              .header("Authorization", s"Bearer $key")
              .post(uri"https://api.openai.com/v1/chat/completions")
              .body(req)
              .response(asJson[Response])
        )).map(_.body match {
          case Left(error) =>
            val a = req.toJson
            Tries.fail(a + " => " + error)
          case Right(value) =>
            value
        })
        (content, call) <-
          response.choices.headOption match {
            case None =>
              AIs.fail("no choices")
            case Some(v) =>
              val a = req.toJson
              (
                  v.message.content.getOrElse(""),
                  v.message.function_call.map(c => Call(c.name, c.arguments))
              )
          }
        _ <- Consoles.println("assistant: " + content)
        _ <- assistant(content, call)
      } yield (content, call)

    def ask[S](msg: String > S): String > (AIs with S) = {
      def eval(plugins: Set[Plugin]): String > AIs =
        fetch(plugins).map {
          case (msg, Some(call)) =>
            plugins.find(_.name == call.function) match {
              case None =>
                function(call.function, "Invalid function call: " + call)
                  .andThen(eval(plugins))
              case Some(plugin) =>
                function(call.function, plugin.call(call.arguments))
                  .andThen(eval(plugins))
            }
          case (msg, None) =>
            msg
        }
      user(msg).andThen(Plugins.get.map(eval))
    }
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
    case class Name(name: String)
    case class Function(description: String, name: String, parameters: JsonSchema)
    case class FunctionCall(arguments: String, name: String)
    case class Entry(
        role: String,
        name: Option[String],
        content: Option[String],
        function_call: Option[FunctionCall]
    )
    case class Request(
        model: String,
        messages: List[Entry],
        function_call: Option[Name],
        functions: Option[Set[Function]]
    )

    object Request {
      def apply(ctx: Context, plugins: Set[Plugin], constrain: Option[Plugin]): Request =
        val entries =
          ctx.messages.reverse.map(msg =>
            Entry(
                msg.role.name,
                msg.name,
                Some(msg.content),
                msg.call.map(c => FunctionCall(c.arguments, c.function))
            )
          )
        val functions =
          if (plugins.isEmpty) None
          else Some(plugins.map(p => Function(p.description, p.name, p.schema)))
        Request(
            ctx.model.name,
            entries,
            constrain.map(p => Name(p.name)),
            functions
        )
    }

    case class Choice(message: Entry)
    case class Response(choices: List[Choice])

    implicit val nameEncoder: JsonEncoder[Name]         = DeriveJsonEncoder.gen[Name]
    implicit val functionEncoder: JsonEncoder[Function] = DeriveJsonEncoder.gen[Function]
    implicit val callEncoder: JsonEncoder[FunctionCall] = DeriveJsonEncoder.gen[FunctionCall]
    implicit val entryEncoder: JsonEncoder[Entry]       = DeriveJsonEncoder.gen[Entry]
    implicit val requestEncoder: JsonEncoder[Request]   = DeriveJsonEncoder.gen[Request]
    implicit val callDecoder: JsonDecoder[FunctionCall] = DeriveJsonDecoder.gen[FunctionCall]
    implicit val entryDecoder: JsonDecoder[Entry]       = DeriveJsonDecoder.gen[Entry]
    implicit val choiceDecoder: JsonDecoder[Choice]     = DeriveJsonDecoder.gen[Choice]
    implicit val responseDecoder: JsonDecoder[Response] = DeriveJsonDecoder.gen[Response]
  }
}
