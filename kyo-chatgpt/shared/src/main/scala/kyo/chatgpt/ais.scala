package kyo.chatgpt

import kyo._
import kyo.aspects._
import kyo.chatgpt.ValueSchema._
import kyo.chatgpt.completions._
import kyo.chatgpt.configs._
import kyo.chatgpt.contexts._
import kyo.chatgpt.tools._
import kyo.chatgpt.util.JsonSchema
import kyo.concurrent.atomics.Atomics
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

object ais {

  type State = Map[AIRef, Context]

  type AIs >: AIs.Effects <: AIs.Effects

  final case class desc(value: String) extends StaticAnnotation

  object AIs {

    type Effects = Sums[State] with Requests with Tries with IOs with Aspects

    private val nextId = IOs.run(Atomics.initLong(0))

    val init: AI > AIs = nextId.incrementAndGet.map(new AI(_))

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

    val save: Context > AIs = Sums[State].get.map(_.getOrElse(ref, Contexts.init))

    def restore[T, S](ctx: Context > S): Unit > (AIs with S) =
      ctx.map { ctx =>
        Sums[State].get.map { st =>
          Sums[State].set(st + (ref -> ctx)).unit
        }
      }

    val initClone: AI > AIs =
      for {
        res <- AIs.init
        st  <- Sums[State].get
        _   <- Sums[State].set(st + (res.ref -> st.getOrElse(ref, Contexts.init)))
      } yield res

    def addMessage(msg: Message): Unit > AIs =
      save.map { ctx =>
        restore(ctx.add(msg))
      }

    def userMessage(msg: String): Unit > AIs =
      addMessage(Message.UserMessage(msg))

    def systemMessage(msg: String): Unit > AIs =
      addMessage(Message.SystemMessage(msg))

    def assistantMessage(msg: String, toolCalls: List[ToolCall] = Nil): Unit > AIs =
      addMessage(Message.AssistantMessage(msg, toolCalls))

    def toolMessage(callId: String, msg: String): Unit > AIs =
      addMessage(Message.ToolMessage(msg, callId))

    def seed[S](msg: String): Unit > AIs =
      save.map { ctx =>
        restore(ctx.seed(msg))
      }

    import AIs._

    def ask(msg: String): String > AIs = {
      def eval(tools: Set[Tool[_, _]]): String > AIs =
        fetch(tools).map { r =>
          r.toolCalls match {
            case Nil =>
              r.content
            case calls =>
              Lists.traverse(calls) { call =>
                tools.find(_.name == call.function) match {
                  case None =>
                    toolMessage(call.id, "Invalid function call: " + call)
                  case Some(tool) =>
                    Tries.run[String, AIs](tool(this, call.arguments)).map {
                      case Success(result) =>
                        toolMessage(call.id, result)
                      case Failure(ex) =>
                        toolMessage(call.id, "Failure: " + ex)
                    }
                }
              }.map(_ => eval(tools))
          }
        }
      userMessage(msg).andThen(Tools.get.map(eval))
    }

    def gen[T](msg: String)(implicit t: ValueSchema[T]): T > AIs = {
      val decoder = JsonCodec.jsonDecoder(t.get)
      val resultTool =
        Tools.init[T, T](
            "resultTool",
            "Call this function with the result. Note how the schema " +
              "is wrapped in an object with a `value` field."
        )((ai, v) => v)
      def eval(): T > AIs =
        fetch(Set(resultTool), Some(resultTool)).map { r =>
          r.toolCalls match {
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
      userMessage(msg).andThen(eval())
    }

    private val inferPrompt = """
    | ==============================IMPORTANT=================================
    | == Note the 'resultTool' function I provided. Feel free to call other ==
    | == functions but please invoke 'resultTool' as soon as you're done.   ==
    | ========================================================================
    """.stripMargin

    def infer[T](msg: String)(implicit t: ValueSchema[T]): T > AIs = {
      val resultTool =
        Tools.init[T, T]("resultTool", "call this function with the result")((ai, v) => v)
      def eval(tools: Set[Tool[_, _]], constrain: Option[Tool[_, _]] = None): T > AIs =
        fetch(tools, constrain).map { r =>
          def loop(calls: List[ToolCall]): T > AIs =
            calls match {
              case Nil =>
                eval(tools)
              case call :: tail =>
                tools.find(_.name == call.function) match {
                  case None =>
                    toolMessage(call.id, "Invalid function call: " + call)
                      .andThen(loop(tail))
                  case Some(`resultTool`) =>
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
                  case Some(tool) =>
                    Tries.run[String, AIs](tool(this, call.arguments)).map {
                      case Success(result) =>
                        toolMessage(call.id, result)
                          .andThen(loop(tail))
                      case Failure(ex) =>
                        toolMessage(call.id, "Failure: " + ex)
                          .andThen(loop(tail))
                    }
                }
            }
          r.toolCalls match {
            case Nil =>
              eval(tools, Some(resultTool))
            case calls =>
              loop(calls)
          }
        }
      userMessage(msg)
        .andThen(toolMessage("", inferPrompt))
        .andThen(Tools.get.map(p =>
          eval(p + resultTool)
        ))
    }

    private def fetch(
        tools: Set[Tool[_, _]],
        constrain: Option[Tool[_, _]] = None
    ): Completions.Result > AIs =
      for {
        ctx <- save
        r   <- Completions(ctx, tools, constrain)
        _   <- assistantMessage(r.content, r.toolCalls)
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
