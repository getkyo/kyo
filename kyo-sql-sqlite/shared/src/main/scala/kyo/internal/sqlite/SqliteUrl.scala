package kyo.internal.sqlite

import kyo.*

/** Reads a `sqlite://` URL into the local address the engine opens.
  *
  * Separate from the shared network parser because the two describe different things. A network URL carries an authority, and its parser is
  * built around the delimiters that authority uses: a `@` separating credentials, a `:` separating host from port, a `/` separating host
  * from database. SQLite's coordinate is a path, in which every one of those characters is an ordinary character.
  *
  * So everything between `://` and the query string is the path, read verbatim. That keeps the two shapes from having to agree on anything,
  * and it means a path containing a colon (`:memory:`) or an `@` needs no escaping.
  */
private[sqlite] object SqliteUrl:

    /** SQLite's own spelling for a database held entirely in memory, which is a name rather than a path.
      *
      * Each connection opening this name gets its OWN private database, so two connections to `sqlite://:memory:` do not see each other's
      * tables. A caller wanting one shared in-memory database across connections spells it
      * `sqlite://file:name?mode=memory&cache=shared`, which SQLite reads as a URI filename.
      */
    val InMemory = ":memory:"

    /** An empty path, which SQLite reads as a private temporary database deleted when the connection closes. */
    val Temporary = ""

    def parse(raw: String)(using Frame): Result[SqlConnectionException, SqlConfig.Url] =
        SqlConfig.Url.schemeOf(raw).flatMap { scheme =>
            val rest = raw.substring(raw.indexOf("://") + 3)
            // The query string is split off on the FIRST `?`, matching the network parser. SQLite's own URI filenames
            // also use `?`, which is why a caller wanting one passes it as the path through `file:` and lets SQLite
            // read it: `sqlite://file:x.db?mode=ro` hands SQLite `file:x.db` and keeps `mode=ro` as a driver option.
            val (path, queryString) = rest.indexOf('?') match
                case -1  => (rest, "")
                case idx => (rest.substring(0, idx), rest.substring(idx + 1))
            SqlConfig.Url.Options.parse(queryString).map { options =>
                SqlConfig.Url(SqlConfig.Address.Local(scheme, path), Maybe.empty, options)
            }
        }

end SqliteUrl
