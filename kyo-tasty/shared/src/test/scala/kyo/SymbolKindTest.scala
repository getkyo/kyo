package kyo

/** Tests for Tasty.SymbolKind enum case set.
  *
  * Phase 21g (T2). Cross-references Tasty.scala SymbolKind definition. The plan listed provisional names (Module, ParamVal, ParamType,
  * Constructor) that do not match the actual enum; this test is authoritative against the real Tasty.scala definition.
  *
  * Actual SymbolKind cases (Tasty.scala line 144-148): Package, Class, Trait, Object, Method, Field, Val, Var, TypeAlias, OpaqueType,
  * AbstractType, TypeParam, Parameter, Unresolved Total: 14 cases.
  */
class SymbolKindTest extends Test:

    // Test 8 (T2, SymbolKind): enum contains every documented case and has the expected count.
    // Given: Tasty.SymbolKind.values array.
    // When: converted to a Set.
    // Then: the set contains all 14 documented cases; values.length == 14.
    // Pins: T2.
    "SymbolKind.values contains all 14 documented cases" in {
        val values = Tasty.SymbolKind.values
        assert(
            values.length == 14,
            s"Expected 14 SymbolKind cases but got ${values.length}: ${values.mkString(", ")}"
        )
        val kindSet = values.toSet
        val expected: List[Tasty.SymbolKind] = List(
            Tasty.SymbolKind.Package,
            Tasty.SymbolKind.Class,
            Tasty.SymbolKind.Trait,
            Tasty.SymbolKind.Object,
            Tasty.SymbolKind.Method,
            Tasty.SymbolKind.Field,
            Tasty.SymbolKind.Val,
            Tasty.SymbolKind.Var,
            Tasty.SymbolKind.TypeAlias,
            Tasty.SymbolKind.OpaqueType,
            Tasty.SymbolKind.AbstractType,
            Tasty.SymbolKind.TypeParam,
            Tasty.SymbolKind.Parameter,
            Tasty.SymbolKind.Unresolved
        )
        val missing = expected.filterNot(kindSet.contains)
        assert(
            missing.isEmpty,
            s"SymbolKind.values is missing cases: ${missing.mkString(", ")}"
        )
    }

    // Additional: plan draft listed names that do not exist (Module, ParamVal, ParamType, Constructor).
    // This test documents the reconciliation: those names are not cases in the enum.
    // The correct name for Scala object-kind is Object, not Module (Module is a Flag).
    "SymbolKind does not have cases named Module ParamVal ParamType Constructor" in {
        val names = Tasty.SymbolKind.values.map(_.toString).toSet
        assert(!names.contains("Module"), "SymbolKind.Module does not exist; use SymbolKind.Object")
        assert(!names.contains("ParamVal"), "SymbolKind.ParamVal does not exist; use SymbolKind.Parameter")
        assert(!names.contains("ParamType"), "SymbolKind.ParamType does not exist; use SymbolKind.TypeParam")
        assert(!names.contains("Constructor"), "SymbolKind.Constructor does not exist; constructors are SymbolKind.Method")
    }

end SymbolKindTest
