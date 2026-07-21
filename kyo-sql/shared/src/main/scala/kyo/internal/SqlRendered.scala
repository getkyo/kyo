package kyo.internal

import kyo.Chunk
import kyo.SqlSchema.BoundValue

/** Both backend renderings of a single statically-rendered SQL string.
  *
  * Produced by [[SqlStaticMacro]] at compile time and consumed by [[SqlRunMacro]] to feed the client's active backend into the executor.
  * Never surfaced to user code: `.run` / `.runStatic` unpack this internally before dispatching to `client.executeBound*`.
  */
final private[kyo] case class SqlBackendSql(postgres: String, mysql: String):

    /** Returns the SQL string for the given backend.
      *
      * When `b` is a statically-known backend type, the `inline match` collapses at compile time to a direct field access:
      * `forBackend(SqlBackend.Postgres)` becomes `this.postgres` with no runtime branching. The dynamic path (when `b` is a runtime value)
      * does a single `case _:` type check.
      */
    inline def forBackend(inline b: SqlBackend): String = b match
        case _: SqlBackend.Postgres => postgres
        case _: SqlBackend.Mysql    => mysql
end SqlBackendSql

/** A macro-time-rendered statement bundle: dual-backend SQL text plus the runtime bind list.
  *
  * The bind list is identical across backends (params are backend-agnostic); only placeholder syntax (`$N` vs `?`) and identifier quoting
  * (`"…"` vs `` `…` ``) differ. Held per-instance so the runtime executor can pick its side of `sql` at zero cost via
  * `SqlBackendSql.forBackend`.
  */
final private[kyo] case class SqlRendered(sql: SqlBackendSql, params: Chunk[BoundValue[?]])
