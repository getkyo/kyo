package kyo.llm.json

import zio.schema._
import scala.language.experimental.macros
import scala.reflect.macros.blackbox

trait JsonDerive {
  implicit def deriveJson[T]: Json[T] = macro JsonDerive.genMacro[T]
}

object JsonDerive {

  def genMacro[T](c: blackbox.Context)(implicit t: c.WeakTypeTag[T]): c.Tree = {
    import c.universe._
    q"""
      kyo.llm.json.Json.fromZio[$t](zio.schema.DeriveSchema.gen)
    """
  }
}
