package kyo.llm

import zio.schema._

case class Value[T](value: T)

case class ValueSchema[T](get: Schema[Value[T]])

object ValueSchema {

  inline implicit def gen[T]: ValueSchema[T] =
    ValueSchema[T](DeriveSchema.gen)
}
