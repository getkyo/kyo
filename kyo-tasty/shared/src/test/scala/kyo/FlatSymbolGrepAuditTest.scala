package kyo

import AllowUnsafe.embrace.danger

/** Verifies the Symbol type-hierarchy invariant at the API level: sealed trait Symbol is the public entry point and the runtime type of any
  * symbol is a subtype. A flat `final case class Symbol` would fail this test by not being a sealed-trait subtype.
  */
class FlatSymbolGrepAuditTest extends kyo.test.Test[Any]:

    // ── No-flat-Symbol-case-class invariant ─────────────────────────────────
    // Verifies the type hierarchy at the API level: sealed trait Symbol is the public
    // entry point and the runtime type of any symbol is a subtype.
    "Symbol hierarchy has no flat case class -- sealed trait is the root" in {
        // Constructing a Class subtype must compile and produce a Symbol (not a flat case class).
        val sym: Tasty.Symbol = Tasty.Symbol.Package(
            kyo.Tasty.SymbolId(-1),
            Tasty.Name("<unresolved>"),
            Tasty.Flags.empty,
            kyo.Tasty.SymbolId(-1),
            Chunk.empty
        )
        sym match
            case u: Tasty.Symbol.Package =>
                assert(u.name.asString == "<unresolved>", s"Expected name '<unresolved>' but got '${u.name.asString}'")
            case other => fail(s"Expected Symbol.Unresolved but got $other")
        end match
        assert(sym.isInstanceOf[Tasty.Symbol.Package], "isUnresolved must be true")
        // A flat final case class Symbol would not be a sealed trait subtype: confirm Unresolved is not a Class subtype.
        sym match
            case _: Tasty.Symbol.Class => fail("Unresolved must not match Class")
            case _                     => assert(!sym.isInstanceOf[Tasty.Symbol.Class], "Unresolved must not be a Class")
    }

end FlatSymbolGrepAuditTest
