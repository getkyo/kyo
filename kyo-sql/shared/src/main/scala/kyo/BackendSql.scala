package kyo

/** Both backend renderings of a single statically-rendered SQL string.
  *
  * The macro produces both fields at compile time; the driver picks one at run time via [[forBackend]]. Because both arguments are string
  * literals at the call site, the `inline match` collapses to a single literal access when the backend type is statically known.
  *
  * Top-level placement (rather than nested inside [[SqlStatic]]) keeps this peer with [[BoundValue]] and [[SqlBackend]], the drivers in
  * `kyo.internal.postgres` and `kyo.internal.mysql` consume this from `SqlClientBackend.executeBound` and similar without an extra import.
  */
final case class BackendSql(postgres: String, mysql: String) derives CanEqual:
    /** Returns the SQL string for the given backend.
      *
      * When `b` is a statically-known backend type (e.g., the constant `SqlBackend.Postgres` or a `match` selector in the macro expansion),
      * the `inline match` collapses at compile time to a direct field access, `forBackend(SqlBackend.Postgres)` becomes `this.postgres`
      * with no runtime branching cost. The dynamic path (when `b` is a runtime value) does a single `case _:` type check.
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
