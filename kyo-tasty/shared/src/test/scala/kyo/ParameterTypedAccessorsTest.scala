package kyo

import kyo.Tasty.SymbolId

/** Tests for Parameter typed accessors: defaultArg, isImplicit, isByName, and isRepeated. */
class ParameterTypedAccessorsTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    // ── Synthetic builders ────────────────────────────────────────────────────

    private def makeDefaultArgSymbol(id: Int): Tasty.Symbol.Method =
        Tasty.Symbol.Method(
            SymbolId(id),
            Tasty.Name("f$default$1"),
            Tasty.Flags.empty,
            SymbolId(-1),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent
        )

    private def makeParameter(
        id: Int,
        name: String,
        declaredType: Maybe[Tasty.Type],
        defaultArgId: Maybe[SymbolId] = Maybe.Absent,
        flags: Tasty.Flags = Tasty.Flags.empty
    ): Tasty.Symbol.Parameter =
        Tasty.Symbol.Parameter(
            SymbolId(id),
            Tasty.Name(name),
            flags,
            SymbolId(-1),
            Maybe.Absent,
            declaredType,
            defaultArgId,
            Chunk.empty
        )

    "defaultArg-present: Parameter.defaultArg returns Present when defaultArgId is Present" in {
        // defaultArgMethod at index 0, parameter at index 1
        val defaultArgMethod = makeDefaultArgSymbol(0)
        val param            = makeParameter(1, "x", Maybe.Absent, defaultArgId = Maybe(SymbolId(0)))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(defaultArgMethod, param)).map { classpath =>
            given Tasty.Classpath       = classpath
            val da: Maybe[Tasty.Symbol] = param.defaultArgId.flatMap(id => classpath.symbol(id))
            assert(da.isDefined, "defaultArg must be Present when defaultArgId is Present")
            succeed
        }
    }

    "defaultArg-absent: Parameter.defaultArg returns Absent when defaultArgId is Absent" in {
        val param = makeParameter(0, "x", Maybe.Absent, defaultArgId = Maybe.Absent)
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(param)).map { classpath =>
            given Tasty.Classpath       = classpath
            val da: Maybe[Tasty.Symbol] = param.defaultArgId.flatMap(id => classpath.symbol(id))
            assert(!da.isDefined, "defaultArg must be Absent when defaultArgId is Absent")
            succeed
        }
    }

    "isImplicit-given: Parameter.isImplicit returns true for a Given-flagged parameter" in {
        val givenFlags = Tasty.Flags(Tasty.Flag.Given)
        val param      = makeParameter(0, "x", Maybe.Absent, flags = givenFlags)
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(param)).map { classpath =>
            assert(param.flags.contains(Tasty.Flag.Given), "isImplicit must be true when Flag.Given is set")
            succeed
        }
    }

    "isByName-typed: Parameter.isByName returns true when declaredType is Type.ByName" in {
        val byNameType = Tasty.Type.ByName(Tasty.Type.Nothing)
        val param      = makeParameter(0, "x", Maybe.Present(byNameType))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(param)).map { classpath =>
            assert(
                param.declaredType.map(_.isInstanceOf[Tasty.Type.ByName]).getOrElse(false),
                "isByName must be true when declaredType is Type.ByName"
            )
            assert(
                !param.declaredType.map(_.isInstanceOf[Tasty.Type.Repeated]).getOrElse(false),
                "isRepeated must be false when declaredType is Type.ByName"
            )
            succeed
        }
    }

    "isRepeated-typed: Parameter.isRepeated returns true when declaredType is Type.Repeated" in {
        val repeatedType = Tasty.Type.Repeated(Tasty.Type.Nothing)
        val param        = makeParameter(0, "xs", Maybe.Present(repeatedType))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(param)).map { classpath =>
            assert(
                param.declaredType.map(_.isInstanceOf[Tasty.Type.Repeated]).getOrElse(false),
                "isRepeated must be true when declaredType is Type.Repeated"
            )
            assert(
                !param.declaredType.map(_.isInstanceOf[Tasty.Type.ByName]).getOrElse(false),
                "isByName must be false when declaredType is Type.Repeated"
            )
            succeed
        }
    }

end ParameterTypedAccessorsTest
