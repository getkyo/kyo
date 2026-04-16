package kyo

// Test data types for Convert tests — at package level for typeCheckFailure references
case class BMUserFull(id: Int, name: String, email: String, password: String) derives CanEqual
case class BMUserDTO(name: String, email: String) derives CanEqual
case class BMPoint2D(x: Int, y: Int) derives CanEqual
case class BMCoords(x: Int, y: Int) derives CanEqual
case class BMPoint3D(x: Int, y: Int, z: Int) derives CanEqual

class ConvertTest extends Test:

    // 1. Manual Convert with explicit function
    "manual convert with explicit function" in {
        val convert = Convert[BMUserFull, BMUserDTO]((u: BMUserFull) => BMUserDTO(u.name, u.email))
        val user    = BMUserFull(42, "Alice", "alice@test.com", "secret")
        assert(convert(user) == BMUserDTO("Alice", "alice@test.com"))
    }

    // 2. Forward direction is total (never fails)
    "forward direction is total" in {
        val convert = Convert[BMUserFull, BMUserDTO]((u: BMUserFull) => BMUserDTO(u.name, u.email))
        // Forward always succeeds
        val user = BMUserFull(1, "Alice", "a@b.com", "pw")
        assert(convert(user) == BMUserDTO("Alice", "a@b.com"))
    }

    // 3. Auto derives when structurally compatible (one-directional)
    "auto derives when structurally compatible" in {
        val convert = Convert[BMPoint2D, BMCoords]
        val p       = BMPoint2D(3, 4)
        assert(convert(p) == BMCoords(3, 4))
    }

    // 4. andThen composes Convert[A, B] + Convert[B, C] into Convert[A, C]
    "andThen composes two converts" in {
        val ab = Convert[BMPoint2D, BMCoords](p => BMCoords(p.x, p.y))
        case class Scaled(x: Int, y: Int, factor: Int) derives CanEqual
        val bc = Convert[BMCoords, Scaled](c => Scaled(c.x * 2, c.y * 2, 2))
        val ac = ab.andThen(bc)
        assert(ac(BMPoint2D(3, 4)) == Scaled(6, 8, 2))
    }

    // 5. Auto-derive fails to compile when types are incompatible
    "auto fails to compile for incompatible types" in {
        typeCheckFailure("Convert[kyo.BMPoint2D, kyo.BMPoint3D]")(
            "no corresponding field"
        )
    }

    // 6. Auto-derive works one-directionally (A superset of B)
    "auto derives when A is superset of B (one-directional)" in {
        val convert = Convert[BMUserFull, BMUserDTO]
        val user    = BMUserFull(42, "Alice", "alice@test.com", "secret")
        assert(convert(user) == BMUserDTO("Alice", "alice@test.com"))
    }

    // 7. Forward after structural transform produces correct target
    "forward after structural transform" in {
        val fwd     = Schema[BMUserFull].drop("id").drop("password").convert[BMUserDTO]
        val convert = Convert[BMUserFull, BMUserDTO](fwd(_))
        val user    = BMUserFull(99, "Charlie", "charlie@test.com", "hunter2")
        assert(convert(user) == BMUserDTO("Charlie", "charlie@test.com"))
    }

    // 8. Implicit conversion via scala.Conversion — given Convert[A, B] enables A where B expected
    "implicit conversion via Conversion" in {
        val p: BMPoint2D                   = BMPoint2D(3, 4)
        given Convert[BMPoint2D, BMCoords] = Convert[BMPoint2D, BMCoords](pt => BMCoords(pt.x, pt.y))
        // Use the given as an implicit conversion
        val coords: BMCoords = p
        assert(coords == BMCoords(3, 4))
    }

    // --- Tests merged from SchemaConvertTest ---

    val alice = MTPerson("Alice", 30)
    val bob   = MTPerson("Bob", 25)
    val team1 = MTSmallTeam(alice, 5)

    // === convert (7 tests) ===

    "convert matching types" in {
        val f    = Schema[MTUser].convert[MTPublicUser]
        val user = MTUser("Alice", 30, "alice@test.com", "123-45-6789")
        assert(f(user) == MTPublicUser("Alice", 30))
    }

    "convert with defaults" in {
        val f      = Schema[MTPublicUser].convert[MTWithDefault]
        val source = MTPublicUser("Alice", 30)
        assert(f(source) == MTWithDefault("Alice", 30, true))
    }

    "convert missing field compile error" in {
        typeCheckFailure("Convert[kyo.MTPublicUser, kyo.MTUser]")("no corresponding field")
    }

    "convert type mismatch" in {
        typeCheckFailure("Convert[kyo.BMPoint2D, kyo.BMUserFull]")("no corresponding field")
    }

    "convert reusable" in {
        val f  = Schema[MTUser].convert[MTPublicUser]
        val u1 = MTUser("Alice", 30, "a@t.com", "111-22-3333")
        val u2 = MTUser("Bob", 25, "b@t.com", "444-55-6666")
        assert(f(u1) == MTPublicUser("Alice", 30))
        assert(f(u2) == MTPublicUser("Bob", 25))
    }

    "convert same type" in {
        val convert = Convert[MTPerson, MTPerson]
        assert(convert(alice) == MTPerson("Alice", 30))
    }

    "convert subset extra fields dropped" in {
        val f      = Schema[MTUser].convert[MTPublicUser]
        val source = MTUser("Alice", 30, "alice@test.com", "secret")
        assert(f(source) == MTPublicUser("Alice", 30))
    }

    // === result (3 tests) ===

    "result produces record" in {
        val m                    = Schema[MTPerson]
        val record               = m.toRecord(alice)
        given CanEqual[Any, Any] = CanEqual.derived
        assert(record.dict("name") == "Alice")
        assert(record.dict("age") == 30)
    }

    "result after drop" in {
        val m                    = Schema[MTUser].drop("ssn")
        val user                 = MTUser("Alice", 30, "alice@test.com", "123-45-6789")
        val record               = m.toRecord(user)
        given CanEqual[Any, Any] = CanEqual.derived
        assert(record.dict("name") == "Alice")
        assert(record.dict("age") == 30)
        assert(record.dict("email") == "alice@test.com")
        assert(!record.dict.contains("ssn"))
    }

    "result record fields" in {
        val m                    = Schema[MTSmallTeam]
        val record               = m.toRecord(team1)
        given CanEqual[Any, Any] = CanEqual.derived
        assert(record.dict("lead") == alice)
        assert(record.dict("size") == 5)
    }

    // === result edge cases (after rename/add) (2 tests) ===

    "result after rename" in {
        val m                    = Schema[MTPerson].rename("name", "fullName")
        val record               = m.toRecord(alice)
        given CanEqual[Any, Any] = CanEqual.derived
        assert(record.dict("fullName") == "Alice")
        assert(record.dict("age") == 30)
        assert(!record.dict.contains("name"))
    }

    "result after add" in {
        val m                    = Schema[MTPerson].add("greeting")((p: MTPerson) => s"Hi ${p.name}")
        val record               = m.toRecord(alice)
        given CanEqual[Any, Any] = CanEqual.derived
        assert(record.dict("name") == "Alice")
        assert(record.dict("age") == 30)
        assert(record.dict("greeting") == "Hi Alice")
    }

    // === instance convert (3 tests) ===

    "instance convert matching types" in {
        val m    = Schema[MTUser]
        val f    = m.convert[MTPublicUser]
        val user = MTUser("Alice", 30, "alice@test.com", "123-45-6789")
        assert(f(user) == MTPublicUser("Alice", 30))
    }

    "instance convert reusable" in {
        val m  = Schema[MTUser]
        val f  = m.convert[MTPublicUser]
        val u1 = MTUser("Alice", 30, "a@t.com", "111-22-3333")
        val u2 = MTUser("Bob", 25, "b@t.com", "444-55-6666")
        assert(f(u1) == MTPublicUser("Alice", 30))
        assert(f(u2) == MTPublicUser("Bob", 25))
    }

    "instance convert with defaults" in {
        val m      = Schema[MTPublicUser]
        val f      = m.convert[MTWithDefault]
        val source = MTPublicUser("Alice", 30)
        assert(f(source) == MTWithDefault("Alice", 30, true))
    }

    // === instance result (3 tests) ===

    "result function" in {
        val m                    = Schema[MTPerson]
        val fn                   = m.toRecord
        val record               = fn(alice)
        given CanEqual[Any, Any] = CanEqual.derived
        assert(record.dict("name") == "Alice")
        assert(record.dict("age") == 30)
    }

    "result function reusable" in {
        val m                    = Schema[MTPerson]
        val fn                   = m.toRecord
        val r1                   = fn(alice)
        val r2                   = fn(bob)
        given CanEqual[Any, Any] = CanEqual.derived
        assert(r1.dict("name") == "Alice")
        assert(r2.dict("name") == "Bob")
    }

    "result function fields" in {
        val imTeam1              = MTNamedTeam("Alpha", alice, 5)
        val m                    = Schema[MTNamedTeam]
        val fn                   = m.toRecord
        val record               = fn(imTeam1)
        given CanEqual[Any, Any] = CanEqual.derived
        assert(record.dict("name") == "Alpha")
        assert(record.dict("lead") == alice)
        assert(record.dict("size") == 5)
    }

    // === Convert[A, B] (auto-derived, one-directional) ===

    "convert forward direction works" in {
        val convert = Convert[BMPoint2D, BMCoords]
        val p       = BMPoint2D(3, 4)
        assert(convert(p) == BMCoords(3, 4))
    }

    "convert auto-derive one-directional (A superset of B)" in {
        val convert = Convert[BMUserFull, BMUserDTO]
        val user    = BMUserFull(42, "Alice", "alice@test.com", "secret")
        assert(convert(user) == BMUserDTO("Alice", "alice@test.com"))
    }

    // =========================================================================
    // composition
    // =========================================================================

    "composition" - {

        "convert after drop round-trip" in {
            val convert = Schema[BMUserFull].drop("id").drop("password").convert[BMUserDTO]
            val user    = BMUserFull(42, "Alice", "alice@test.com", "secret")
            val result  = convert(user)
            assert(result == BMUserDTO("Alice", "alice@test.com"))
        }

        "convert andThen composes A to B to C" in {
            val ab = Convert[BMPoint2D, BMCoords](p => BMCoords(p.x, p.y))
            case class Shifted(x: Int, y: Int) derives CanEqual
            val bc = Convert[BMCoords, Shifted](c => Shifted(c.x + 10, c.y + 10))
            val ac = ab.andThen(bc)
            assert(ac(BMPoint2D(3, 4)) == Shifted(13, 14))
        }

        "convert after rename uses new field name" in {
            case class RenamedUser(userName: String, email: String) derives CanEqual
            val convert = Schema[BMUserFull].drop("id").drop("password").rename("name", "userName").convert[RenamedUser]
            val user    = BMUserFull(42, "Alice", "alice@test.com", "secret")
            val result  = convert(user)
            assert(result == RenamedUser("Alice", "alice@test.com"))
        }

        "convert auto-derive structural compatibility" in {
            val convert = Convert[BMPoint2D, BMCoords]
            val p       = BMPoint2D(7, 8)
            assert(convert(p) == BMCoords(7, 8))
            // andThen with another
            val ab = convert.andThen(Convert[BMCoords, BMPoint2D](c => BMPoint2D(c.x, c.y)))
            assert(ab(p) == p)
        }

    }

end ConvertTest
