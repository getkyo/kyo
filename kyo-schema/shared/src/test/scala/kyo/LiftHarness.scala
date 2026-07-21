package kyo

import scala.quoted.*

/** Test-only macro harness for `FromExprDerivedTest`. Captures an inline expression at the splice site and applies the derived FromExpr's
  * `unapply` against it. Lives in its own source file so Scala 3 will let the macro be invoked from `FromExprDerivedTest`.
  */
object LiftHarness:

    /** Returns `true` if the derived FromExpr's `unapply` matches the supplied inline expression. */
    inline def matched[A](inline value: A): Boolean = ${ matchedImpl[A]('value) }

    /** Returns `Option[A].toString` from the derived FromExpr applied to the inline expression. */
    inline def repr[A](inline value: A): String = ${ reprImpl[A]('value) }

    private def matchedImpl[A: Type](value: Expr[A])(using Quotes): Expr[Boolean] =
        kyo.internal.FromExprDerived.applyMatchedImpl[A](value)

    private def reprImpl[A: Type](value: Expr[A])(using Quotes): Expr[String] =
        kyo.internal.FromExprDerived.applyReprImpl[A](value)
end LiftHarness
