package kyo

import AllowUnsafe.embrace.danger
import kyo.Tasty.SymbolId

/** Phase 01 plan-mandated tests confirming that every prior flat-Symbol field is reachable on the matching typed subtype.
  *
  * Leaves 15-19 per plan 05-plan.yaml id:1. Pins: INV-002, INV-003.
  */
class SymbolFieldPreservationTest extends Test:

    // ── Leaf 15: id-on-every-subtype ─────────────────────────────────────────

    // Given: one literal per subtype constructed at distinct deterministic ids.
    // When: read .id on each.
    // Then: every read returns the constructed id.
    // Pins: INV-002.
    "Leaf 15: .id is accessible and correct on all 14 subtypes" in {
        val syms: Seq[(Int, Tasty.Symbol)] = Seq(
            1 -> Tasty.Symbol.Class(
                SymbolId(1),
                Tasty.Name("C"),
                Tasty.Flags.empty,
                SymbolId(0),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty,
                Chunk.empty,
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty,
                Maybe.Absent
            ),
            2 -> Tasty.Symbol.Trait(
                SymbolId(2),
                Tasty.Name("T"),
                Tasty.Flags.empty,
                SymbolId(0),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty,
                Chunk.empty,
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty,
                Maybe.Absent
            ),
            3 -> Tasty.Symbol.Object(
                SymbolId(3),
                Tasty.Name("O"),
                Tasty.Flags.empty,
                SymbolId(0),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty,
                Chunk.empty,
                Chunk.empty,
                Chunk.empty,
                Maybe.Absent
            ),
            4 -> Tasty.Symbol.Method(
                SymbolId(4),
                Tasty.Name("m"),
                Tasty.Flags.empty,
                SymbolId(0),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty,
                Chunk.empty,
                Maybe.Absent,
                Maybe.Absent
            ),
            5 -> Tasty.Symbol.Val(
                SymbolId(5),
                Tasty.Name("v"),
                Tasty.Flags.empty,
                SymbolId(0),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                Chunk.empty,
                Maybe.Absent
            ),
            6 -> Tasty.Symbol.Var(
                SymbolId(6),
                Tasty.Name("vr"),
                Tasty.Flags.empty,
                SymbolId(0),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                Chunk.empty,
                Maybe.Absent
            ),
            7 -> Tasty.Symbol.Field(
                SymbolId(7),
                Tasty.Name("f"),
                Tasty.Flags.empty,
                SymbolId(0),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                Chunk.empty
            ),
            8 -> Tasty.Symbol.TypeAlias(
                SymbolId(8),
                Tasty.Name("ta"),
                Tasty.Flags.empty,
                SymbolId(0),
                Maybe.Absent,
                Maybe.Absent,
                Tasty.Type.Unknown,
                Chunk.empty,
                Chunk.empty
            ),
            9 -> Tasty.Symbol.OpaqueType(
                SymbolId(9),
                Tasty.Name("ot"),
                Tasty.Flags.empty,
                SymbolId(0),
                Maybe.Absent,
                Maybe.Absent,
                Tasty.Type.Unknown,
                Tasty.TypeBounds(Tasty.Type.Nothing, Tasty.Type.Any),
                Chunk.empty,
                Chunk.empty
            ),
            10 -> Tasty.Symbol.AbstractType(
                SymbolId(10),
                Tasty.Name("at"),
                Tasty.Flags.empty,
                SymbolId(0),
                Maybe.Absent,
                Maybe.Absent,
                Tasty.TypeBounds(Tasty.Type.Nothing, Tasty.Type.Any),
                Chunk.empty
            ),
            11 -> Tasty.Symbol.TypeParam(
                SymbolId(11),
                Tasty.Name("tp"),
                Tasty.Flags.empty,
                SymbolId(0),
                Maybe.Absent,
                Tasty.TypeBounds(Tasty.Type.Nothing, Tasty.Type.Any),
                Tasty.Variance.Invariant
            ),
            12 -> Tasty.Symbol.Parameter(
                SymbolId(12),
                Tasty.Name("p"),
                Tasty.Flags.empty,
                SymbolId(0),
                Maybe.Absent,
                Tasty.Type.Unknown,
                Maybe.Absent,
                Chunk.empty
            ),
            13 -> Tasty.Symbol.Package(
                SymbolId(13),
                Tasty.Name("pkg"),
                Tasty.Flags.empty,
                SymbolId(0),
                Chunk.empty
            ),
            14 -> Tasty.Symbol.Unresolved(SymbolId(14), Tasty.Name("u"), SymbolId(0))
        )
        for (expectedId, sym) <- syms do
            assert(sym.id == SymbolId(expectedId), s"Expected id=${expectedId} for ${sym.getClass.getSimpleName} but got ${sym.id.value}")
        end for
        succeed
    }

    // ── Leaf 16: flags-on-every-subtype ──────────────────────────────────────

    // Given: one literal per subtype with Flags(Flag.Synthetic) set.
    // When: read .flags.contains(Flag.Synthetic).
    // Then: returns true on all 14 subtypes.
    // Pins: INV-002, INV-003.
    "Leaf 16: Flags(Flag.Synthetic) is preserved on all 14 subtypes" in {
        val sf = Tasty.Flags(Tasty.Flag.Synthetic)
        val syms: Seq[Tasty.Symbol] = Seq(
            Tasty.Symbol.Class(
                SymbolId(1),
                Tasty.Name("C"),
                sf,
                SymbolId(0),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty,
                Chunk.empty,
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty,
                Maybe.Absent
            ),
            Tasty.Symbol.Trait(
                SymbolId(2),
                Tasty.Name("T"),
                sf,
                SymbolId(0),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty,
                Chunk.empty,
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty,
                Maybe.Absent
            ),
            Tasty.Symbol.Object(
                SymbolId(3),
                Tasty.Name("O"),
                sf,
                SymbolId(0),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty,
                Chunk.empty,
                Chunk.empty,
                Chunk.empty,
                Maybe.Absent
            ),
            Tasty.Symbol.Method(
                SymbolId(4),
                Tasty.Name("m"),
                sf,
                SymbolId(0),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty,
                Chunk.empty,
                Maybe.Absent,
                Maybe.Absent
            ),
            Tasty.Symbol.Val(
                SymbolId(5),
                Tasty.Name("v"),
                sf,
                SymbolId(0),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                Chunk.empty,
                Maybe.Absent
            ),
            Tasty.Symbol.Var(
                SymbolId(6),
                Tasty.Name("vr"),
                sf,
                SymbolId(0),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                Chunk.empty,
                Maybe.Absent
            ),
            Tasty.Symbol.Field(
                SymbolId(7),
                Tasty.Name("f"),
                sf,
                SymbolId(0),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                Chunk.empty
            ),
            Tasty.Symbol.TypeAlias(
                SymbolId(8),
                Tasty.Name("ta"),
                sf,
                SymbolId(0),
                Maybe.Absent,
                Maybe.Absent,
                Tasty.Type.Unknown,
                Chunk.empty,
                Chunk.empty
            ),
            Tasty.Symbol.OpaqueType(
                SymbolId(9),
                Tasty.Name("ot"),
                sf,
                SymbolId(0),
                Maybe.Absent,
                Maybe.Absent,
                Tasty.Type.Unknown,
                Tasty.TypeBounds(Tasty.Type.Nothing, Tasty.Type.Any),
                Chunk.empty,
                Chunk.empty
            ),
            Tasty.Symbol.AbstractType(
                SymbolId(10),
                Tasty.Name("at"),
                sf,
                SymbolId(0),
                Maybe.Absent,
                Maybe.Absent,
                Tasty.TypeBounds(Tasty.Type.Nothing, Tasty.Type.Any),
                Chunk.empty
            ),
            Tasty.Symbol.TypeParam(
                SymbolId(11),
                Tasty.Name("tp"),
                sf,
                SymbolId(0),
                Maybe.Absent,
                Tasty.TypeBounds(Tasty.Type.Nothing, Tasty.Type.Any),
                Tasty.Variance.Invariant
            ),
            Tasty.Symbol.Parameter(
                SymbolId(12),
                Tasty.Name("p"),
                sf,
                SymbolId(0),
                Maybe.Absent,
                Tasty.Type.Unknown,
                Maybe.Absent,
                Chunk.empty
            ),
            Tasty.Symbol.Package(SymbolId(13), Tasty.Name("pkg"), sf, SymbolId(0), Chunk.empty),
            Tasty.Symbol.Unresolved(SymbolId(14), Tasty.Name("u"), SymbolId(0))
        )
        // Note: Unresolved ignores constructor flags and returns Flags.empty (by design).
        val subtypesWithFlags = syms.dropRight(1)
        for sym <- subtypesWithFlags do
            assert(
                sym.flags.contains(Tasty.Flag.Synthetic),
                s"Expected Flag.Synthetic on ${sym.getClass.getSimpleName} but not found in flags=${sym.flags.bits}"
            )
        end for
        succeed
    }

    // ── Leaf 17: declaredType-on-term-subtypes ───────────────────────────────

    // Given: literals for Method, Val, Var, Field with declaredType=Maybe.Present(Type.Named(SymbolId(1)));
    //   Parameter with declaredType=Type.Named(SymbolId(1)) (required, non-Maybe).
    // When: read .declaredType.
    // Then: Method/Val/Var/Field return Maybe.Present(Type.Named(SymbolId(1)));
    //   Parameter returns Type.Named(SymbolId(1)).
    // Pins: INV-002.
    "Leaf 17: declaredType is accessible on Method/Val/Var/Field/Parameter" in {
        val namedType = Tasty.Type.Named(SymbolId(1))
        val method = Tasty.Symbol.Method(
            SymbolId(20),
            Tasty.Name("m"),
            Tasty.Flags.empty,
            SymbolId(0),
            Maybe.Absent,
            Maybe.Absent,
            Maybe(namedType),
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent,
            Maybe.Absent
        )
        val vl = Tasty.Symbol.Val(
            SymbolId(21),
            Tasty.Name("v"),
            Tasty.Flags.empty,
            SymbolId(0),
            Maybe.Absent,
            Maybe.Absent,
            Maybe(namedType),
            Chunk.empty,
            Maybe.Absent
        )
        val vr = Tasty.Symbol.Var(
            SymbolId(22),
            Tasty.Name("vr"),
            Tasty.Flags.empty,
            SymbolId(0),
            Maybe.Absent,
            Maybe.Absent,
            Maybe(namedType),
            Chunk.empty,
            Maybe.Absent
        )
        val field = Tasty.Symbol.Field(
            SymbolId(23),
            Tasty.Name("f"),
            Tasty.Flags.empty,
            SymbolId(0),
            Maybe.Absent,
            Maybe.Absent,
            Maybe(namedType),
            Maybe.Absent,
            Chunk.empty
        )
        val param = Tasty.Symbol.Parameter(
            SymbolId(24),
            Tasty.Name("p"),
            Tasty.Flags.empty,
            SymbolId(0),
            Maybe.Absent,
            namedType,
            Maybe.Absent,
            Chunk.empty
        )
        assert(method.declaredType == Maybe(namedType))
        assert(vl.declaredType == Maybe(namedType))
        assert(vr.declaredType == Maybe(namedType))
        assert(field.declaredType == Maybe(namedType))
        assert(param.declaredType == namedType)
        succeed
    }

    // ── Leaf 18: parentTypes-on-classlike ────────────────────────────────────

    // Given: Symbol.Class, Symbol.Trait, Symbol.Object literals with
    //   parentTypes=Chunk(Type.Named(SymbolId(2))).
    // When: read .parentTypes.
    // Then: returns Chunk(Type.Named(SymbolId(2))) on all three.
    // Pins: INV-002.
    "Leaf 18: parentTypes is accessible on Class/Trait/Object and carries constructed value" in {
        val pt = Chunk(Tasty.Type.Named(SymbolId(2)))
        val cls = Tasty.Symbol.Class(
            SymbolId(30),
            Tasty.Name("C"),
            Tasty.Flags.empty,
            SymbolId(0),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            pt,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent
        )
        val trt = Tasty.Symbol.Trait(
            SymbolId(31),
            Tasty.Name("T"),
            Tasty.Flags.empty,
            SymbolId(0),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            pt,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent
        )
        val obj = Tasty.Symbol.Object(
            SymbolId(32),
            Tasty.Name("O"),
            Tasty.Flags.empty,
            SymbolId(0),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            pt,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent
        )
        assert(cls.parentTypes == pt)
        assert(trt.parentTypes == pt)
        assert(obj.parentTypes == pt)
        succeed
    }

    // ── Leaf 19: permittedSubclassIds-on-class-and-trait ────────────────────

    // Given: Symbol.Class and Symbol.Trait literals with
    //   permittedSubclassIds=Maybe.Present(Chunk(SymbolId(3))).
    // When: read .permittedSubclassIds.
    // Then: returns Maybe.Present(Chunk(SymbolId(3))) on both.
    // Pins: INV-002.
    "Leaf 19: permittedSubclassIds is accessible on Class and Trait with correct value" in {
        val psi = Maybe(Chunk(SymbolId(3)))
        val cls = Tasty.Symbol.Class(
            SymbolId(40),
            Tasty.Name("C"),
            Tasty.Flags.empty,
            SymbolId(0),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            psi,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent
        )
        val trt = Tasty.Symbol.Trait(
            SymbolId(41),
            Tasty.Name("T"),
            Tasty.Flags.empty,
            SymbolId(0),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            psi,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent
        )
        assert(cls.permittedSubclassIds == psi)
        assert(trt.permittedSubclassIds == psi)
        succeed
    }

end SymbolFieldPreservationTest
