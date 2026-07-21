package kyo

import kyo.Test

class SqlConfigAddressTest extends Test:

    // SqlConfig.Address equality, two identical instances are equal (used as pool key)
    "SqlConfig.Address equality" in {
        val a1 = SqlConfig.Address("postgres", "localhost", 5432, "mydb", "alice")
        val a2 = SqlConfig.Address("postgres", "localhost", 5432, "mydb", "alice")
        assert(a1 == a2)
    }

    "SqlConfig.Address inequality on different host" in {
        val a1 = SqlConfig.Address("postgres", "localhost", 5432, "mydb", "alice")
        val a2 = SqlConfig.Address("postgres", "db.example.com", 5432, "mydb", "alice")
        assert(a1 != a2)
    }

    "SqlConfig.Address inequality on different port" in {
        val a1 = SqlConfig.Address("postgres", "localhost", 5432, "mydb", "alice")
        val a2 = SqlConfig.Address("postgres", "localhost", 3306, "mydb", "alice")
        assert(a1 != a2)
    }

    "SqlConfig.Address usable as Map key" in {
        val a = SqlConfig.Address("postgres", "localhost", 5432, "mydb", "alice")
        val m = Map(a -> 42)
        assert(m(SqlConfig.Address("postgres", "localhost", 5432, "mydb", "alice")) == 42)
    }

    "Render[SqlConfig.Address] renders SqlConfig.Address to driver://user@host:port/db form" in {
        val addr     = SqlConfig.Address("postgres", "localhost", 5432, "mydb", "admin")
        val rendered = Render[SqlConfig.Address].asString(addr)
        assert(rendered == "postgres://admin@localhost:5432/mydb")
    }

end SqlConfigAddressTest
