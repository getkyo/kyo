package kyo

import kyo.Test

class SqlAddressTest extends Test:

    // SqlAddress equality, two identical instances are equal (used as pool key)
    "SqlAddress equality" in {
        val a1 = SqlAddress("postgres", "localhost", 5432, "mydb", "alice")
        val a2 = SqlAddress("postgres", "localhost", 5432, "mydb", "alice")
        assert(a1 == a2)
    }

    "SqlAddress inequality on different host" in {
        val a1 = SqlAddress("postgres", "localhost", 5432, "mydb", "alice")
        val a2 = SqlAddress("postgres", "db.example.com", 5432, "mydb", "alice")
        assert(a1 != a2)
    }

    "SqlAddress inequality on different port" in {
        val a1 = SqlAddress("postgres", "localhost", 5432, "mydb", "alice")
        val a2 = SqlAddress("postgres", "localhost", 3306, "mydb", "alice")
        assert(a1 != a2)
    }

    "SqlAddress usable as Map key" in {
        val a = SqlAddress("postgres", "localhost", 5432, "mydb", "alice")
        val m = Map(a -> 42)
        assert(m(SqlAddress("postgres", "localhost", 5432, "mydb", "alice")) == 42)
    }

    "Render[SqlAddress] renders SqlAddress to driver://user@host:port/db form" in {
        val addr     = SqlAddress("postgres", "localhost", 5432, "mydb", "admin")
        val rendered = Render[SqlAddress].asString(addr)
        assert(rendered == "postgres://admin@localhost:5432/mydb")
    }

end SqlAddressTest
