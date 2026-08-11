package kyo.kernel.internal

import kyo.kernel.<
import scala.quoted.*

object LiftMacro:

    def abortCastUnitMacro[S1: Type, S2: Type](v: Expr[Unit < S1])(using Quotes): Expr[Unit < S2] =
        import quotes.reflect.*
        val source = TypeRepr.of[S1].show
        report.errorAndAbort(
            s"""Cannot lift `Unit < ${source}` to the expected type (`Unit < ?`).
               |This may be due to an effect type mismatch.
               |Consider removing or adjusting the type constraint on the left-hand side.
               |More info : https://github.com/getkyo/kyo/issues/903""".stripMargin
        )
    end abortCastUnitMacro

end LiftMacro
