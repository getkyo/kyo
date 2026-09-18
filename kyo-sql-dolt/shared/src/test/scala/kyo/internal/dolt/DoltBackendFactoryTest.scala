package kyo.internal.dolt

import kyo.*
import kyo.Test
import kyo.db.Backend

/** Pins the backend's plug point: which schemes it claims, and that it reads a `db/branch` URL without a parser of its own. */
class DoltBackendFactoryTest extends Test:

    private val factory = new DoltBackendFactory

    "the canonical scheme is claimed" in {
        assert(factory.scheme == "dolt")
        assert(factory.claims("dolt"))
    }

    "mysql is NOT claimed, even though this engine speaks that protocol" in {
        assert(factory.aliases.isEmpty)
        assert(!factory.claims("mysql"))
        assert(!factory.claims("postgres"))
        assert(!factory.claims(""))
    }

    "the dialect is this backend's own flavor" in {
        assert(factory.dialect eq DoltDialect)
    }

    "a branch-qualified URL parses with the revision intact" in {
        val url = factory.parseUrl("dolt://user:pw@localhost:3306/app/feature").getOrThrow
        url.address match
            case n: SqlConfig.Address.Network =>
                assert(n.database == "app/feature", n.database)
                assert(n.host == "localhost")
                assert(n.port == 3306)
            case other => fail(s"expected a network address, got $other")
        end match
    }

    "a URL with no branch parses to the database alone" in {
        val url = factory.parseUrl("dolt://user:pw@localhost:3306/app").getOrThrow
        url.address match
            case n: SqlConfig.Address.Network => assert(n.database == "app", n.database)
            case other                        => fail(s"expected a network address, got $other")
        end match
    }

    "a second instance answers identically, since construction carries no state" in {
        val other: Backend = new DoltBackendFactory
        assert(other.scheme == factory.scheme)
        assert(other.aliases == factory.aliases)
        assert(other.dialect eq factory.dialect)
    }

end DoltBackendFactoryTest
