package kyo

import AllowUnsafe.embrace.danger
import kyo.Tasty.SymbolId

/** Position.show, Pickle.show, Flag.show, Flags.isEmpty, Name.isEmpty.
  */
class SmallTypeShowTest extends kyo.test.Test[Any]:

    "Position.show returns file:line:column" in {
        val p = Tasty.Position("Foo.scala", 10, 5)
        assert(p.show == "Foo.scala:10:5", s"Expected 'Foo.scala:10:5', got '${p.show}'")
        succeed
    }

    // sourceFile is always a String now; the absence of a position is represented by Symbol.sourcePosition == Absent,
    // not by a sentinel string. The 165b test verified the old '<unknown>' sentinel, which no longer exists.
    "Position.show with a custom sourceFile string" in {
        val p = Tasty.Position("synthetic.scala", 3, 1)
        assert(p.show == "synthetic.scala:3:1", s"Expected 'synthetic.scala:3:1', got '${p.show}'")
        succeed
    }

    "Pickle.show returns Pickle(<uuid> v<version> <n>B)" in {
        val pk = Tasty.Pickle("uuid-1", Tasty.Version(0, 0, 0), Span.from(Array[Byte](1, 2)))
        assert(pk.show == "Pickle(uuid-1 v0.0.0 2B)", s"Expected 'Pickle(uuid-1 v0.0.0 2B)', got '${pk.show}'")
        succeed
    }

    "Flag.show returns the flag name" in {
        assert(Tasty.Flag.Final.show == "Final", s"Expected 'Final', got '${Tasty.Flag.Final.show}'")
        assert(Tasty.Flag.Sealed.show == "Sealed")
        assert(Tasty.Flag.Abstract.show == "Abstract")
        succeed
    }

    "Flags.isEmpty returns true for empty and false for non-empty" in {
        assert(Tasty.Flags.empty.isEmpty, "Flags.empty.isEmpty must be true")
        assert(!Tasty.Flags(Tasty.Flag.Final).isEmpty, "Flags(Flag.Final).isEmpty must be false")
        succeed
    }

    "Name.isEmpty returns true for empty and false for non-empty" in {
        val emptyName = Tasty.Name("")
        val fooName   = Tasty.Name("Foo")
        // The isEmpty extension is defined in object Name; imported via implicit scope.
        assert(emptyName.isEmpty, "Name(\"\").isEmpty must be true")
        assert(!fooName.isEmpty, "Name(\"Foo\").isEmpty must be false")
        succeed
    }

end SmallTypeShowTest
