package kyo.internal

import kyo.NamingStrategy
import scala.quoted.*

/** Test-only macro harness for [[NamingStrategyFromExprTest]].
  *
  * Lives in a separate compilation unit from its use site (Scala 3 macro rule). Invokes the `given FromExpr[NamingStrategy]` defined in
  * [[NamingStrategyFromExpr]] and emits the result at macro expansion time so tests can assert the lifted value at runtime.
  */
object NamingStrategyFromExprHarness:

    /** Returns `true` if [[NamingStrategyFromExpr.given FromExpr[NamingStrategy]]] successfully unapplies the supplied inline expression.
      */
    inline def matched(inline value: NamingStrategy): Boolean = ${ matchedImpl('value) }

    /** Returns the `Option[NamingStrategy].toString` from [[NamingStrategyFromExpr.given FromExpr[NamingStrategy]]] applied to the inline
      * expression. Useful to assert the lifted value.
      */
    inline def repr(inline value: NamingStrategy): String = ${ reprImpl('value) }

    private def matchedImpl(value: Expr[NamingStrategy])(using Quotes): Expr[Boolean] =
        import NamingStrategyFromExpr.given
        val result = summon[FromExpr[NamingStrategy]].unapply(value)
        Expr(result.isDefined)
    end matchedImpl

    private def reprImpl(value: Expr[NamingStrategy])(using Quotes): Expr[String] =
        import NamingStrategyFromExpr.given
        val result = summon[FromExpr[NamingStrategy]].unapply(value)
        Expr(result.toString)
    end reprImpl

end NamingStrategyFromExprHarness
