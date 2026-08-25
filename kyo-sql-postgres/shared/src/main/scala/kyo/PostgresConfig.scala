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
  */
final case class PostgresConfig(
    typeNames: Set[String] = Set.empty,
    copyOutCleanupTimeout: Duration = 5.seconds
) extends SqlConfig.Extension derives CanEqual:

    def typeNames(names: Set[String]): PostgresConfig      = copy(typeNames = names)
    def copyOutCleanupTimeout(d: Duration): PostgresConfig = copy(copyOutCleanupTimeout = d)

end PostgresConfig

object PostgresConfig:

    /** Every default: no user-defined types, a five-second COPY cleanup budget. */
    val default: PostgresConfig = PostgresConfig()

    /** The PostgreSQL settings attached to `config`, or [[default]] when none is.
      *
      * Attaching no instance is a declaration that the program needs none of these settings, which is what the defaults express, so the
      * backend reads its settings through here rather than branching on presence.
      */
    private[kyo] def of(config: SqlConfig): PostgresConfig =
        config.extensionFor[PostgresConfig].getOrElse(default)

end PostgresConfig
