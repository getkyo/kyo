package kyo.internal.sqlite

import kyo.*
import kyo.SqliteClient
import kyo.db.Backend
import kyo.db.Idiom

/** The SQLite backend's plug point: the scheme it answers to, the dialect it renders in, and how a client is opened for a URL.
  *
  * The one place this differs in shape from the other two backends is [[parseUrl]]. A network backend takes the shared parser, whose
  * delimiters describe an authority; SQLite's coordinate is a path, in which every one of those delimiters is an ordinary character.
  *
  * A public zero-argument constructor with no side effects, because it is constructed at every literal-URL call site while compiling as well
  * as when the program runs.
  */
class SqliteBackendFactory extends Backend:

    val scheme: String = SqliteBackendFactory.scheme

    val aliases: Set[String] = SqliteBackendFactory.aliases

    val dialect: Idiom = SqliteClient.dialect

    override def parseUrl(raw: String)(using Frame): Result[SqlConnectionException, SqlConfig.Url] =
        SqliteUrl.parse(raw)

    def open(url: SqlConfig.Url, config: SqlConfig)(using Frame): SqlClient < (Async & Abort[SqlException]) =
        SqliteClient.openUnscoped(url, config)

end SqliteBackendFactory

object SqliteBackendFactory:

    val scheme: String = "sqlite"

    /** `sqlite3` as well, which is what the CLI and most tooling call it, so a URL copied from either resolves. */
    val aliases: Set[String] = Set("sqlite3")

    def claims(candidate: String): Boolean =
        candidate == scheme || aliases.contains(candidate)

end SqliteBackendFactory
