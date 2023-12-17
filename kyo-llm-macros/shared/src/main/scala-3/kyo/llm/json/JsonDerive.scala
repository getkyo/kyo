package kyo.llm.json

import kyo._
import kyo.ios._
import zio.schema.codec.JsonCodec
import scala.compiletime.constValue
import zio.schema._
import zio.Chunk

trait JsonDerive {
  inline implicit def deriveJson[T]: Json[T] =
    Json.fromZio(DeriveSchema.gen)
}
