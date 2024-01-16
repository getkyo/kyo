package kyo.llm

import kyo._
import kyo.llm.completions._
import kyo.llm.configs._
import kyo.llm.contexts._
import kyo.llm.thoughts._
import kyo.llm.tools._
import kyo.Joins

import zio.schema.codec.JsonCodec
import zio.schema.{Schema => ZSchema}

import java.lang.ref.WeakReference
import scala.util.Failure
import scala.util.Success
import scala.util.control.NoStackTrace
import kyo.llm.listeners.Listeners
import scala.reflect.ClassTag

object ais {

  import internal._

  type AIs >: AIs.Effects <: AIs.Effects

  type desc = kyo.llm.json.desc
  val desc = kyo.llm.json.desc

  type Json[T] = kyo.llm.json.Json[T]
  val Json = kyo.llm.json.Json

  implicit class PromptInterpolator(val sc: StringContext) extends AnyVal {
    def p(args: Any*): String =
      sc.s(args: _*)
        .replaceAll("\n\\s+", "\n") // remove whitespace at the start of a line
        .trim
  }

  class AI private[ais] (val id: Long) {

    private val ref = new AIRef(this)

    def save: Context < AIs =
      State.get.map(_.getOrElse(ref, Context.empty))

    def dump: Unit < AIs =
      save.map(_.dump).map(Consoles.println(_))

    def restore(ctx: Context): Unit < AIs =
      State.update(_ + (ref -> ctx)).unit

    def update(f: Context => Context): Unit < AIs =
      save.map { ctx =>
        restore(f(ctx))
      }

    def copy: AI < AIs =
      for {
        ai <- AIs.init
        _  <- State.update(st => st + (ai.ref -> st.getOrElse(ref, Context.empty)))
      } yield ai

    def seed[S](msg: String): Unit < AIs =
      update(_.seed(msg))

    def seed[S](msg: String, reminder: String): Unit < AIs =
      update(_.seed(msg).reminder(reminder))

    def reminder[S](msg: String): Unit < AIs =
      update(_.reminder(msg))

    def userMessage(msg: String, imageUrls: List[String] = Nil): Unit < AIs =
      update(_.userMessage(msg, imageUrls))

    def systemMessage(msg: String): Unit < AIs =
      update(_.systemMessage(msg))

    def assistantMessage(msg: String, calls: List[Call] = Nil): Unit < AIs =
      update(_.assistantMessage(msg, calls))

    def toolMessage(callId: CallId, msg: String): Unit < AIs =
      update(_.toolMessage(callId, msg))

    def thought[T <: Thought](implicit j: Json[T], t: ClassTag[T]): Unit < AIs =
      update(_.thought(Thoughts.opening[T]))

    def closingThought[T <: Thought](implicit j: Json[T], t: ClassTag[T]): Unit < AIs =
      update(_.thought(Thoughts.closing[T]))

    def gen[T](msg: String)(implicit t: Json[T], f: Flat[T]): T < AIs =
      userMessage(msg).andThen(gen[T])

    def gen[T](implicit t: Json[T], f: Flat[T]): T < AIs =
      infer[T](Nil)

    def infer[T](msg: String)(implicit t: Json[T], f: Flat[T]): T < AIs =
      userMessage(msg).andThen(infer[T])

    def infer[T](implicit t: Json[T], f: Flat[T]): T < AIs =
      save.map { ctx =>
        infer[T](ctx.thoughts)
      }

    private def infer[T](thoughts: List[Thoughts.Info])(implicit t: Json[T], f: Flat[T]): T < AIs =
      Tools.resultTool[T](thoughts).map { case (resultTool, result) =>
        def eval(tools: List[Tool], constrain: Option[Tool] = None): T < AIs =
          fetch(tools, constrain).map { r =>
            r.calls match {
              case Nil =>
                eval(tools, Some(resultTool))
              case calls =>
                Tools.handle(this, tools, calls).andThen {
                  result.map {
                    case None =>
                      Listeners.observe("Processing results") {
                        eval(tools)
                      }
                    case Some(v) =>
                      v
                  }
                }
            }
          }
        Tools.get.map(p => eval(resultTool :: p))
      }

    private def fetch(
        tools: List[Tool],
        constrain: Option[Tool] = None
    ): Completions.Result < AIs =
      save.map { ctx =>
        val patch =
          ctx.seed(internal.seed + "\n\n\n" + ctx.seed.getOrElse(""))
            .reminder(internal.reminder + "\n\n\n" + ctx.reminder.getOrElse(""))
        val call =
          if (constrain.isEmpty && tools.size == 1) {
            tools.headOption
          } else {
            constrain
          }
        Completions(patch, tools, call)
          .map { r =>
            assistantMessage(r.content, r.calls).andThen(r)
          }
      }
  }

