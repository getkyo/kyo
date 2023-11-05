package kyo.chatgpt

import scala.language.experimental.macros
import scala.reflect.macros.blackbox

object ValueSchemaMacro {

  def gen[T](c: blackbox.Context)(implicit t: c.WeakTypeTag[T]): c.Tree = {
    import c.universe._
    q"""
      new kyo.chatgpt.ValueSchema[$t] {
        val get = zio.schema.DeriveSchema.gen[kyo.chatgpt.ValueSchema.Value[$t]]
      }
    """
  }
}
