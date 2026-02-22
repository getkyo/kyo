package kyo

import Record2.*

class FieldTest extends Test:

    "apply with ConstValue and Tag" in {
        val f = Field["name", String]
        assert(f.name == "name")
    }

    "get from record" in {
        val r = ("name" ~ "Alice") & ("age" ~ 30)
        val f = Field["name", String]
        assert(f.get(r) == "Alice")
    }

    "set on record" in {
        val r  = ("name" ~ "Alice") & ("age" ~ 30)
        val f  = Field["name", String]
        val r2 = f.set(r, "Bob")
        assert(r2.name == "Bob")
        assert(r2.age == 30)
    }

    "nested field descriptor" in {
        val nested = List(Field["x", Int], Field["y", Int])
        val f      = Field[String, Any]("point", Tag[Any], nested)
        assert(f.nested.size == 2)
        assert(f.nested.map(_.name).toSet == Set("x", "y"))
    }

end FieldTest
