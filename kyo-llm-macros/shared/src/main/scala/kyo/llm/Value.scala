package kyo.llm

import scala.annotation.StaticAnnotation
import zio.schema._

final case class desc(value: String) extends StaticAnnotation

case class Value[T](
    @desc("Please **generate compact json**.")
    willIGenerateCompactJson: Boolean,
    @desc("Result is wrapped into a `value` field.")
    value: T
)

object Value {
  def apply[T](v: T): Value[T] = new Value(true, v)
}
