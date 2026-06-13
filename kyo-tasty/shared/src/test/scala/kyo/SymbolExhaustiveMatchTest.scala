package kyo

import AllowUnsafe.embrace.danger
import kyo.Tasty.SymbolId

/** Confirms exhaustive match on the Symbol hierarchy under -Xfatal-warnings. */
class SymbolExhaustiveMatchTest extends kyo.test.Test[Any]:

    "exhaustive 14-case match on Symbol compiles cleanly" in {
        def kindLabel(s: Tasty.Symbol): String = s match
            case _: Tasty.Symbol.Class        => "Class"
            case _: Tasty.Symbol.Trait        => "Trait"
            case _: Tasty.Symbol.Object       => "Object"
            case _: Tasty.Symbol.Method       => "Method"
            case _: Tasty.Symbol.Val          => "Val"
            case _: Tasty.Symbol.Var          => "Var"
            case _: Tasty.Symbol.Field        => "Field"
            case _: Tasty.Symbol.TypeAlias    => "TypeAlias"
            case _: Tasty.Symbol.OpaqueType   => "OpaqueType"
            case _: Tasty.Symbol.AbstractType => "AbstractType"
            case _: Tasty.Symbol.TypeParam    => "TypeParam"
            case _: Tasty.Symbol.Parameter    => "Parameter"
            case _: Tasty.Symbol.Package      => "Package"

        val symbol: Tasty.Symbol = Tasty.Symbol.Package(SymbolId(-1), Tasty.Name("x"), Tasty.Flags.empty, SymbolId(-1), Chunk.empty)
        assert(kindLabel(symbol) == "Package")
        succeed
    }

    "exhaustive 3-case match on Symbol.ClassLike compiles cleanly" in {
        def cl(c: Tasty.Symbol.ClassLike): String = c match
            case _: Tasty.Symbol.Class  => "C"
            case _: Tasty.Symbol.Trait  => "T"
            case _: Tasty.Symbol.Object => "O"

        val obj: Tasty.Symbol.ClassLike = Tasty.Symbol.Object(
            id = SymbolId(1),
            name = Tasty.Name("MyObj"),
            flags = Tasty.Flags.empty,
            ownerId = SymbolId(0),
            scaladoc = Maybe.Absent,
            sourcePosition = Maybe.Absent,
            javaMetadata = Maybe.Absent,
            parentTypes = Chunk.empty,
            typeParamIds = Chunk.empty,
            declarationIds = Chunk.empty,
            annotations = Chunk.empty,
            javaAnnotations = Chunk.empty
        )
        assert(cl(obj) == "O")
        succeed
    }

end SymbolExhaustiveMatchTest
