package kyo.internal

import kyo.Maybe
import scala.quoted.*

private[kyo] object CompileTimeFlag:

    inline def boolean(inline name: String, inline default: Boolean): Boolean = ${ booleanImpl('name, 'default) }

    private def booleanImpl(name: Expr[String], default: Expr[Boolean])(using Quotes): Expr[Boolean] =
        import quotes.reflect.*
        val key = name.valueOrAbort
        Maybe(java.lang.System.getProperty(key)) match
            case Maybe.Present("true")  => Expr(true)
            case Maybe.Present("false") => Expr(false)
            case Maybe.Present(other)   => report.errorAndAbort(s"$key must be true or false, got $other")
            case Maybe.Absent           => Expr(default.valueOrAbort)
        end match
    end booleanImpl
end CompileTimeFlag
