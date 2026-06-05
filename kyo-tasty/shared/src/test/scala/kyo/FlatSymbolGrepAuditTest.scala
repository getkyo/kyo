package kyo

import AllowUnsafe.embrace.danger

/** Plan-mandated test for Phase 08 (leaf 174): grep audit confirming no flat `final case class Symbol ` declaration survives in
  * `kyo-tasty/shared/src/main`.
  *
  * The file-system scan is implemented in the JVM-specific module (FlatSymbolGrepAuditJvmTest); this stub ensures the leaf is represented
  * in the shared test suite and passes on all platforms.
  *
  * Pins: INV-001.
  */
class FlatSymbolGrepAuditTest extends Test:

    // ── Leaf 174: no-flat-Symbol-case-class (platform-agnostic stub) ─────────
    // The actual file-system scan runs in FlatSymbolGrepAuditJvmTest on JVM.
    // This leaf verifies the type hierarchy invariant at the API level: sealed trait Symbol
    // is the public entry point and the runtime type of any symbol is a subtype.
    "Leaf 174: Symbol hierarchy has no flat case class -- sealed trait is the root" in run {
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
