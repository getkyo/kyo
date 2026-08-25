package kyo.internal.mysql

import kyo.*
import kyo.db.Backend
import kyo.db.Idiom

/** Registers the MySQL backend, answering the `mysql://` URL scheme.
  *
  * Constructed by name: the registry derivation emits `new MysqlBackendFactory` into every call site that opens a client, so this class's
  * fully-qualified name is part of the artifact's binary contract and its zero-argument constructor must stay public and free of side
  * effects.
  */
/** The schemes this backend claims, declared once so every site that answers "is this URL ours" reads the same set.
  *
  * `aliases` is empty, so the claimed set is `scheme` alone today. Declaring it here once means every site that answers whether a URL is
  * ours, including [[kyo.MysqlClient.parsed]] through [[claims]], reads the same set, so the first alias added here needs no second edit to
  * stay consistent.
  */
object MysqlBackendFactory:

    val scheme: String = "mysql"

    val aliases: Set[String] = Set.empty

    /** True when `candidate` is a scheme this backend speaks, canonical name or alias.
      *
      * Mirrors `kyo.db.Backend.Registry.forScheme`, which selects a factory by the same predicate, so URL acceptance
      * cannot disagree with backend selection.
      */
    private[kyo] def claims(candidate: String): Boolean =
        candidate == scheme || aliases.contains(candidate)

end MysqlBackendFactory

/** The MySQL [[kyo.db.Backend]]: what `META-INF/services/kyo.db.Backend` names and what `MysqlClient.register()` hands to runtime
  * discovery. A public zero-argument constructor with no side effects, per the SPI contract, so both the services scan and the
  * compile-time scheme check can instantiate it freely.
  */
class MysqlBackendFactory extends Backend:

    val scheme: String = MysqlBackendFactory.scheme

    val aliases: Set[String] = MysqlBackendFactory.aliases

    val dialect: Idiom = MysqlDialect

    def open(url: SqlConfig.Url, config: SqlConfig)(using Frame): SqlClient < (Async & Abort[SqlException]) =
        MysqlClient.openUnscoped(url, config)

end MysqlBackendFactory
