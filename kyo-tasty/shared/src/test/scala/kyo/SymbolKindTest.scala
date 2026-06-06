package kyo

import kyo.internal.tasty.symbol.SymbolKind

/** Tests for SymbolKind enum case set.
  *
  * SymbolKind moved from Tasty.SymbolKind to kyo.internal.tasty.symbol.SymbolKind in.
  * All path references updated accordingly. The enum is private[kyo], so test code in package kyo
  * can still access it directly.
  *
  * Actual SymbolKind cases: Package, Class, Trait, Object, Method, Field, Val, Var, TypeAlias, OpaqueType, AbstractType, TypeParam,
  * Parameter, EnumCase. Total: 14 cases (Unresolved removed in).
  */
class SymbolKindTest extends Test:

    // Test 8 (T2, SymbolKind): enum contains every documented case and has the expected count.
    // Given: SymbolKind.values array.
    // When: converted to a Set.
    // Then: the set contains all 14 documented cases; values.length == 14.
    "SymbolKind.values contains all 14 documented cases" in {
        val values = SymbolKind.values
        assert(
            values.length == 14,
            s"Expected 14 SymbolKind cases but got ${values.length}: ${values.mkString(", ")}"
        )
        val kindSet = values.toSet
        val expected: List[SymbolKind] = List(
            SymbolKind.Package,
            SymbolKind.Class,
            SymbolKind.Trait,
            SymbolKind.Object,
            SymbolKind.Method,
            SymbolKind.Field,
            SymbolKind.Val,
            SymbolKind.Var,
            SymbolKind.TypeAlias,
            SymbolKind.OpaqueType,
            SymbolKind.AbstractType,
            SymbolKind.TypeParam,
            SymbolKind.Parameter,
            SymbolKind.EnumCase
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
        val names = SymbolKind.values.map(_.toString).toSet
        assert(!names.contains("Module"), "SymbolKind.Module does not exist; use SymbolKind.Object")
        assert(!names.contains("ParamVal"), "SymbolKind.ParamVal does not exist; use SymbolKind.Parameter")
        assert(!names.contains("ParamType"), "SymbolKind.ParamType does not exist; use SymbolKind.TypeParam")
        assert(!names.contains("Constructor"), "SymbolKind.Constructor does not exist; constructors are SymbolKind.Method")
    }

end SymbolKindTest
