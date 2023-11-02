package kyo.chatgpt

import kyo._
import kyo.aspects._
import kyo.chatgpt.configs._
import kyo.chatgpt.contexts._
import kyo.chatgpt.completions._
import kyo.chatgpt.plugins._
import kyo.chatgpt.util.JsonSchema
import kyo.concurrent.atomics.Atomics
import kyo.concurrent.fibers._
import kyo.ios._
import kyo.locals._
import kyo.options.Options
import kyo.requests._
import kyo.sums._
import kyo.tries._
import sttp.client3._
import sttp.client3.ziojson._
import zio.json._
import zio.schema.DeriveSchema
import zio.schema.Schema
import zio.schema.codec.JsonCodec

import java.lang.ref.WeakReference
import scala.annotation.targetName
import scala.concurrent.duration.Duration
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.NoStackTrace
import scala.annotation.StaticAnnotation

object ais {

  type State = Map[AIRef, Context]

  type AIs >: AIs.Effects <: AIs.Effects

  final case class desc(value: String) extends StaticAnnotation

  object AIs {

    type Effects = Sums[State] with Requests with Tries with IOs with Aspects

    private[kyo] case class Value[T](value: T)

    private val nextId = IOs.run(Atomics.initLong(0))

    val init: AI > AIs = nextId.incrementAndGet.map(AI(_))

    def init[S](seed: String > S): AI > (AIs with S) =
      init.map { ai =>
        ai.seed(seed).andThen(ai)
      }

    def ask[S](msg: String > S): String > (AIs with S) =
      init.map(_.ask(msg))

    inline def gen[T](msg: String): T > AIs =
      init.map(_.gen[T](msg))

    inline def infer[T](msg: String): T > AIs =
      init.map(_.infer[T](msg))

    def ask[S](seed: String > S, msg: String > S): String > (AIs with S) =
      init(seed).map(_.ask(msg))

    inline def gen[T](seed: String, msg: String): T > AIs =
      init(seed).map(_.gen[T](msg))

    inline def infer[T](seed: String, msg: String): T > AIs =
      init(seed).map(_.infer[T](msg))

    def restore[S](ctx: Context > S): AI > (AIs with S) =
      init.map { ai =>
        ai.restore(ctx).map(_ => ai)
      }

    def fail[T, S](cause: String > S): T > (AIs with S) =
      cause.map(cause => Tries.fail(AIException(cause)))

    def ephemeral[T, S](f: => T > S): T > (AIs with S) =
      Sums[State].get.map { st =>
        Tries.run(f).map(r => Sums[State].set(st).map(_ => r.get))
      }

    def run[T, S](v: T > (AIs with S)): T > (Requests with Tries with S) = {
      val a: T > (Requests with Tries with Aspects with S) = Sums[State].run(v)
      val b: T > (Requests with Tries with S)              = Aspects.run(a)
      b
    }
  }

  class AI private[ais] (val id: Long) {

    private val ref = AIRef(this)

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

    def seed[S](msg: String > S): Unit > (AIs with S) =
      msg.map { msg =>
        save.map { ctx =>
          restore(ctx.seed(msg))
        }
      }

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
              restore(ctx.add(role, content, name, call))
            }
          }
        }
      }

    import AIs._

    def ask[S](msg: String > S): String > (AIs with S) = {
      def eval(plugins: Set[Plugin[_, _]]): String > AIs =
        fetch(plugins).map { r =>
          r.call.map { call =>
            plugins.find(_.name == call.function) match {
              case None =>
                function(call.function, "Invalid function call: " + call)
                  .andThen(eval(plugins))
              case Some(plugin) =>
                function(call.function, plugin(this, call.arguments))
                  .andThen(eval(plugins))
            }
          }.getOrElse(r.content)
        }
      user(msg).andThen(Plugins.get.map(eval))
    }

    inline def gen[T](msg: String): T > AIs =
      gen(msg)(DeriveSchema.gen[T])

    private def gen[T](msg: String)(implicit t: Schema[T]): T > AIs = {
      val decoder = JsonCodec.jsonDecoder(t)
      val resultPlugin =
        Plugins.init[T, T](
            "resultPlugin",
            "Call this function with the result. Note how the schema " +
              "is wrapped in an object with a `value` field."
        )((ai, v) => v)
      def eval(): T > AIs =
        fetch(Set(resultPlugin), Some(resultPlugin)).map { r =>
          r.call.map { call =>
            if (call.function != resultPlugin.name) {
              function(call.function, "Invalid function call: " + call)
                .andThen(eval())
            } else {
              resultPlugin.decoder.decodeJson(call.arguments) match {
                case Left(error) =>
                  function(
                      resultPlugin.name,
                      "Failed to read the result: " + error
                  ).andThen(eval())
                case Right(value) =>
                  value.value
              }
            }
          }.getOrElse(AIs.fail("Expected a function call"))
        }
      user(msg).andThen(eval())
    }

    private val inferPrompt = """
    | ==============================IMPORTANT===================================
    | == Note the 'resultPlugin' function I provided. Feel free to call other ==
    | == functions but please invoke 'resultPlugin' as soon as you're done.   ==
    | ==========================================================================
    """.stripMargin

    inline def infer[T](msg: String): T > AIs =
      infer(msg)(DeriveSchema.gen[Value[T]])

    private def infer[T](msg: String)(implicit t: Schema[Value[T]]): T > AIs = {
      val resultPlugin =
        Plugins.init[T, T]("resultPlugin", "call this function with the result", t, t)((ai, v) => v)
      def eval(plugins: Set[Plugin[_, _]], constrain: Option[Plugin[_, _]]): T > AIs =
        fetch(plugins, constrain).map { r =>
          r.call.map { call =>
            plugins.find(_.name == call.function) match {
              case None =>
                function(call.function, "Invalid function call: " + call)
                  .andThen(eval(plugins, constrain))
              case Some(`resultPlugin`) =>
                resultPlugin.decoder.decodeJson(call.arguments) match {
                  case Left(error) =>
                    function(
                        resultPlugin.name,
                        "Failed to read the result: " + error
                    ).andThen(eval(plugins, constrain))
                  case Right(value) =>
                    value.value
                }
              case Some(plugin) =>
                Tries.run[String, AIs](plugin(this, call.arguments)).map {
                  case Success(result) =>
                    function(call.function, result)
                      .andThen(eval(plugins, constrain))
                  case Failure(ex) =>
                    function(call.function, "Failure: " + ex)
                      .andThen(eval(plugins, constrain))
                }
            }
          }.getOrElse(eval(plugins, Some(resultPlugin)))
        }
      user(msg)
        .andThen(function(resultPlugin.name, inferPrompt))
        .andThen(Plugins.get.map(p =>
          eval(p + resultPlugin, None)
        ))
    }

    private def fetch(
        plugins: Set[Plugin[_, _]],
        constrain: Option[Plugin[_, _]] = None
    ): Completions.Result > AIs =
      for {
        ctx <- save
        r   <- Completions(ctx, plugins, constrain)
        _   <- assistant(r.content, r.call)
      } yield r
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
      val init = Map.empty
      def add(x: State, y: State) = {
        val merged = x ++ y.map { case (k, v) => k -> (x.get(k).getOrElse(Contexts.init) ++ v) }
        merged.filter { case (k, v) => k.isValid() && v.messages.nonEmpty }
      }
    }
}
