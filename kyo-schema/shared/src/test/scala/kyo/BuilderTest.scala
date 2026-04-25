package kyo

class BuilderTest extends Test:

    // === build (12 tests) ===

    "build complete" in {
        val result = Builder[MTPerson].name("Alice").age(30).result
        assert(result == MTPerson("Alice", 30))
    }

    "build any order" in {
        val result = Builder[MTPerson].age(30).name("Alice").result
        assert(result == MTPerson("Alice", 30))
    }

    "build with defaults" in {
        val result = Builder[MTDebugConfig].host("localhost").result
        assert(result == MTDebugConfig("localhost", 8080, false))
    }

    "build missing field compile error" in {
        typeCheckFailure("Builder[kyo.MTPerson].name(\"Alice\").result")("result")
    }

    "build type safety via selectDynamic" in {
        // Using selectDynamic + apply (2-step), the BuilderAt.apply enforces type
        typeCheckFailure("Builder[kyo.MTPerson].name.apply(42)")("Required: String")
    }

    "build override defaults" in {
        val result = Builder[MTDebugConfig].host("localhost").port(9090).result
        assert(result == MTDebugConfig("localhost", 9090, false))
    }

    "build nonexistent field compile error" in {
        typeCheckFailure("Builder[kyo.MTPerson].missing")("not found")
    }

    "build all defaults" in {
        val result = Builder[MTAllDefaults].result
        assert(result == MTAllDefaults(1, "hello", false))
    }

    "build duplicate field" in {
        // Setting same field twice: last value wins
        val result = Builder[MTPerson].name("Alice").age(30).name("Bob").result
        assert(result == MTPerson("Bob", 30))
    }

    "build multiple fields" in {
        val result = Builder[MTThreeField].x(1).y("two").z(true).result
        assert(result == MTThreeField(1, "two", true))
    }

    "build override all defaults" in {
        // Set every defaulted field explicitly, all overrides applied
        val result = Builder[MTAllDefaults].a(42).b("world").c(true).result
        assert(result == MTAllDefaults(42, "world", true))
    }

    "build three fields in all permutations" in {
        // MTThreeField built in every field order produces identical result
        val expected = MTThreeField(1, "two", true)
        assert(Builder[MTThreeField].x(1).y("two").z(true).result == expected)
        assert(Builder[MTThreeField].x(1).z(true).y("two").result == expected)
        assert(Builder[MTThreeField].y("two").x(1).z(true).result == expected)
        assert(Builder[MTThreeField].y("two").z(true).x(1).result == expected)
        assert(Builder[MTThreeField].z(true).x(1).y("two").result == expected)
        assert(Builder[MTThreeField].z(true).y("two").x(1).result == expected)
    }

    // === instance build (3 tests) ===

    "instance build complete" in {
        val result = Builder[MTPerson].name("Alice").age(30).result
        assert(result == MTPerson("Alice", 30))
    }

    "instance build with defaults" in {
        val result = Builder[MTConfig].host("localhost").result
        assert(result == MTConfig("localhost", 8080, false))
    }

    "instance build any order" in {
        val result = Builder[MTPerson].age(30).name("Alice").result
        assert(result == MTPerson("Alice", 30))
    }

    // === result method tests (new: real method, not Dynamic) ===

    "result is a real method with return type Root" in {
        // result should return MTPerson, not Any
        val builder: Builder[MTPerson, Any] = Builder[MTPerson].name("Alice").age(30)
        val r: MTPerson                     = builder.result
        assert(r == MTPerson("Alice", 30))
    }

    "construct function preserved through chaining" in {
        // set multiple fields, result still works correctly
        val b1 = Builder[MTThreeField].x(1)
        val b2 = b1.y("two")
        val b3 = b2.z(true)
        assert(b3.result == MTThreeField(1, "two", true))
    }

    "nested case class" in {
        val inner  = MTAddress("Main St", "Springfield", "12345")
        val result = Builder[MTPersonAddr].name("Alice").age(30).address(inner).result
        assert(result == MTPersonAddr("Alice", 30, inner))
    }

    "result type is Root not Any" in {
        // Verify at compile time that result returns the proper Root type
        val b: Builder[MTPerson, Any] = Builder[MTPerson].name("Alice").age(30)
        val r                         = b.result
        // If result returned Any, this type ascription would fail
        val typed: MTPerson = r
        assert(typed == MTPerson("Alice", 30))
    }

    "result with partial build via stored construct function" in {
        // Verify that construct is properly stored and called with accumulated values
        val b1     = Builder[MTDebugConfig].host("example.com")
        val b2     = b1.port(9999)
        val result = b2.result
        assert(result == MTDebugConfig("example.com", 9999, false))
    }

    "result with no defaults missing required field compile error" in {
        typeCheckFailure("Builder[kyo.MTPerson].name(\"Alice\").result")("result")
    }

    "result with wrong type for field compile error" in {
        // The two-step syntax (selectDynamic + apply) enforces types via BuilderAt.apply(value: Focus)
        typeCheckFailure("Builder[kyo.MTPerson].name.apply(42)")("Required: String")
    }

    "result nonexistent field compile error" in {
        typeCheckFailure("Builder[kyo.MTPerson].foo(\"bar\")")("not found")
    }

    "result with partial required fields compile error" in {
        typeCheckFailure("Builder[kyo.MTThreeField].x(1).y(\"two\").result")("result")
    }

    // =========================================================================
    // composition
    // =========================================================================

    "composition" - {

        "build with all fields in various orders produces same result" in {
            val r1 = Builder[MTThreeField].x(1).y("two").z(true).result
            val r2 = Builder[MTThreeField].z(true).x(1).y("two").result
            val r3 = Builder[MTThreeField].y("two").z(true).x(1).result
            assert(r1 == r2)
            assert(r2 == r3)
            assert(r1 == MTThreeField(1, "two", true))
        }

        "build with missing required field fails to compile" in {
            typeCheckFailure("Builder[kyo.MTThreeField].x(1).y(\"two\").result")("result")
        }

        "build nested type" in {
            val addr   = MTAddress("123 Main", "Portland", "97201")
            val result = Builder[MTPersonAddr].name("Alice").age(30).address(addr).result
            assert(result == MTPersonAddr("Alice", 30, addr))
        }

        "build and encode to JSON" in {
            val person = Builder[MTPerson].name("Alice").age(30).result
            val json   = Schema[MTPerson].encodeString[Json](person)
            assert(json.contains("\"Alice\""))
            assert(json.contains("30"))
        }

    }

end BuilderTest
