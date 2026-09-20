package kyo.internal.dolt

import kyo.*
import kyo.DoltServer
import kyo.db.Backend
import kyo.db.Idiom

/** The Dolt backend's plug point: the schemes it answers to, the dialect it renders in, and how a client is opened for a URL.
  *
  * Its fully-qualified name is named by `META-INF/services/kyo.db.Backend` and by the compile-time scheme check, so it keeps a public
  * zero-argument constructor free of side effects: it is constructed while compiling a literal URL as well as when the program runs.
  *
  * No `parseUrl` override. Dolt names a branch inside the database (`app/feature`), and the shared parser already takes everything after
  * the host as the database verbatim, so the branch arrives intact with nothing to undo.
  */
class DoltBackendFactory extends Backend:

    val scheme: String = "dolt"

    /** No aliases. `mysql` is deliberately NOT claimed even though Dolt speaks that protocol: claiming it would make which backend answers
      * a URL saying `mysql` depend on classpath order.
      */
    val aliases: Set[String] = Set.empty

    val dialect: Idiom = DoltDialect

    def open(url: SqlConfig.Url, config: SqlConfig)(using Frame): SqlClient < (Async & Abort[SqlException]) =
        DoltServer.openUnscoped(url, config)

end DoltBackendFactory
