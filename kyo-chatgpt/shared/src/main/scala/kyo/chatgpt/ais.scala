package kyo.chatgpt

import kyo._
import kyo.aspects._
import kyo.chatgpt.completions._
import kyo.chatgpt.contexts._
import kyo.chatgpt.tools._
import kyo.concurrent.atomics._
import kyo.concurrent.fibers._
import kyo.ios._
import kyo.lists._
import kyo.requests._
import kyo.sums._
import kyo.tries._
import zio.schema.codec.JsonCodec

import java.lang.ref.WeakReference
import scala.annotation.StaticAnnotation
import scala.util.Failure
import scala.util.Success
import scala.util.control.NoStackTrace
import kyo.consoles.Consoles

object ais {

  import internal._

  type AIs >: AIs.Effects <: AIs.Effects

  final case class desc(value: String) extends StaticAnnotation

  implicit class PromptInterpolator(val sc: StringContext) extends AnyVal {
    def p(args: Any*): String =
      sc.s(args: _*)
        .replaceAll("\n\\s+", "\n") // remove whitespace at the start of a line
        .trim
  }

  object AIs {

    type Effects = Sums[State] with Requests with Tries with IOs with Aspects

    case class AIException(cause: String) extends Exception(cause) with NoStackTrace

    private val nextId = IOs.run(Atomics.initLong(0))

    val init: AI > AIs =
      nextId.incrementAndGet.map(new AI(_))

    def init(seed: String): AI > AIs =
      init.map { ai =>
        ai.seed(seed).andThen(ai)
      }

    def ask(msg: String): String > AIs =
      init.map(_.ask(msg))

    def gen[T](msg: String)(implicit t: ValueSchema[T]): T > AIs =
      init.map(_.gen[T](msg))

    def infer[T](msg: String)(implicit t: ValueSchema[T]): T > AIs =
      init.map(_.infer[T](msg))

    def ask(seed: String, msg: String): String > AIs =
      init(seed).map(_.ask(msg))

    def gen[T](seed: String, msg: String)(implicit t: ValueSchema[T]): T > AIs =
      init(seed).map(_.gen[T](msg))

    def infer[T](seed: String, msg: String)(implicit t: ValueSchema[T]): T > AIs =
      init(seed).map(_.infer[T](msg))

    def restore(ctx: Context): AI > AIs =
      init.map { ai =>
        ai.restore(ctx).map(_ => ai)
      }

    def fail[T](cause: String): T > AIs =
      Tries.fail(AIException(cause))

    def ephemeral[T, S](f: => T > S): T > (AIs with S) =
      Sums[State].get.map { st =>
        Tries.run[T, S](f).map(r => Sums[State].set(st).map(_ => r.get))
      }

    def run[T, S](v: T > (AIs with S)): T > (Requests with Tries with S) = {
      val a: T > (Requests with Tries with Aspects with S) =
        Sums[State].run[T, Requests with Tries with Aspects with S](v)
      val b: T > (Requests with Tries with S) =
        Aspects.run[T, Requests with Tries with S](a)
      b
    }
  }

  class AI private[ais] (val id: Long) {

    private val ref = new AIRef(this)

    def save: Context > AIs =
      Sums[State].get.map(_.getOrElse(ref, Context.empty))

    def dump: Unit > (AIs with Consoles) =
      save.map(_.dump).map(Consoles.println)

    def restore(ctx: Context): Unit > AIs =
      Sums[State].get.map { st =>
        Sums[State].set(st + (ref -> ctx)).unit
      }

    def update(f: Context => Context): Unit > AIs =
      save.map { ctx =>
        restore(f(ctx))
      }

    def copy: AI > AIs =
      for {
        res <- AIs.init
        st  <- Sums[State].get
        _   <- Sums[State].set(st + (res.ref -> st.getOrElse(ref, Context.empty)))
      } yield res

    def seed[S](msg: String): Unit > AIs =
      update(_.seed(msg))

    def userMessage(msg: String, imageUrls: List[String] = Nil): Unit > AIs =
      update(_.userMessage(msg, imageUrls))

