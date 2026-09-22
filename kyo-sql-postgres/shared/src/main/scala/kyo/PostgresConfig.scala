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
  *   the schemas an unqualified name is looked up in, tried in order, sent as the session's `search_path` at startup.
  *
  *   A schema is a namespace inside the database, so a table's real name is always `schema.table`. A statement that writes just `users`
  *   names no schema, and this is what decides which one it meant. Under `Chunk("app", "public")`:
  *
  *   {{{
  *   SELECT * FROM users         // app.users when that exists, otherwise public.users
  *   SELECT * FROM public.users  // public.users; a qualified name never consults the path
  *   CREATE TABLE audit(...)     // lands in app, the first entry
  *   }}}
  *
  *   A list because shadowing is the point: `app, public` finds a program's own tables first and takes everything it did not override from
  *   the shared schema. Empty, the default, sends nothing and leaves whatever the server decided (`postgresql.conf`, then any
  *   `ALTER DATABASE` or `ALTER ROLE` default), normally `"$user", public`.
  *
  *   Reaches only the statements a renderer never saw. A statement built through `Sql.from` and its siblings carries its own schema in the
  *   SQL and is unaffected, which is the form to prefer: a qualified name cannot be changed by which pooled connection served it.
  *
  *   `$user` is not a schema name. It is PostgreSQL's placeholder for one named after the connecting role, skipped when no such schema
  *   exists, and the only entry sent unquoted. Every other is quoted, so `App` names the schema spelled `App` rather than the one an
  *   unquoted `CREATE SCHEMA App` folded to `app`.
  *
  *   Naming a schema that does not exist does not fail the connection: PostgreSQL accepts the value, and the first statement that cannot
  *   resolve a name reports it. An EMPTY entry does fail it, with [[SqlConnectionInvalidSearchPathException]], because it names no schema
  *   at all and left to the server it is a FATAL identifying neither the setting nor the entry.
  *
  *   Connection state, so it is part of the pool's identity. Sent in the startup packet rather than as a later `SET`, which is what makes
  *   it survive [[kyo.SqlClient.reset]]: `DISCARD ALL` restores a parameter to its startup value.
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
      * as a list of identifiers rather than taken verbatim: an unquoted `My Schema` would be read as two entries, and an unquoted `App`
      * would fold to `app`. Quoting is by the SQL rule, doubling any embedded quote.
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
