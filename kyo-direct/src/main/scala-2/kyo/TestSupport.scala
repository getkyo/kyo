package kyo

import scala.language.experimental.macros
import scala.reflect.macros.whitebox.Context
import org.scalamacros.resetallattrs._
import language.higherKinds
import scala.reflect.macros.TypecheckException
import kyo.ios.IOs

object TestSupport {
  def showTree[T](t: T): T = macro TestSupportMacro.showTree
  def showRawTree[T](t: T): T = macro TestSupportMacro.showRawTree
  def forceLift[T](t: T): T = macro TestSupportMacro.forceLift
  def runLiftTest[T, U](expected: T)(body: U): Unit = macro TestSupportMacro.runLiftTest[T]
}

private[kyo] class TestSupportMacro(val c: Context) {
  import c.universe._

  def showTree(t: Tree): Tree = {
    c.warning(c.enclosingPosition, t.toString)
    t
  }
  def showRawTree(t: Tree): Tree = {
    c.warning(c.enclosingPosition, showRaw(t))
    t
  }

  def forceLift(t: Tree): Tree =
    c.resetAllAttrs {
      Trees.Transform(c)(t) {
        case q"$pack.await[$t, $s]($v)" =>
          q"kyo.ios.IOs.run($v.asInstanceOf[$t > IOs])"
      }
    }

  def runLiftTest[T](expected: Tree)(body: Tree): Tree = {
    c.resetAllAttrs {
      val lifted =
        q"kyo.ios.IOs.run(kyo.direct.defer($body).asInstanceOf[Any > IOs])"

      val forceLifted = forceLift(body)

      q"""
        val expected = scala.util.Try($expected)
        assert(expected == ${typecheckToTry(lifted, "lifted")})
        assert(expected == ${typecheckToTry(forceLifted, "force lifted")})
      """
    }
  }

  def typecheckToTry(tree: Tree, name: String): Tree = {
    try {
      val typeCheckedTree = c.typecheck(c.resetAllAttrs(tree))
      c.info(c.enclosingPosition, s"$name: $typeCheckedTree", force = false)
      q"scala.util.Try($typeCheckedTree)"
    } catch {
      case e: TypecheckException =>
        val msg = s"""
          |$name fails typechecking: $e
          |tree: $tree
          |""".stripMargin
        c.info(e.pos.asInstanceOf[Position], msg, force = true)
        q"""scala.util.Failure(new Exception($msg))"""
    }
  }
}