  object AIs extends Joins[AIs] {

    type Effects = Sums[State] with Fibers

    case class AIException(cause: String) extends Exception(cause) with NoStackTrace

    private val nextId = IOs.run(Atomics.initLong(0))

    val configs = Configs

    val init: AI < AIs =
      nextId.incrementAndGet.map(new AI(_))

    def init(seed: String): AI < AIs =
      init.map { ai =>
        ai.seed(seed).andThen(ai)
      }

    def init(seed: String, reminder: String): AI < AIs =
      init(seed).map { ai =>
        ai.reminder(reminder).andThen(ai)
      }

    def run[T, S](v: T < (AIs with S))(implicit f: Flat[T < AIs with S]): T < (Fibers with S) =
      State.run[T, Fibers with S](v).map(_._1)

    def gen[T](implicit t: Json[T], f: Flat[T]): T < AIs =
      init.map(_.gen[T])

    def gen[T](msg: String)(implicit t: Json[T], f: Flat[T]): T < AIs =
      init.map(_.gen[T](msg))

    def infer[T](msg: String)(implicit t: Json[T], f: Flat[T]): T < AIs =
      init.map(_.infer[T](msg))

    def gen[T](seed: String, msg: String)(
        implicit
        t: Json[T],
        f: Flat[T]
    ): T < AIs =
      init(seed).map(_.gen[T](msg))

    def infer[T](seed: String, msg: String)(
        implicit
        t: Json[T],
        f: Flat[T]
    ): T < AIs =
      init(seed).map(_.infer[T](msg))

    def gen[T](seed: String, reminder: String, msg: String)(
        implicit
        t: Json[T],
        f: Flat[T]
    ): T < AIs =
      init(seed, reminder).map(_.gen[T](msg))

    def infer[T](seed: String, reminder: String, msg: String)(
        implicit
        t: Json[T],
        f: Flat[T]
    ): T < AIs =
      init(seed, reminder).map(_.infer[T](msg))

    def restore(ctx: Context): AI < AIs =
      init.map { ai =>
        ai.restore(ctx).map(_ => ai)
      }

    def fail[T](cause: String): T < AIs =
      IOs.fail(AIException(cause))

    def ephemeral[T, S](f: => T < S)(implicit flat: Flat[T < S]): T < (AIs with S) =
      State.get.map { st =>
        Tries.run[T, S](f).map(r => State.set(st).map(_ => r.get))
      }

    def race[T](l: Seq[T < AIs])(implicit f: Flat[T < AIs]): T < AIs =
      State.get.map { st =>
        Fibers.race[(T, State)](l.map(State.run[T, Fibers](st)))
          .map {
            case (v, st) =>
              State.set(st).map(_ => v)
          }
      }

    def parallel[T](l: Seq[T < AIs])(implicit f: Flat[T < AIs]): Seq[T] < AIs =
      State.get.map { st =>
        Fibers.parallel[(T, State)](l.map(State.run[T, Fibers](st)))
          .map { rl =>
            val r = rl.map(_._1)
            val st =
              rl.map(_._2)
                .foldLeft(Map.empty: State) {
                  case (acc, st) =>
                    summer.add(acc, st)
                }
            State.set(st).map(_ => r)
          }
      }
  }

  object internal {

    type State = Map[AIRef, Context]

    val State = Sums[State]

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

    val seed =
      p"""
        - "Tool" is the nomenclature for the tools/functions available for you to call.
        - The only way to interact with the user is via tool calls.
        - Do not output simple text as replies, always call an tool.
        - The 'toolInput' field is the only information sent to the user.
        - Do not assume you'll have other opportunity to elaborate.
        - The 'toolInput' field must be complete and fully satisfy the user's request.
        - Strictly follow the json schema with **all required fields**.
        - Leverage text name fields as an inner-dialog thought mechanism.
        - Provide all required thought fields and do not create arbitrary ones.
        - The quality of your thoughts will be used by the system administrator to judge your performance.
        - Make sure to use thoughts to perform necessary analysis to guarantee high-quality information.
      """

    val reminder =
      p"""
        - Only interact via function calls
        - 'toolInput' is the only opportunity to return to the user.
        - Strictly follow the json schema with all required fields.
        - Leverage text name fields as inner-dialog.
      """

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
