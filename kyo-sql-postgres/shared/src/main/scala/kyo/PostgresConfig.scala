package kyo

import kyo.*

/** PostgreSQL-specific settings, attached to a [[SqlConfig]] through [[SqlConfig.extension]].
  *
  * {{{
  * val config = SqlConfig.default.extension(PostgresConfig(typeNames = Set("hstore")))
  * }}}
  *
  * The PostgreSQL backend reads this back with `config.extensionFor[PostgresConfig]` and uses the defaults below when no instance is
  * attached, so a program that needs none of these settings never mentions this type.
  *
  * @param typeNames
  *   names of user-defined PostgreSQL types (`CREATE TYPE` enums, composites, domains) the session must resolve OIDs for. Each name is
  *   looked up in `pg_type` at connection startup; a name absent from the catalog fails pool initialisation with
  *   [[SqlConnectionException]]. Required for every type [[PostgresTypes]] cannot resolve from a fixed OID.
  * @param copyOutCleanupTimeout
  *   maximum time allowed for the uninterruptible `CopyFail` + `ReadyForQuery`-drain cleanup path that runs when a `COPY TO STDOUT` stream
  *   is closed before the server has sent `CopyDone`. If the budget expires the connection is marked corrupted and removed from the pool.
  * @param searchPath
  *   the schemas an unqualified name resolves against, in order, sent as the session's `search_path` at startup. A list because
  *   `search_path` is one, and `app, public` is the ordinary spelling. Empty, the default, sends nothing and leaves whatever the server
  *   decided: `postgresql.conf`, then any `ALTER DATABASE` or `ALTER ROLE` default.
  *
  *   Reaches only statements that go out UNQUALIFIED. A statement naming its own schema carries it in the SQL and is unaffected. The
  *   difference is where each can go wrong: a qualified name cannot be changed by which pooled connection served it, while this is
  *   connection state and therefore part of the pool's identity.
  *
  *   Sent in the startup packet rather than as a later `SET`, which is what makes it survive [[kyo.SqlClient.reset]]: `DISCARD ALL`
  *   restores a parameter to its startup value.
  *
  *   Names are exact. Every entry but `$user` is sent quoted, so `App` names the schema spelled `App` and not the one an unquoted
  *   `CREATE SCHEMA App` folded to `app`.
  *
  *   A schema named here that does not exist does not fail the connection. PostgreSQL accepts the value, and the first statement that
  *   cannot resolve a name reports it. An EMPTY entry does fail it, with [[SqlConnectionInvalidSearchPathException]]: it names no schema
  *   at all, and left to the server it is a FATAL that says neither which setting nor which entry.
  */
final case class PostgresConfig(
    typeNames: Set[String] = Set.empty,
    copyOutCleanupTimeout: Duration = 5.seconds,
    searchPath: Chunk[String] = Chunk.empty
) extends SqlConfig.Extension derives CanEqual:

    def typeNames(names: Set[String]): PostgresConfig      = copy(typeNames = names)
    def copyOutCleanupTimeout(d: Duration): PostgresConfig = copy(copyOutCleanupTimeout = d)
    def searchPath(schemas: Chunk[String]): PostgresConfig = copy(searchPath = schemas)
    def searchPath(schema: String): PostgresConfig         = copy(searchPath = Chunk(schema))

    /** The `search_path` startup value, or [[Absent]] when no schema was named.
      *
      * Comma-separated, which is the only form the parameter takes. A name is quoted when it needs to be, because `search_path` is parsed
      * as a list of identifiers rather than taken verbatim: an unquoted `My Schema` would be read as two entries, and an unquoted `user`
      * collides with the `$user` convention. Quoting is by the SQL rule, doubling any embedded quote.
      *
      * An empty entry refuses here rather than on the wire. Quoted it would be a zero-length delimited identifier, which the server rejects
      * with a FATAL naming neither the setting nor the entry, so the connection would fail with nothing the caller could act on.
      */
    private[kyo] def searchPathValue(using Frame): Maybe[String] < Abort[SqlException] =
        if searchPath.isEmpty then Absent
        else if searchPath.exists(_.isEmpty) then Abort.fail(SqlConnectionInvalidSearchPathException(searchPath))
        else Present(searchPath.map(PostgresConfig.quoteSearchPathEntry).mkString(","))

end PostgresConfig

object PostgresConfig:

    /** Every default: no user-defined types, a five-second COPY cleanup budget, and no `search_path` of the driver's own. */
    val default: PostgresConfig = PostgresConfig()

    /** One `search_path` entry, quoted when the parameter's own parse would otherwise not read it back whole.
      *
      * `$user` is deliberately left alone: it is the convention naming the connecting role's schema, and quoting it would turn the
      * convention into a literal schema named `$user`.
      */
    private def quoteSearchPathEntry(entry: String): String =
        val needsQuoting =
            entry != "$user" && (entry.isEmpty || !entry.forall(c => c.isLower && c.isLetter || c.isDigit || c == '_'))
        if needsQuoting then "\"" + entry.replace("\"", "\"\"") + "\"" else entry
    end quoteSearchPathEntry

    /** The PostgreSQL settings attached to `config`, or [[default]] when none is.
      *
      * Attaching no instance is a declaration that the program needs none of these settings, which is what the defaults express, so the
      * backend reads its settings through here rather than branching on presence.
      */
    private[kyo] def of(config: SqlConfig): PostgresConfig =
        config.extensionFor[PostgresConfig].getOrElse(default)

end PostgresConfig
