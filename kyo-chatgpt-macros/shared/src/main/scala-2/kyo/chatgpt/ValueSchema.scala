package kyo.chatgpt

import zio.schema._
import scala.language.experimental.macros
import scala.reflect.macros.blackbox

trait ValueSchema[T] {
  def get: Schema[ValueSchema.Value[T]]
}

object ValueSchema {

  case class Value[T](value: T)

  implicit def gen[T]: ValueSchema[T] = macro ValueSchemaMacro.gen[T]

}
