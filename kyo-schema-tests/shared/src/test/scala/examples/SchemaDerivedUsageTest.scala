package examples

import kyo.*

class SchemaDerivedUsageTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    "record derivation works outside the library package" in {
        type User = Record["name" ~ String & "age" ~ Int]
        val value: User = "name" ~ "Ada" & "age" ~ 42
        val wire        = Json.encode(value)
        assert(wire == """{"name":"Ada","age":42}""")
        assert(Json.decode[User](wire) == Result.succeed(value))
    }

    "generic enum derivation works outside the library package" in {
        val value: Reading[Int] = Reading.Value(42)
        val wire                = Json.encode(value)
        assert(wire == """{"Value":{"value":42}}""")
        assert(Json.decode[Reading[Int]](wire) == Result.succeed(value))
        assert(Json.decode[Reading[String]](wire).isFailure)
    }

    enum Reading[+A] derives CanEqual, Schema:
        case Value(value: A)
        case Missing

end SchemaDerivedUsageTest
