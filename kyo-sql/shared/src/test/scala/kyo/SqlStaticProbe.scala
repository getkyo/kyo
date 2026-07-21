package kyo

import kyo.internal.SqlRendered
import scala.quoted.*

/** Test-only wrapper around [[kyo.internal.SqlStaticMacro]].
  *
  * Exposes the compile-time render pipeline as a value so parity tests can assert that the compile-time renderer (used by
  * `.run` / `.runStatic`) produces byte-identical SQL to the runtime renderer (used by `.renderPostgres` / `.renderMysql` /
  * `.runDynamic`). Any divergence would surface as `.run` and `.runDynamic` returning different execution results on the same query.
  *
  * Lives in test sources only: the compile-time SQL emitter is intentionally NOT a user-facing API (see the module-level plan doc); a
  * probe kept next to the parity tests is a coverage tool, not a shipped surface. Mirrors the same-file inline-def-plus-macro-impl
  * separation pattern that [[SqlLiftHarness]] uses to bridge test callers to a main-source macro.
  */
object SqlStaticProbe:

    /** Renders `q` for both backends at compile time via [[kyo.internal.SqlStaticMacro.impl]] and returns the resulting
      * [[kyo.internal.SqlRendered]] (dual-backend SQL text plus the runtime bind list).
      *
      * `q` must be a fully-inline expression, `SqlStaticMacro` cannot lift a `val` reference. Callers should write the query directly
      * as the argument.
      */
    inline def render(inline q: SqlAst.Executable[?]): SqlRendered = ${ renderImpl('q) }

    private def renderImpl(q: Expr[SqlAst.Executable[?]])(using Quotes): Expr[SqlRendered] =
        kyo.internal.SqlStaticMacro.impl(q)

end SqlStaticProbe
