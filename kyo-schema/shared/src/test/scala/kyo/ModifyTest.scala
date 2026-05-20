package kyo

class ModifyTest extends Test:

    val alice = MTPerson("Alice", 30)
    val bob   = MTPerson("Bob", 25)
    val team1 = MTSmallTeam(alice, 5)

    // === patch (12 tests) ===

    // 1. patch set single field
    "patch set single field" in {
        val result = Modify[MTPerson].set(_.name)("Bob").applyTo(alice)
        assert(result == MTPerson("Bob", 30))
    }

    // 2. patch update single field
    "patch update single field" in {
        val result = Modify[MTPerson].update(_.age)(_ + 1).applyTo(alice)
        assert(result == MTPerson("Alice", 31))
    }

    // 3. patch multiple fields
    "patch multiple fields" in {
        val result = Modify[MTPerson].set(_.name)("Bob").set(_.age)(25).applyTo(alice)
        assert(result == MTPerson("Bob", 25))
    }

    // 4. patch nested field
    "patch nested field" in {
        val result = Modify[MTSmallTeam].set(_.lead.name)("Bob").applyTo(team1)
        assert(result == MTSmallTeam(MTPerson("Bob", 30), 5))
    }

    // 5. patch nested update
    "patch nested update" in {
        val result = Modify[MTSmallTeam].update(_.lead.age)(_ + 1).applyTo(team1)
        assert(result == MTSmallTeam(MTPerson("Alice", 31), 5))
    }

    // 6. patch multiple nested and top-level
    "patch multiple nested and top-level" in {
        val result = Modify[MTSmallTeam].set(_.lead.name)("Bob").set(_.size)(10).applyTo(team1)
        assert(result == MTSmallTeam(MTPerson("Bob", 30), 10))
    }

    // 7. patch update then set same field
    "patch update then set same field" in {
        val result = Modify[MTPerson].update(_.age)(_ + 10).set(_.age)(25).applyTo(alice)
        assert(result == MTPerson("Alice", 25))
    }

    // 8. patch empty identity
    "patch empty identity" in {
        val result = Modify[MTPerson].applyTo(alice)
        assert(result == alice)
    }

    // 9. patch set idempotent
    "patch set idempotent" in {
        val result = Modify[MTPerson].set(_.name)("Bob").set(_.name)("Carol").applyTo(alice)
        assert(result == MTPerson("Carol", 30))
    }

    // 10. patch field to same value
    "patch field to same value" in {
        val result = Modify[MTPerson].set(_.name)("Alice").applyTo(alice)
        assert(result == alice)
    }

    // 11. patch deeply nested 3 levels
    "patch deeply nested 3 levels" in {
        val addr   = MTAddress("123 Main", "Springfield", "62704")
        val pa     = MTPersonAddr("Alice", 30, addr)
        val result = Modify[MTPersonAddr].set(_.address.city)("Shelbyville").applyTo(pa)
        assert(result == MTPersonAddr("Alice", 30, MTAddress("123 Main", "Shelbyville", "62704")))
    }

    // 12. patch compile error nonexistent field
    "patch compile error nonexistent field" in {
        typeCheckFailure("Modify[kyo.MTPerson].set(_.nonexistent)(\"x\")")("not found")
    }

    // === instance patch (3 tests) ===

    "instance patch set" in {
        val result = Modify[MTPerson].set(_.name)("Bob").applyTo(alice)
        assert(result == MTPerson("Bob", 30))
    }

    "instance patch update" in {
        val result = Modify[MTPerson].update(_.age)(_ + 1).applyTo(alice)
        assert(result == MTPerson("Alice", 31))
    }

    "instance patch nested" in {
        val imTeam1 = MTNamedTeam("Alpha", alice, 5)
        val result  = Modify[MTNamedTeam].set(_.lead.name)("Bob").applyTo(imTeam1)
        assert(result == MTNamedTeam("Alpha", MTPerson("Bob", 30), 5))
    }

    "patch applies targeted overrides leaves others unchanged" in {
        val base   = MTAccount("Test", "test@test.com", "free", 0)
        val result = Modify[MTAccount].set(_.tier)("enterprise").set(_.score)(999).applyTo(base)
        assert(result == MTAccount("Test", "test@test.com", "enterprise", 999))
    }

    "patch from base creates test fixture" in {
        val base    = MTAccount("Test", "test@test.com", "free", 0)
        val fixture = Modify[MTAccount].set(_.tier)("enterprise").set(_.score)(999).applyTo(base)
        assert(fixture.name == "Test")
        assert(fixture.email == "test@test.com")
        assert(fixture.tier == "enterprise")
        assert(fixture.score == 999)
    }

end ModifyTest
