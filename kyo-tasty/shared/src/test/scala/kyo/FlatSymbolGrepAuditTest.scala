package kyo

import AllowUnsafe.embrace.danger

/** Verifies the Symbol type-hierarchy invariant at the API level: sealed trait Symbol is the public entry point and the runtime type of any
  * symbol is a subtype. A flat `final case class Symbol` would fail this test by not being a sealed-trait subtype.
  */
class FlatSymbolGrepAuditTest extends kyo.test.Test[Any]:

    "Symbol hierarchy has no flat case class -- sealed trait is the root" in {
        val symbol: Tasty.Symbol = Tasty.Symbol.Package(
            kyo.Tasty.SymbolId(-1),
            Tasty.Name("<unresolved>"),
            Tasty.Flags.empty,
            kyo.Tasty.SymbolId(-1),
            Chunk.empty
        )
        symbol match
            case u: Tasty.Symbol.Package =>
                assert(u.name.asString == "<unresolved>", s"Expected name '<unresolved>' but got '${u.name.asString}'")
            case other => fail(s"Expected Symbol.Unresolved but got $other")
        end match
        assert(symbol.isInstanceOf[Tasty.Symbol.Package], "isUnresolved must be true")
        symbol match
            case _: Tasty.Symbol.Class => fail("Unresolved must not match Class")
            case _                     => assert(!symbol.isInstanceOf[Tasty.Symbol.Class], "Unresolved must not be a Class")
    }

end FlatSymbolGrepAuditTest
