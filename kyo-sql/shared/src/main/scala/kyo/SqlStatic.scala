package kyo

import kyo.SqlAst.*
import scala.quoted.*

/** Static-SQL emitter: `SqlStatic.staticSql(q)` → `Rendered(sql, params)` produced as compile-time literals.
  *
  * The macro lifts the full AST value via `FromExpr.derived`, then renders it for both backends with `SqlRender.render` — the single
  * renderer shared with the runtime path, so static and runtime SQL are byte-identical by construction. The bind params are identical
  * across backends because [[BoundValue]] is backend-agnostic; only placeholder syntax (`$N` vs `?`) and identifier quoting (`"…"` vs
  * `` `…` ``) differ.
  */
object SqlStatic:

    final case class Rendered(sql: BackendSql, params: Chunk[BoundValue[?]])

    inline def staticSql(inline q: SqlAst.Executable[?]): Rendered =
        ${ kyo.internal.SqlStaticMacro.impl('q) }

end SqlStatic
