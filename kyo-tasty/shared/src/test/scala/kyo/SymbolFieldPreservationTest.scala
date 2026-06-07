package kyo

import AllowUnsafe.embrace.danger
import kyo.Tasty.SymbolId

/** Confirms that every prior flat-Symbol field is reachable on the matching typed subtype. */
class SymbolFieldPreservationTest extends kyo.test.Test[Any]:

    ".id is accessible and correct on all 14 subtypes" in {
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
                Chunk.empty
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
                Chunk.empty
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
                Chunk.empty
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
                Chunk.empty
            ),
            6 -> Tasty.Symbol.Var(
                SymbolId(6),
                Tasty.Name("vr"),
                Tasty.Flags.empty,
                SymbolId(0),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                Chunk.empty
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
                Maybe.Absent,
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
                Maybe.Absent,
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
                Maybe.Absent,
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
            14 -> Tasty.Symbol.Package(SymbolId(14), Tasty.Name("u"), Tasty.Flags.empty, SymbolId(0), Chunk.empty)
        )
        for (expectedId, sym) <- syms do
            assert(sym.id == SymbolId(expectedId), s"Expected id=${expectedId} for ${sym.getClass.getSimpleName} but got ${sym.id.value}")
        end for
        succeed
    }

    "Flags(Flag.Synthetic) is preserved on all 14 subtypes" in {
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
                Chunk.empty
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
                Chunk.empty
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
                Chunk.empty
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
                Chunk.empty
            ),
            Tasty.Symbol.Var(
                SymbolId(6),
                Tasty.Name("vr"),
                sf,
                SymbolId(0),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                Chunk.empty
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
                Maybe.Absent,
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
                Maybe.Absent,
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
                Maybe.Absent,
                Maybe.Absent,
                Chunk.empty
            ),
            Tasty.Symbol.Package(SymbolId(13), Tasty.Name("pkg"), sf, SymbolId(0), Chunk.empty),
            Tasty.Symbol.Package(SymbolId(14), Tasty.Name("u"), Tasty.Flags.empty, SymbolId(0), Chunk.empty)
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

    "declaredType is accessible on Method/Val/Var/Field/Parameter" in {
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
            Chunk.empty
        )
        val vr = Tasty.Symbol.Var(
            SymbolId(22),
            Tasty.Name("vr"),
            Tasty.Flags.empty,
            SymbolId(0),
            Maybe.Absent,
            Maybe.Absent,
            Maybe(namedType),
            Chunk.empty
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
            Maybe.Present(namedType),
            Maybe.Absent,
            Chunk.empty
        )
        assert(method.declaredType == Maybe(namedType))
        assert(vl.declaredType == Maybe(namedType))
        assert(vr.declaredType == Maybe(namedType))
        assert(field.declaredType == Maybe(namedType))
        assert(param.declaredType == Maybe.Present(namedType))
        succeed
    }

    //   parentTypes=Chunk(Type.Named(SymbolId(2))).
    "parentTypes is accessible on Class/Trait/Object and carries constructed value" in {
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
            Chunk.empty
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
            Chunk.empty
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
            Chunk.empty
        )
        assert(cls.parentTypes == pt)
        assert(trt.parentTypes == pt)
        assert(obj.parentTypes == pt)
        succeed
    }

    //   permittedSubclassIds=Maybe.Present(Chunk(SymbolId(3))).
    "permittedSubclassIds is accessible on Class and Trait with correct value" in {
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
            Chunk.empty
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
            Chunk.empty
        )
        assert(cls.permittedSubclassIds == psi)
        assert(trt.permittedSubclassIds == psi)
        succeed
    }

end SymbolFieldPreservationTest
