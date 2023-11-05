package kyo.chatgpt

import zio.schema._
import scala.language.experimental.macros
import scala.reflect.macros.blackbox

case class Value[T](value: T)

case class ValueSchema[T](get: Schema[Value[T]])

object ValueSchema {

  implicit def gen[T]: ValueSchema[T] = macro genMacro[T]

  def genMacro[T](c: blackbox.Context)(implicit t: c.WeakTypeTag[T]): c.Tree = {
    import c.universe._
    val res =
      q"""
      import kyo.chatgpt.Value
      kyo.chatgpt.ValueSchema[$t](zio.schema.DeriveSchema.gen)
      """
    c.info(c.enclosingPosition, res.toString, true)
    res
  }

}
