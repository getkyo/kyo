package kyo.chatgpt

import kyo._
import kyo.chatgpt.ais._
import kyo.chatgpt.util.JsonSchema
import kyo.locals._
import zio.json.JsonEncoder
import zio.schema.DeriveSchema
import zio.schema.codec.JsonCodec
import zio.schema.Schema
import zio.json.JsonDecoder
import AIs.Value

package object plugins {

  class Plugin[T, U] private[plugins] (
      val name: String,
      val description: String,
      val schema: JsonSchema,
      val decoder: JsonDecoder[Value[T]],
      val encoder: JsonEncoder[Value[U]],
      val call: T => U > AIs
  ) {
    def apply(v: String): String > AIs =
      decoder.decodeJson(v) match {
        case Left(error) =>
          AIs.fail("Fail to decode plugin input: " + error)
        case Right(value) =>
          call(value.value).map(v => encoder.encodeJson(Value(v)).toString())
      }
  }

  object Plugins {

    private[plugins] val local = Locals.init(Set.empty[Plugin[_, _]])

    val get: Set[Plugin[_, _]] > AIs = local.get

    def enable[T, S](p: Plugin[_, _]*)(v: => T > S) =
      Plugins.local.get.map { set =>
        Plugins.local.let(set ++ p.toSeq)(v)
      }

    inline def init[T, U](name: String, description: String)(f: T => U > AIs): Plugin[T, U] =
      init(name, description, f, DeriveSchema.gen[Value[T]], DeriveSchema.gen[Value[U]])

    private def init[T, U](
        name: String,
        description: String,
        f: T => U > AIs,
        t: Schema[Value[T]],
        u: Schema[Value[U]]
    ): Plugin[T, U] =
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
