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

package object plugins {

  case class Plugin[T, U](
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
          AIs.fail("Fail to decode plugin input: " + error)
        case Right(value) =>
          call(ai, value.value).map(v => encoder.encodeJson(Value(v)).toString())
      }
  }

  object Plugins {

    private[plugins] val local = Locals.init(Set.empty[Plugin[_, _]])

    val get: Set[Plugin[_, _]] > AIs = local.get

    def enable[T, S](p: Plugin[_, _]*)(v: => T > S) =
      Plugins.local.get.map { set =>
        Plugins.local.let(set ++ p.toSeq)(v)
      }

    def init[T, U](
        name: String,
        description: String
    )(f: (AI, T) => U > AIs)(implicit t: ValueSchema[T], u: ValueSchema[U]): Plugin[T, U] =
      Plugin(
          name,
          description,
          JsonSchema(t.get),
          JsonCodec.jsonDecoder(t.get),
          JsonCodec.jsonEncoder(u.get),
          f
      )
  }
}
