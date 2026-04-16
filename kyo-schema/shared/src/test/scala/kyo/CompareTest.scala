package kyo

class CompareTest extends Test:

    val alice = MTPerson("Alice", 30)
    val bob   = MTPerson("Bob", 25)
    val team1 = MTSmallTeam(alice, 5)
    val team2 = MTSmallTeam(bob, 10)

    // === diff (12 tests) ===

    // 1. diff identical objects changed is false
    "diff identical objects changed is false" in {
        assert(Compare(alice, alice).changed == false)
    }

    // 2. diff changed field left/right values
    "diff changed field left/right values" in {
        val d = Compare(alice, bob)
        assert(d.changed(_.name) == true)
        assert(d.left(_.name) == Maybe("Alice"))
        assert(d.right(_.name) == Maybe("Bob"))
    }

    // 3. diff nested field navigation
    "diff nested field navigation" in {
        assert(Compare(team1, team2).changed(_.lead.name) == true)
    }

    // 4. diff overall changed/unchanged
    "diff overall changed/unchanged" in {
        assert(Compare(alice, bob).changed == true)
        assert(Compare(alice, alice).changed == false)
    }

    // 5. diff age field left/right
    "diff age field left/right" in {
        val d = Compare(alice, bob)
        assert(d.left(_.age) == Maybe(30))
        assert(d.right(_.age) == Maybe(25))
    }

    // 6. diff nested left/right values
    "diff nested left/right values" in {
        val d = Compare(team1, team2)
        assert(d.left(_.lead.name) == Maybe("Alice"))
        assert(d.right(_.lead.name) == Maybe("Bob"))
    }

    // 7. diff only one field changed others not
    "diff only one field changed others not" in {
        val alice2 = MTPerson("Alice", 35)
        val d      = Compare(alice, alice2)
        assert(d.changed(_.name) == false)
        assert(d.changed(_.age) == true)
    }

    // 8. diff deeply nested 4 levels
    "diff deeply nested 4 levels" in {
        val addr1 = MTAddress("123 Main", "Springfield", "62704")
        val addr2 = MTAddress("456 Oak", "Shelbyville", "62705")
        val pa1   = MTPersonAddr("Alice", 30, addr1)
        val pa2   = MTPersonAddr("Alice", 30, addr2)
        val team1 = MTTeam("Alpha", pa1, Nil)
        val team2 = MTTeam("Alpha", pa2, Nil)
        val co1   = MTCompany("Acme", team1)
        val co2   = MTCompany("Acme", team2)
        val d     = Compare(co1, co2)
        assert(d.changed(_.hq.lead.address.city) == true)
        assert(d.left(_.hq.lead.address.city) == Maybe("Springfield"))
        assert(d.right(_.hq.lead.address.city) == Maybe("Shelbyville"))
    }

    // 9. diff invalid field compile error
    "diff invalid field compile error" in {
        typeCheckFailure("Compare(kyo.MTPerson(\"a\", 1), kyo.MTPerson(\"b\", 2)).changed(_.missing)")("not found")
    }

    // 10. diff with collection field
    "diff with collection field" in {
        val o1 = MTOrder(1, List(MTItem("a", 1.0)))
        val o2 = MTOrder(1, List(MTItem("b", 2.0)))
        val d  = Compare(o1, o2)
        assert(d.changed(_.items) == true)
        assert(d.changed(_.id) == false)
    }

    // 11. Compare.changes returns list of changed fields
    "Compare.changes returns list of changed fields" in {
        given CanEqual[Any, Any] = CanEqual.derived
        val d                    = Compare(alice, bob)
        val changed              = d.changes
        assert(changed.size == 2)
        assert(changed.exists(c => c._1 == "name" && c._2 == "Alice" && c._3 == "Bob"))
        assert(changed.exists(c => c._1 == "age" && c._2 == 30 && c._3 == 25))
    }

    // 12. Compare.changes on identical objects returns empty
    "Compare.changes on identical objects returns empty" in {
        val d = Compare(alice, alice)
        assert(d.changes.isEmpty)
    }

    // === instance diff (3 tests) ===

    "instance diff changed" in {
        assert(Compare(alice, bob).changed(_.name) == true)
    }

    "instance diff unchanged" in {
        assert(Compare(alice, alice).changed(_.name) == false)
    }

    "instance diff nested" in {
        val imTeam1 = MTNamedTeam("Alpha", alice, 5)
        val imTeam2 = MTNamedTeam("Beta", bob, 10)
        assert(Compare(imTeam1, imTeam2).changed(_.lead.name) == true)
    }

    "diff multiple fields some changed some not" in {
        val before = MTAccount("Alice", "alice@old.com", "free", 100)
        val after  = MTAccount("Alice", "alice@new.com", "pro", 100)
        val d      = Compare(before, after)
        assert(!d.changed(_.name))
        assert(d.changed(_.email))
        assert(d.changed(_.tier))
        assert(!d.changed(_.score))
    }

    // === Compare.left/right return Maybe ===

    "diff left returns Maybe.Present for product field" in {
        val d = Compare(alice, bob)
        assert(d.left(_.name) == Maybe("Alice"))
    }

    "diff right returns Maybe.Present for product field" in {
        val d = Compare(alice, bob)
        assert(d.right(_.age) == Maybe(25))
    }

    "diff left returns Maybe.Absent for non-matching sum variant" in {
        val circle: MTShape = MTCircle(5.0)
        val rect: MTShape   = MTRectangle(3.0, 4.0)
        val d               = Compare(circle, rect)
        // circle has no MTRectangle variant
        assert(d.left(_.MTRectangle) == Maybe.empty)
    }

    "diff right returns Maybe.Absent for non-matching sum variant" in {
        val circle: MTShape = MTCircle(5.0)
        val rect: MTShape   = MTRectangle(3.0, 4.0)
        val d               = Compare(circle, rect)
        // rect has no MTCircle variant
        assert(d.right(_.MTCircle) == Maybe.empty)
    }

    "diff left returns Maybe.Present for matching sum variant" in {
        val circle1: MTShape = MTCircle(5.0)
        val circle2: MTShape = MTCircle(10.0)
        val d                = Compare(circle1, circle2)
        assert(d.left(_.MTCircle) == Maybe(MTCircle(5.0)))
        assert(d.right(_.MTCircle) == Maybe(MTCircle(10.0)))
    }

end CompareTest
