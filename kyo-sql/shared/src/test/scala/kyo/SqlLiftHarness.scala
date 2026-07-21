package kyo

import scala.quoted.*

/** Test-only macro harness for Phase-E / Phase-D absorbed leaves in SqlStaticTest.
  *
  * Mirrors `kyo.LiftHarness` in kyo-schema but lives in kyo-sql test sources, satisfying the Scala 3 rule that a macro definition and its
  * use site must reside in separate compilation units.
  *
  * Delegates directly to `kyo.internal.FromExprDerived.applyMatchedImpl` / `applyReprImpl`, the same entry points used by `LiftHarness`.
  * The separation (kyo-schema defines the macro, kyo-sql defines this thin wrapper) is necessary because kyo-sql test sources cannot access
  * kyo-schema test sources (no `test->test` dependency).
  */
object SqlLiftHarness:

    /** Returns `true` if the derived `FromExpr[A]` successfully unapplies the supplied inline expression. */
    inline def matched[A](inline value: A): Boolean = ${ matchedImpl[A]('value) }

    /** Returns `Option[A].toString` from the derived `FromExpr[A]` applied to the inline expression. */
    inline def repr[A](inline value: A): String = ${ reprImpl[A]('value) }

    /** Lifts the inline expression via its derived `FromExpr[A]` and returns the sorted, comma-joined field names of the first `kyo.Record`
      * in the reconstructed value (`"<none>"` if `unapply` returns `None`). Used to assert the *structure* of a reconstructed
      * `Record`-carrying value.
      */
    inline def recordFieldNames[A](inline value: A): String = ${ recordFieldNamesImpl[A]('value) }

    private def matchedImpl[A: Type](value: Expr[A])(using Quotes): Expr[Boolean] =
        kyo.internal.FromExprDerived.applyMatchedImpl[A](value)

    private def reprImpl[A: Type](value: Expr[A])(using Quotes): Expr[String] =
        kyo.internal.FromExprDerived.applyReprImpl[A](value)

    private def recordFieldNamesImpl[A: Type](value: Expr[A])(using Quotes): Expr[String] =
        kyo.internal.FromExprDerived.applyRecordFieldNamesImpl[A](value)

end SqlLiftHarness
