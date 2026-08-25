package kyo

import kyo.internal.FromExprTestFixtures.Probe
import scala.quoted.*

/** PRODUCTION-path FromExpr probe for the field-carrying-enum-case lift.
  *
  * Unlike [[LiftHarness]] (which reaches the test-only `buildDirect` twin via `applyMatchedImpl`), this
  * drives the REAL static pipeline: `given = FromExpr.derived` (`derivedImpl` -> `deriveFor` ->
  * `deriveSum`/`deriveProduct`), then applies the emitted `unapply`, exactly as the static-SQL macro's
  * lift does. It is what separates a defect in the derivation itself from one in the test twin, which the
  * twin alone cannot tell apart.
  *
  * Monomorphic in the concrete `Probe`: `FromExpr.derived` is an `inline def` whose derivation splice
  * expands at THIS file's compile, so an abstract type parameter would reflect on an abstract `TypeRepr`
  * and abort. Every `Probe` value shares the one concrete type, so a single monomorphic entry covers all.
  */
object ProbeProdHarness:

    inline def prodMatched(inline value: Probe): Boolean = ${ prodMatchedImpl('value) }
    inline def prodRepr(inline value: Probe): String     = ${ prodReprImpl('value) }

    private def prodMatchedImpl(value: Expr[Probe])(using Quotes): Expr[Boolean] =
        val fe: scala.quoted.FromExpr[Probe] = kyo.FromExpr.derived
        Expr(fe.unapply(value).isDefined)

    private def prodReprImpl(value: Expr[Probe])(using Quotes): Expr[String] =
        val fe: scala.quoted.FromExpr[Probe] = kyo.FromExpr.derived
        Expr(fe.unapply(value).toString)
end ProbeProdHarness
