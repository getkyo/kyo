package kyo.internal.postgres

import kyo.*
import kyo.db.Backend
import kyo.db.Idiom

/** Registers the Postgres backend, answering the `postgres://` and `postgresql://` URL schemes.
  *
  * Constructed by name: the registry derivation emits `new PostgresBackendFactory` into every call site that opens a client, so this class's
  * fully-qualified name is part of the artifact's binary contract and its zero-argument constructor must stay public and free of side
  * effects.
  */
/** The schemes this backend claims, declared once so every site that answers "is this URL ours" reads the same set.
  *
  * The companion exists because `scheme` and `aliases` are instance members of [[kyo.db.Backend]], so a caller
  * outside the registry cannot consult them without constructing a factory. `PostgresClient.parsed` needs exactly that
  * answer.
  */
object PostgresBackendFactory:

    val scheme: String = "postgres"

    /** `postgresql` is the scheme JDBC and libpq URLs use, accepted so a URL copied from either works unchanged. */
    val aliases: Set[String] = Set("postgresql")

    /** True when `candidate` is a scheme this backend speaks, canonical name or alias.
      *
      * Mirrors `kyo.db.Backend.Registry.forScheme`, which selects a factory by the same predicate, so URL acceptance
      * cannot disagree with backend selection.
      */
    private[kyo] def claims(candidate: String): Boolean =
        candidate == scheme || aliases.contains(candidate)

end PostgresBackendFactory

/** The PostgreSQL [[kyo.db.Backend]]: what `META-INF/services/kyo.db.Backend` names and what `PostgresClient.register()` hands to
  * runtime discovery. A public zero-argument constructor with no side effects, per the SPI contract, so both the services scan and
  * the compile-time scheme check can instantiate it freely.
  */
class PostgresBackendFactory extends Backend:

    val scheme: String = PostgresBackendFactory.scheme

    val aliases: Set[String] = PostgresBackendFactory.aliases

    val dialect: Idiom = PostgresDialect

    def open(url: SqlConfig.Url, config: SqlConfig)(using Frame): SqlClient < (Async & Abort[SqlException]) =
        PostgresClient.openUnscoped(url, config)

end PostgresBackendFactory
