package kyo

import kyo.SqlAst.*
import kyo.SqlSchema.BoundValue
import kyo.internal.SqlBackend
import scala.quoted.*
// TODO why is this here? Is it user facing/ don't we have apis for static queries elsewhere already?
/** Static-SQL emitter: `SqlStatic.staticSql(q)` → `Rendered(sql, params)` produced as compile-time literals.
  *
  * The macro lifts the full AST value via `FromExpr.derived`, then renders it for both backends with `SqlRender.render`, the single
  * renderer shared with the runtime path, so static and runtime SQL are byte-identical by construction. The bind params are identical
  * across backends because [[BoundValue]] is backend-agnostic; only placeholder syntax (`$N` vs `?`) and identifier quoting (`"…"` vs
  * `` `…` ``) differ.
  */
object SqlStatic:

    /** Both backend renderings of a single statically-rendered SQL string.
      *
      * The macro produces both fields at compile time; the driver picks one at run time via [[forBackend]]. Because both arguments are
      * string literals at the call site, the `inline match` collapses to a single literal access when the backend type is statically
      * known.
      */
    final case class BackendSql(postgres: String, mysql: String) derives CanEqual:

        /** Returns the SQL string for the given backend.
          *
          * When `b` is a statically-known backend type (e.g., the constant `SqlBackend.Postgres` or a `match` selector in the macro
          * expansion), the `inline match` collapses at compile time to a direct field access, `forBackend(SqlBackend.Postgres)` becomes
          * `this.postgres` with no runtime branching cost. The dynamic path (when `b` is a runtime value) does a single `case _:` type
          * check.
          *
          * @param b
          *   the backend to render for; passed inline so the match can fold at compile time when the type is known
          * @return
          *   `postgres` if `b` is a [[SqlBackend.Postgres]], `mysql` if `b` is a [[SqlBackend.Mysql]]
          */
        inline def forBackend(inline b: SqlBackend): String = b match
            case _: SqlBackend.Postgres => postgres
            case _: SqlBackend.Mysql    => mysql
    end BackendSql

    final case class Rendered(sql: BackendSql, params: Chunk[BoundValue[?]])

    inline def staticSql(inline q: SqlAst.Executable[?]): Rendered =
        ${ kyo.internal.SqlStaticMacro.impl('q) }

end SqlStatic
