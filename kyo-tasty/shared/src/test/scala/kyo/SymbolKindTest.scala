package kyo

import kyo.internal.tasty.symbol.SymbolKind

/** Tests for SymbolKind enum case set.
  *
  * SymbolKind is `kyo.internal.tasty.symbol.SymbolKind`. The enum is private[kyo], so test code in package kyo
  * can access it directly.
  *
  * Actual SymbolKind cases: Package, Class, Trait, Object, Method, Field, Val, Var, TypeAlias, OpaqueType, AbstractType, TypeParam,
  * Parameter, EnumCase. Total: 14 cases.
  */
class SymbolKindTest extends kyo.test.Test[Any]:

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

    // Pinned negative names that do not exist on SymbolKind (Module, ParamVal, ParamType, Constructor).
    // The correct name for Scala object-kind is Object, not Module (Module is a Flag).
    "SymbolKind does not have cases named Module ParamVal ParamType Constructor" in {
        val names = SymbolKind.values.map(_.toString).toSet
        assert(!names.contains("Module"), "SymbolKind.Module does not exist; use SymbolKind.Object")
        assert(!names.contains("ParamVal"), "SymbolKind.ParamVal does not exist; use SymbolKind.Parameter")
        assert(!names.contains("ParamType"), "SymbolKind.ParamType does not exist; use SymbolKind.TypeParam")
        assert(!names.contains("Constructor"), "SymbolKind.Constructor does not exist; constructors are SymbolKind.Method")
    }

end SymbolKindTest
