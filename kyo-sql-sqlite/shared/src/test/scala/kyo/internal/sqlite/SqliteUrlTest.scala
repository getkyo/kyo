package kyo.internal.sqlite

import kyo.*

/** What a `sqlite://` URL means, and in particular what it does NOT mean.
  *
  * Most of these leaves exist because the character they feed in is a DELIMITER to the shared network parser and an ordinary character in a
  * path. A path is read verbatim, so each of them has to survive rather than split the URL somewhere.
  */
class SqliteUrlTest extends Test:

    private def local(raw: String)(using Frame): SqlConfig.Address.Local =
        SqliteUrl.parse(raw).getOrThrow.address match
            case l: SqlConfig.Address.Local => l
            case other                      => throw new AssertionError(s"expected a local address, got $other")

    "an absolute path keeps its leading slash" in {
        val a = local("sqlite:///var/lib/app.db")
        assert(a.scheme == "sqlite")
        assert(a.path == "/var/lib/app.db")
    }

    "a relative path is read as written" in {
        assert(local("sqlite://data/app.db").path == "data/app.db")
    }

    "the in-memory name survives its colons" in {
        // A colon separates host from port in a network URL. Here it is part of the name.
        assert(local("sqlite://:memory:").path == SqliteUrl.InMemory)
    }

    "an empty path is a temporary database rather than a parse error" in {
        assert(local("sqlite://").path == SqliteUrl.Temporary)
    }

    "a path containing an at-sign is not read as credentials" in {
        // `@` ends the userinfo in a network URL, so this is the case that would silently truncate.
        val a = local("sqlite:///srv/user@host/app.db")
        assert(a.path == "/srv/user@host/app.db")
    }

    "a path containing a colon is not read as a port" in {
        assert(local("sqlite:///C:/data/app.db").path == "/C:/data/app.db")
    }

    "a windows path with backslashes is read verbatim" in {
        assert(local("sqlite://C:\\data\\app.db").path == "C:\\data\\app.db")
    }

    "options are split off the path on the first question mark" in {
        val url = SqliteUrl.parse("sqlite:///var/app.db?connectTimeout=5s").getOrThrow
        assert(url.address.asInstanceOf[SqlConfig.Address.Local].path == "/var/app.db")
        assert(url.options.connectTimeout == Present(5.seconds))
    }

    "a URI filename keeps everything SQLite itself will read" in {
        // SQLite reads `file:` names with their own query string. The driver's split takes the first `?`, so a caller
        // spelling a URI filename gets the name intact and the rest as driver options, which is the documented trade.
        val a = local("sqlite://file:shared.db")
        assert(a.path == "file:shared.db")
    }

    "a local address carries no password" in {
        assert(SqliteUrl.parse("sqlite:///var/app.db").getOrThrow.password.isEmpty)
    }

    "a URL with no scheme separator is refused" in {
        assert(SqliteUrl.parse("sqlite:/var/app.db").isFailure)
        assert(SqliteUrl.parse("/var/app.db").isFailure)
    }

    "the scheme is lowercased so an upper-cased URL resolves the same" in {
        assert(local("SQLITE:///var/app.db").scheme == "sqlite")
    }

    "rendering a local address names the path rather than a host and port" in {
        val rendered = Render.asString(local("sqlite:///var/app.db"): SqlConfig.Address)
        assert(rendered == "sqlite:///var/app.db", s"rendered as '$rendered'")
    }

end SqliteUrlTest
