package kyo

import scala.quoted.*

/** Test-only wrapper around [[kyo.internal.SqlStaticMacro]].
  *
  * Exposes the compile-time render pipeline as a value so parity tests can assert that the compile-time renderer (used by `.run` /
  * `.runStatic`) produces byte-identical SQL to the runtime renderer (used by `ast.render(dialect)` and `.runDynamic`). Any divergence would
  * surface as `.run` and `.runDynamic` returning different execution results on the same query.
  *
  * Lives in test sources only: the compile-time SQL emitter is intentionally NOT a user-facing API, and a probe kept next to the parity
  * tests is a coverage tool rather than a shipped surface. Mirrors the same-file inline-def-plus-macro-impl separation pattern that
  * [[SqlLiftHarness]] uses to bridge test callers to a main-source macro.
  */
object SqlStaticProbe:

    /** Renders `q` at compile time for every dialect on the compile classpath and returns the resulting [[Sql.Rendered]].
      *
      * `q` must be a fully-inline expression, `SqlStaticMacro` cannot lift a `val` reference. Callers should write the query directly as the
      * argument.
      */
    inline def render(inline q: Sql.Executable[?]): Sql.Rendered = ${ renderImpl('q) }

    private def renderImpl(q: Expr[Sql.Executable[?]])(using Quotes): Expr[Sql.Rendered] =
        kyo.internal.SqlStaticMacro.impl(q)

end SqlStaticProbe
