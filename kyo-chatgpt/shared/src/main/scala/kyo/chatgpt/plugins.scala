package kyo.chatgpt

import kyo._
import kyo.chatgpt.ais._
import kyo.chatgpt.util.JsonSchema
import kyo.locals._
import zio.json.JsonEncoder
import zio.schema.DeriveSchema
import zio.schema.codec.JsonCodec

object plugins {

  case class Plugin(
      name: String,
      description: String,
      schema: JsonSchema,
      call: String => String > AIs
  ) {
    def enable[T, S](v: T > S): T > (S with AIs) =
      Plugins.local.get.map { set =>
        Plugins.local.let(set + this)(v)
      }
  }

  object Plugins {

    import AIs.Value

    private[plugins] val local = Locals.init(Set.empty[Plugin])

    val get: Set[Plugin] > AIs = local.get

    inline def init[T, U](name: String, description: String)(f: T => U > AIs): Plugin = {
      val t       = DeriveSchema.gen[Value[T]]
      val u       = DeriveSchema.gen[Value[U]]
      val schema  = JsonSchema(t)
      val decoder = JsonCodec.jsonDecoder(t)
      val encoder = JsonCodec.jsonEncoder(u)
      Plugin(
          name,
          description,
          schema,
          input =>
            decoder.decodeJson(input) match {
              case Left(error) =>
                AIs.fail("Fail to decode plugin input: " + error)
              case Right(value) =>
                f(value.value).map(v => encoder.encodeJson(Value(v)).toString())
            }
      )
    }
  }
}
