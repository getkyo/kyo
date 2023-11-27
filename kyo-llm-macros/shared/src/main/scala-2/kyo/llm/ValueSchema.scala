package kyo.llm

import zio.schema._
import scala.language.experimental.macros
import scala.reflect.macros.blackbox

case class ValueSchema[T](get: Schema[Value[T]])

object ValueSchema {

  implicit def gen[T]: ValueSchema[T] = macro genMacro[T]

  def genMacro[T](c: blackbox.Context)(implicit t: c.WeakTypeTag[T]): c.Tree = {
    import c.universe._
    q"""
      import kyo.llm.Value
      kyo.llm.ValueSchema[$t](zio.schema.DeriveSchema.gen)
    """
  }
}