    def systemMessage(msg: String): Unit > AIs =
      update(_.systemMessage(msg))

    def assistantMessage(msg: String, calls: List[Call] = Nil): Unit > AIs =
      update(_.assistantMessage(msg, calls))

    def toolMessage(callId: CallId, msg: String): Unit > AIs =
      update(_.toolMessage(callId, msg))

    def ask(msg: String): String > AIs =
      userMessage(msg).andThen(ask)

    def ask: String > AIs = {
      def eval(tools: Set[Tool[_, _]]): String > AIs =
        fetch(tools).map { r =>
          r.calls match {
            case Nil =>
              r.content
            case calls =>
              Tools.handle(this, tools, calls)
                .andThen(eval(tools))
          }
        }
      Tools.get.map(eval)
    }

    def gen[T](msg: String)(implicit t: ValueSchema[T]): T > AIs =
      userMessage(msg).andThen(gen[T])

    def gen[T](implicit t: ValueSchema[T]): T > AIs = {
      val decoder = JsonCodec.jsonDecoder(t.get)
      val resultTool =
        Tools.init[T, T](
            "resultTool",
            "Call this function with the result. Note how the schema " +
              "is wrapped in an object with a `value` field.",
            _ => s"Generating: ${t}"
        )((ai, v) => v)
      def eval(): T > AIs =
        fetch(Set(resultTool), Some(resultTool)).map { r =>
          r.calls match {
            case call :: Nil if (call.function == resultTool.name) =>
              resultTool.decoder.decodeJson(call.arguments) match {
                case Left(error) =>
                  toolMessage(
                      call.id,
                      "Failed to read the result: " + error
                  ).andThen(eval())
                case Right(value) =>
                  toolMessage(
                      call.id,
                      "Result processed"
                  ).andThen(value.value)
              }
            case calls =>
              AIs.fail("Expected a function call to the resultTool")
          }
        }
      eval()
    }

    def infer[T](msg: String)(implicit t: ValueSchema[T]): T > AIs =
      userMessage(msg).andThen(infer[T])

    def infer[T](implicit t: ValueSchema[T]): T > AIs = {
      val resultTool = Tools.result[T]
      def eval(tools: Set[Tool[_, _]], constrain: Option[Tool[_, _]] = None): T > AIs =
        fetch(tools, constrain).map { r =>
          r.calls match {
            case Nil =>
              eval(tools, Some(resultTool))
            case call :: _ if (call.function == resultTool.name) =>
              resultTool.decoder.decodeJson(call.arguments) match {
                case Left(error) =>
                  toolMessage(
                      call.id,
                      "Failed to read the result: " + error
                  ).andThen(eval(tools, Some(resultTool)))
                case Right(value) =>
                  toolMessage(
                      call.id,
                      "Result processed."
                  ).andThen(value.value)
              }
            case calls =>
              Tools.handle(this, tools, calls)
                .andThen(eval(tools))
          }
        }
      Tools.get.map(p => eval(p + resultTool))
    }

    private def fetch(
        tools: Set[Tool[_, _]],
        constrain: Option[Tool[_, _]] = None
    ): Completions.Result > AIs =
      for {
        ctx <- save
        r   <- Completions(ctx, tools, constrain)
        _   <- assistantMessage(r.content, r.calls)
      } yield r
  }

  object internal {

    type State = Map[AIRef, Context]

    class AIRef(ai: AI) extends WeakReference[AI](ai) {

      private val id = ai.id

      def isValid(): Boolean = get() != null

      override def equals(obj: Any): Boolean =
        obj match {
          case other: AIRef => id == other.id
          case _            => false
        }

      override def hashCode =
        (31 * id.toInt) + 31
    }

    implicit val summer: Summer[State] =
      new Summer[State] {
        val init = Map.empty
        def add(x: State, y: State) = {
          val merged = x ++ y.map { case (k, v) => k -> (x.get(k).getOrElse(Context.empty) ++ v) }
          merged.filter { case (k, v) => k.isValid() && !v.isEmpty }
        }
      }
  }
}
