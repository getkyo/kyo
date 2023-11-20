package kyo.chatgpt

import kyo._
import kyo.chatgpt.ais._
import kyo.chatgpt.util.JsonSchema
import kyo.chatgpt.ValueSchema._
import kyo.locals._
import kyo.tries._
import zio.json.JsonDecoder
import zio.json.JsonEncoder
import zio.schema.DeriveSchema
import zio.schema.Schema
import zio.schema.codec.JsonCodec
import kyo.ios.IOs
import kyo.chatgpt.contexts.Call
import scala.util.{Try, Success, Failure}
import kyo.lists.Lists
import kyo.concurrent.atomics.Atomics

package object tools {

  case class Tool[T, U](
      name: String,
      description: String,
      schema: JsonSchema,
      decoder: JsonDecoder[Value[T]],
      encoder: JsonEncoder[Value[U]],
      call: (AI, T) => U > AIs,
      userStatus: T => String > AIs
  ) {
    private[kyo] def handle(ai: AI, v: String): String > AIs =
      decoder.decodeJson(v) match {
        case Left(error) =>
          AIs.fail(
              "Invalid json input. **Correct any mistakes before retrying**. " + error
          )
        case Right(value) =>
          for {
            l   <- Tools.listeners.get
            msg <- userStatus(value.value)
            _   <- Lists.traverseUnit(l)(_(msg))
            v   <- call(ai, value.value)
          } yield {
            encoder.encodeJson(Value(v)).toString()
          }
      }
  }

  object Tools {

    private[tools] val listeners = Locals.init(List.empty[String => Unit > AIs])
    private[tools] val local     = Locals.init(Set.empty[Tool[_, _]])

    def get: Set[Tool[_, _]] > AIs = local.get

    def enable[T, S](p: Tool[_, _]*)(v: => T > S): T > (AIs with S) =
      local.get.map { set =>
        local.let(set ++ p.toSeq)(v)
      }

    def disable[T, S](f: T > S): T > (AIs with S) =
      local.let(Set.empty)(f)

    def init[T, U](
        name: String,
        description: String,
        userStatus: T => String
    )(f: (AI, T) => U > AIs)(implicit t: ValueSchema[T], u: ValueSchema[U]): Tool[T, U] =
      Tool(
          name,
          description + " **Note how the input and output are wrapped into a `value` field**",
          JsonSchema(t.get),
          JsonCodec.jsonDecoder(t.get),
          JsonCodec.jsonEncoder(u.get),
          f,
          userStatus
      )

    def listen[T, S](listener: String => Unit > AIs)(f: T > S): T > (AIs with S) =
      listeners.get.map { l =>
        listeners.let(listener :: l)(f)
      }

    private[kyo] def resultTool[T](implicit
        t: ValueSchema[T]
    ): (Tool[T, T], Option[T] > AIs) > AIs = {
      Atomics.initRef(Option.empty[T]).map { ref =>
        val tool =
          init[T, T](
              "resultTool",
              "call this function with the result",
              (_: T) => "Generating result."
          ) { (_, v) =>
            ref.set(Some(v)).andThen(v)
          }
        (tool, ref.get)
      }
    }

    private[kyo] def handle(ai: AI, tools: Set[Tool[_, _]], calls: List[Call]): Unit > AIs =
      Lists.traverseUnit(calls) { call =>
        tools.find(_.name == call.function) match {
          case None =>
            ai.toolMessage(call.id, "Tool not found: " + call)
          case Some(tool) =>
            AIs.ephemeral {
              Tools.disable {
                Tries.run[String, AIs] {
                  ai.toolMessage(
                      call.id,
                      p"""
                        Entering the tool execution flow. Further interactions 
                        are automated and indirectly initiated by a human.
                      """
                  ).andThen {
                    tool.handle(ai, call.arguments)
                  }
                }
              }
            }.map {
              case Success(result) =>
                ai.toolMessage(call.id, result)
              case Failure(ex) =>
                ai.toolMessage(call.id, "Failure:" + ex)
            }
        }
      }
  }
}
