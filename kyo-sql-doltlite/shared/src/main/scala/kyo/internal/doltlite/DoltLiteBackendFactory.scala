package kyo.internal.doltlite

import kyo.*
import kyo.DoltLite
import kyo.db.Backend
import kyo.db.Idiom

/** The DoltLite backend's plug point: the scheme it answers to, the dialect it renders in, and how a client is opened.
  *
  * Its fully-qualified name is part of the artifact's binary contract, named by `META-INF/services/kyo.db.Backend` and
  * by the compile-time scheme check, so it must keep a public zero-argument constructor free of side effects.
  */
class DoltLiteBackendFactory extends Backend:

    val scheme: String = "doltlite"

    /** `sqlite` is deliberately NOT claimed even though this engine forks it: claiming it would make which backend a
      * `sqlite://` URL reaches depend on classpath order.
      */
    val aliases: Set[String] = Set.empty

    val dialect: Idiom = DoltLiteDialect

    override def parseUrl(raw: String)(using Frame): Result[SqlConnectionException, SqlConfig.Url] =
        DoltLiteUrl.parse(raw)

    def open(url: SqlConfig.Url, config: SqlConfig)(using Frame): SqlClient < (Async & Abort[SqlException]) =
        DoltLite.openUnscoped(url, config)

end DoltLiteBackendFactory
