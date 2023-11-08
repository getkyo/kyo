package kyo.chatgpt

import kyo._
import kyo.chatgpt.ais._
import kyo.chatgpt.util.JsonSchema
import kyo.chatgpt.ValueSchema._
import kyo.locals._
import zio.json.JsonDecoder
import zio.json.JsonEncoder
import zio.schema.DeriveSchema
import zio.schema.Schema
import zio.schema.codec.JsonCodec
import kyo.ios.IOs

package object tools {

  case class Tool[T, U](
      name: String,
      description: String,
      schema: JsonSchema,
      decoder: JsonDecoder[Value[T]],
      encoder: JsonEncoder[Value[U]],
      call: (AI, T) => U > AIs
  ) {
    def apply(ai: AI, v: String): String > AIs =
      decoder.decodeJson(v) match {
        case Left(error) =>
          AIs.fail("Fail to decode tool input: " + error)
        case Right(value) =>
          call(ai, value.value).map(v => encoder.encodeJson(Value(v)).toString())
      }
  }

  object Tools {

    private[tools] val local = Locals.init(Set.empty[Tool[_, _]])

    val get: Set[Tool[_, _]] > AIs = local.get

    def enable[T, S](p: Tool[_, _]*)(v: => T > S): T > (IOs with S) =
      local.get.map { set =>
        local.let(set ++ p.toSeq)(v)
      }

    def disabled[T, S](f: T > S): T > (IOs with S) =
      local.let(Set.empty)(f)

    def init[T, U](
        name: String,
        description: String
    )(f: (AI, T) => U > AIs)(implicit t: ValueSchema[T], u: ValueSchema[U]): Tool[T, U] =
      Tool(
          name,
          description + " **Note how the input and output are wrapped into a `value` field**",
          JsonSchema(t.get),
          JsonCodec.jsonDecoder(t.get),
          JsonCodec.jsonEncoder(u.get),
          f
      )
  }
}
