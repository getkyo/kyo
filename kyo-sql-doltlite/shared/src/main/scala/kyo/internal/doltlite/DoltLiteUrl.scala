package kyo.internal.doltlite

import kyo.*
import kyo.internal.sqlite.SqliteUrl

/** Reads a `doltlite://` URL, which is a path exactly as a `sqlite://` one is, so [[SqliteUrl]] parses it. A path
  * holding `:memory:`, an `@`, or a Windows drive letter has to survive verbatim, and the shared network parser's
  * delimiters would split all three. The scheme is not checked: the registry routes by scheme before a backend sees the
  * URL.
  */
private[kyo] object DoltLiteUrl:

    /** The spelling for a database held entirely in memory. */
    val InMemory: String = SqliteUrl.InMemory

    def parse(raw: String)(using Frame): Result[SqlConnectionException, SqlConfig.Url] =
        SqliteUrl.parse(raw)

end DoltLiteUrl
