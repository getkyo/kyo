package kyo.kernel.internal

import kyo.<
import scala.quoted.*

// Diverges from main: main's liftMacro and defaultLift are gone, the lift is the plain implicit in
// Implicits; only the issue-903 guidance for a Unit computation lifted to the wrong row remains.
object LiftMacro:

    def abortCastUnitImpl[S1: Type, S2: Type](v: Expr[Unit < S1])(using quotes: Quotes): Expr[Unit < S2] =
        import quotes.reflect.*
        val source = TypeRepr.of[S1].show
        report.errorAndAbort(
            s"""Cannot lift `Unit < ${source}` to the expected type (`Unit < ?`).
               |This may be due to an effect type mismatch.
               |Consider removing or adjusting the type constraint on the left-hand side.
               |More info : https://github.com/getkyo/kyo/issues/903""".stripMargin
        )
    end abortCastUnitImpl

end LiftMacro
