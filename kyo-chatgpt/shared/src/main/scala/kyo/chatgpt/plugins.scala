package kyo.chatgpt

import kyo._
import kyo.chatgpt.ais._
import kyo.chatgpt.util.JsonSchema
import kyo.locals._
import zio.json.JsonDecoder
import zio.json.JsonEncoder
import zio.schema.DeriveSchema
import zio.schema.Schema
import zio.schema.codec.JsonCodec

import AIs.Value

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

    inline def init[T, U](name: String, description: String)(f: (AI, T) => U > AIs): Plugin[T, U] =
      init(name, description, DeriveSchema.gen[Value[T]], DeriveSchema.gen[Value[U]])(f)

    def init[T, U](
        name: String,
        description: String,
        t: Schema[Value[T]],
        u: Schema[Value[U]]
    )(f: (AI, T) => U > AIs): Plugin[T, U] =
      Plugin(
          name,
          description,
          JsonSchema(t),
          JsonCodec.jsonDecoder(t),
          JsonCodec.jsonEncoder(u),
          f
      )
  }
}
