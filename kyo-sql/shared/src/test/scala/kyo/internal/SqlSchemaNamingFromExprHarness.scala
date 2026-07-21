package kyo.internal

import kyo.SqlSchema.Naming
import scala.quoted.*

/** Test-only macro harness for [[SqlSchemaNamingFromExprTest]].
  *
  * Lives in a separate compilation unit from its use site (Scala 3 macro rule). Invokes the `given FromExpr[Naming]` defined in
  * [[SqlSchemaNamingFromExpr]] and emits the result at macro expansion time so tests can assert the lifted value at runtime.
  */
object SqlSchemaNamingFromExprHarness:

    /** Returns `true` if [[SqlSchemaNamingFromExpr.given FromExpr[Naming]]] successfully unapplies the supplied inline expression.
      */
    inline def matched(inline value: Naming): Boolean = ${ matchedImpl('value) }

    /** Returns the `Option[Naming].toString` from [[SqlSchemaNamingFromExpr.given FromExpr[Naming]]] applied to the inline
      * expression. Useful to assert the lifted value.
      */
    inline def repr(inline value: Naming): String = ${ reprImpl('value) }

    private def matchedImpl(value: Expr[Naming])(using Quotes): Expr[Boolean] =
        import SqlSchemaNamingFromExpr.given
        val result = summon[FromExpr[Naming]].unapply(value)
        Expr(result.isDefined)
    end matchedImpl

    private def reprImpl(value: Expr[Naming])(using Quotes): Expr[String] =
        import SqlSchemaNamingFromExpr.given
        val result = summon[FromExpr[Naming]].unapply(value)
        Expr(result.toString)
    end reprImpl

end SqlSchemaNamingFromExprHarness
