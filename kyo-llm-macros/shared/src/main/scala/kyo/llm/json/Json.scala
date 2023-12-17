package kyo.llm.json

import kyo._
import kyo.ios._
import zio.schema.codec.JsonCodec
import zio.schema._
import zio.Chunk

trait Json[T] {
  def schema: JsonSchema
  def encode(v: T): String > IOs
  def decode(s: String): T > IOs
}

object Json extends JsonDerive {

  def schema[T](implicit j: Json[T]): JsonSchema =
    j.schema

  def encode[T](v: T)(implicit j: Json[T]): String > IOs =
    j.encode(v)

  def decode[T](s: String)(implicit j: Json[T]): T > IOs =
    j.decode(s)

  implicit def primitive[T](implicit t: StandardType[T]): Json[T] =
    fromZio(Schema.Primitive(t, Chunk.empty))

  def fromZio[T](z: Schema[T]) =
    new Json[T] {
      lazy val schema: JsonSchema = JsonSchema(z)
      private lazy val decoder    = JsonCodec.jsonDecoder(z)
      private lazy val encoder    = JsonCodec.jsonEncoder(z)

      def encode(v: T): String > IOs =
        IOs(encoder.encodeJson(v).toString)

      def decode(s: String): T > IOs =
        IOs {
          decoder.decodeJson(s) match {
            case Left(fail) => IOs.fail(fail)
            case Right(v)   => v
          }
        }
    }
}
