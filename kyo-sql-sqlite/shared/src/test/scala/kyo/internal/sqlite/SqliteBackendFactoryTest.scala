package kyo.internal.sqlite

import kyo.*
import kyo.Test
import kyo.db.Backend

/** Pins the backend's plug point: the schemes it claims, the dialect it renders in, and that its constructor stays usable the way the
  * services file and the compile-time scheme check use it. The claims leaf asserts the AGREEMENT between the declared set and the
  * predicate rather than a fixed string, so it keeps holding when an alias is added and fails if the two are separated.
  */
class SqliteBackendFactoryTest extends Test:

    private val factory = new SqliteBackendFactory

    "the canonical scheme and every alias are claimed" in {
        assert(factory.scheme == "sqlite")
        assert(factory.claims(factory.scheme), "the canonical scheme must be claimed")
        factory.aliases.foreach { alias =>
            assert(factory.claims(alias), s"a declared alias must be claimed, $alias is not")
        }
    }

    "sqlite3 is an alias, because that is what the tooling calls it" in {
        assert(factory.aliases == Set("sqlite3"))
        assert(factory.claims("sqlite3"))
    }

    "another engine's scheme is not claimed" in {
        assert(!factory.claims("postgres"))
        assert(!factory.claims("mysql"))
        assert(!factory.claims(""))
    }

    // Backend.claims is final and compares the candidate exactly, so an upper-cased URL resolves only because the
    // parser has already lowercased the scheme.
    "claims matches candidates exactly, and the parser is what lowercases" in {
        assert(!factory.claims("SQLite"))
        assert(!factory.claims("SQLITE"))
        assert(SqliteUrl.parse("SQLITE:///var/app.db").getOrThrow.address.scheme == "sqlite")
    }

    "the dialect is this backend's own flavor" in {
        assert(factory.dialect eq SqliteDialect)
    }

    "a second instance answers identically, since construction carries no state" in {
        val other: Backend = new SqliteBackendFactory
        assert(other.scheme == factory.scheme)
        assert(other.aliases == factory.aliases)
        assert(other.dialect eq factory.dialect)
    }

    "parseUrl reads a path rather than an authority" in {
        val url = factory.parseUrl("sqlite:///srv/user@host:5432/app.db").getOrThrow
        url.address match
            case l: SqlConfig.Address.Local =>
                assert(l.path == "/srv/user@host:5432/app.db", l.path)
            case other =>
                fail(s"expected a local address, got $other")
        end match
    }

end SqliteBackendFactoryTest
