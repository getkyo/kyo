package kyo

import kyo.Test

class SqlConfigAddressTest extends Test:

    // SqlConfig.Address equality, two identical instances are equal (used as pool key)
    "SqlConfig.Address equality" in {
        val a1 = SqlConfig.Address("postgres", "localhost", 5432, "mydb", Present("alice"))
        val a2 = SqlConfig.Address("postgres", "localhost", 5432, "mydb", Present("alice"))
        assert(a1 == a2)
    }

    "SqlConfig.Address inequality on different host" in {
        val a1 = SqlConfig.Address("postgres", "localhost", 5432, "mydb", Present("alice"))
        val a2 = SqlConfig.Address("postgres", "db.example.com", 5432, "mydb", Present("alice"))
        assert(a1 != a2)
    }

    "SqlConfig.Address inequality on different port" in {
        val a1 = SqlConfig.Address("postgres", "localhost", 5432, "mydb", Present("alice"))
        val a2 = SqlConfig.Address("postgres", "localhost", 3306, "mydb", Present("alice"))
        assert(a1 != a2)
    }

    // The pool keys on the whole address, so a named user and no user must not collide: two endpoints
    // authenticating as different principals are different pools.
    "SqlConfig.Address inequality between an absent user and a declared one" in {
        val named  = SqlConfig.Address("postgres", "localhost", 5432, "mydb", Present("alice"))
        val absent = SqlConfig.Address("postgres", "localhost", 5432, "mydb", Absent)
        assert(named != absent)
    }

    // The distinction the Maybe exists for: no user at all against a declared empty one. Under a plain String
    // both of these are the same value and this leaf cannot be written.
    "SqlConfig.Address inequality between an absent user and a declared empty one" in {
        val absent = SqlConfig.Address("postgres", "localhost", 5432, "mydb", Absent)
        val empty  = SqlConfig.Address("postgres", "localhost", 5432, "mydb", Present(""))
        assert(absent != empty)
    }

    "SqlConfig.Address usable as Map key" in {
        val a = SqlConfig.Address("postgres", "localhost", 5432, "mydb", Present("alice"))
        val m = Map(a -> 42)
        assert(m(SqlConfig.Address("postgres", "localhost", 5432, "mydb", Present("alice"))) == 42)
    }

    "Render[SqlConfig.Address] renders SqlConfig.Address to scheme://user@host:port/db form" in {
        val addr     = SqlConfig.Address("postgres", "localhost", 5432, "mydb", Present("admin"))
        val rendered = Render[SqlConfig.Address].asString(addr)
        assert(rendered == "postgres://admin@localhost:5432/mydb")
    }

    // An absent user drops the whole userinfo component, which is the URL that produced it. The three
    // negative assertions pin the spellings that would each put a string on the page that no URL parses
    // back to: the literal Absent, a bare @, and null.
    "Render[SqlConfig.Address] omits the userinfo entirely for an absent user" in {
        val addr     = SqlConfig.Address("postgres", "localhost", 5432, "mydb", Absent)
        val rendered = Render[SqlConfig.Address].asString(addr)
        assert(rendered == "postgres://localhost:5432/mydb")
        assert(!rendered.contains("Absent"), s"an absent user rendered as the literal Absent: $rendered")
        assert(!rendered.contains("@"), s"an absent user rendered a bare @: $rendered")
        assert(!rendered.contains("null"), s"an absent user rendered as null: $rendered")
    }

    // A declared empty user keeps the @, because that is the spelling that declared it. This is the render
    // that distinguishes it from the leaf above, and the two are identical under a plain String.
    "Render[SqlConfig.Address] keeps the delimiter for a declared empty user" in {
        val addr     = SqlConfig.Address("postgres", "localhost", 5432, "mydb", Present(""))
        val rendered = Render[SqlConfig.Address].asString(addr)
        assert(rendered == "postgres://@localhost:5432/mydb")
    }

    // What makes the render correct rather than merely readable: every spelling it produces parses back to the
    // address it came from, including the two that differ only in whether a user was declared. The renders are
    // taken from the instance rather than written out, so a change to either side has to keep them agreeing.
    "Render[SqlConfig.Address] round-trips through Url.parse for all three user spellings" in {
        val addresses = List(
            SqlConfig.Address("postgres", "localhost", 5432, "mydb", Present("alice")),
            SqlConfig.Address("postgres", "localhost", 5432, "mydb", Present("")),
            SqlConfig.Address("postgres", "localhost", 5432, "mydb", Absent)
        )
        addresses.foreach { addr =>
            val rendered = Render[SqlConfig.Address].asString(addr)
            SqlConfig.Url.parse(rendered) match
                case Result.Success(url) =>
                    assert(url.address == addr, s"'$rendered' parsed back to ${url.address}, expected $addr")
                case other =>
                    fail(s"'$rendered' did not parse: $other")
            end match
        }
    }

end SqlConfigAddressTest
