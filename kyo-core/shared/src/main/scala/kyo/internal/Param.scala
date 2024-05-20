package kyo.internal

import scala.collection.mutable.LinkedHashMap
import scala.language.implicitConversions
import scala.quoted.*

case class Param[T](code: String, value: T) derives CanEqual

object Param:

    def show(params: Param[?]*): String =
        val tuples = LinkedHashMap(params.map(p => (p.code, p.value))*)
        pprint.apply(tuples).plainText.replaceFirst("LinkedHashMap", "Params")
    end show

    implicit inline def derive[T](v: => T): Param[T] =
        ${ paramImpl('v) }

    private def paramImpl[T: Type](v: Expr[T])(using Quotes): Expr[Param[T]] =
        import quotes.reflect.*
        val code = Expr(v.asTerm.pos.sourceCode.get)
        '{ Param($code, $v) }
    end paramImpl

end Param
