package kyo.chatgpt

import zio.schema._

trait ValueSchema[T] {
  val get: Schema[ValueSchema.Value[T]]
}

object ValueSchema {

  case class Value[T](value: T)

  inline implicit def gen[T]: ValueSchema[T] =
    new ValueSchema[T] {
      val get = DeriveSchema.gen[Value[T]]
    }
}
