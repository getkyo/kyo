package kyo.internal.sqlite

import kyo.*
import kyo.SqliteClient
import kyo.db.Backend
import kyo.db.Idiom

/** The SQLite backend's plug point: the schemes it answers to, the dialect it renders in, and how a client is opened for a URL.
  *
  * Its fully-qualified name is part of the artifact's binary contract, named by `META-INF/services/kyo.db.Backend` and by the compile-time
  * scheme check, so the public zero-argument constructor must stay free of side effects: it is constructed while compiling a literal URL as
  * well as when the program runs. [[parseUrl]] takes SQLite's own parser rather than the shared one, whose delimiters describe an authority
  * where SQLite's coordinate is a path in which every one of them is an ordinary character.
  */
class SqliteBackendFactory extends Backend:

    val scheme: String = "sqlite"

    /** `sqlite3` is what the CLI and most tooling call it, so a URL copied from either resolves. */
    val aliases: Set[String] = Set("sqlite3")

    val dialect: Idiom = SqliteDialect

    override def parseUrl(raw: String)(using Frame): Result[SqlConnectionException, SqlConfig.Url] =
        SqliteUrl.parse(raw)

    def open(url: SqlConfig.Url, config: SqlConfig)(using Frame): SqlClient < (Async & Abort[SqlException] & Scope) =
        SqliteClient.opened(url, config)

end SqliteBackendFactory
