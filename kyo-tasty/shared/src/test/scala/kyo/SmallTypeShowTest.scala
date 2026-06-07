package kyo

import AllowUnsafe.embrace.danger
import kyo.Tasty.SymbolId

/** Position.show, Pickle.show, Flag.show, Flags.isEmpty, Name.isEmpty.
  */
class SmallTypeShowTest extends kyo.test.Test[Any]:

    // ── Leaf 165: position-show ───────────────────────────────────────────────
    // Given: Position("Foo.scala", 10, 5)
    // When: p.show
    // Then: returns "Foo.scala:10:5"
    "Leaf 165: Position.show returns file:line:column" in {
        val p = Tasty.Position("Foo.scala", 10, 5)
        assert(p.show == "Foo.scala:10:5", s"Expected 'Foo.scala:10:5', got '${p.show}'")
        succeed
    }

    // sourceFile is always a String now; the absence of a position is represented by Symbol.sourcePosition == Absent,
    // not by a sentinel string. The 165b test verified the old '<unknown>' sentinel, which no longer exists.
    "Leaf 165b: Position.show with a custom sourceFile string" in {
        val p = Tasty.Position("synthetic.scala", 3, 1)
        assert(p.show == "synthetic.scala:3:1", s"Expected 'synthetic.scala:3:1', got '${p.show}'")
        succeed
    }

    // ── Leaf 166: pickle-show ─────────────────────────────────────────────────
    // Given: Pickle("uuid-1", Version(0, 0, 0), Chunk(1.toByte, 2.toByte))
    // When: pk.show
    // Then: returns "Pickle(uuid-1 v0.0.0 2B)"
    "Leaf 166: Pickle.show returns Pickle(<uuid> v<version> <n>B)" in {
        val pk = Tasty.Pickle("uuid-1", Tasty.Version(0, 0, 0), Span.from(Array[Byte](1, 2)))
        assert(pk.show == "Pickle(uuid-1 v0.0.0 2B)", s"Expected 'Pickle(uuid-1 v0.0.0 2B)', got '${pk.show}'")
        succeed
    }

    // ── Leaf 167: flag-show-name ──────────────────────────────────────────────
    // Given: Flag.Final
    // When: f.show
    // Then: returns "Final"
    "Leaf 167: Flag.show returns the flag name" in {
        assert(Tasty.Flag.Final.show == "Final", s"Expected 'Final', got '${Tasty.Flag.Final.show}'")
        assert(Tasty.Flag.Sealed.show == "Sealed")
        assert(Tasty.Flag.Abstract.show == "Abstract")
        succeed
    }

    // ── Leaf 168: flags-empty ─────────────────────────────────────────────────
    // Given: Flags.empty and Flags(Flag.Final)
    // When: .isEmpty on each
    // Then: true and false respectively
    "Leaf 168: Flags.isEmpty returns true for empty and false for non-empty" in {
        assert(Tasty.Flags.empty.isEmpty, "Flags.empty.isEmpty must be true")
        assert(!Tasty.Flags(Tasty.Flag.Final).isEmpty, "Flags(Flag.Final).isEmpty must be false")
        succeed
    }

    // ── Leaf 169: name-isEmpty ────────────────────────────────────────────────
    // Given: Name("") and Name("Foo")
    // When: .isEmpty on each
    // Then: true and false respectively
    "Leaf 169: Name.isEmpty returns true for empty and false for non-empty" in {
        val emptyName = Tasty.Name("")
        val fooName   = Tasty.Name("Foo")
        // The isEmpty extension is defined in object Name; imported via implicit scope.
        assert(emptyName.isEmpty, "Name(\"\").isEmpty must be true")
        assert(!fooName.isEmpty, "Name(\"Foo\").isEmpty must be false")
        succeed
    }

end SmallTypeShowTest
