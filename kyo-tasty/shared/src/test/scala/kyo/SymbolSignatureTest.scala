package kyo

import kyo.Tasty.SymbolId
import kyo.internal.tasty.symbol.SymbolSignature

/** Tests for SymbolSignature.compute across all symbol subtypes.
  *
  * Verifies that the signature string is non-empty and follows the documented pattern for each subtype.
  */
class SymbolSignatureTest extends Test:

    import AllowUnsafe.embrace.danger

    private def makePackage(id: Int, name: String, members: Chunk[SymbolId] = Chunk.empty): Tasty.Symbol.Package =
        Tasty.Symbol.Package(SymbolId(id), Tasty.Name(name), Tasty.Flags.empty, SymbolId(-1), members)

    private def makeClass(
        id: Int,
        name: String,
        ownerId: Int,
        parentTypes: Chunk[Tasty.Type] = Chunk.empty,
        tpIds: Chunk[SymbolId] = Chunk.empty,
        decls: Chunk[SymbolId] = Chunk.empty
    ): Tasty.Symbol.Class =
        Tasty.Symbol.Class(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            parentTypes,
            tpIds,
            decls,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty
        )

    private def makeMethod(
        id: Int,
        name: String,
        ownerId: Int,
        plistIds: Chunk[Chunk[SymbolId]] = Chunk.empty,
        retType: Maybe[Tasty.Type] = Maybe.Absent
    ): Tasty.Symbol.Method =
        Tasty.Symbol.Method(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Maybe.Absent,
            Maybe.Absent,
            retType,
            plistIds,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent
        )

    private def makeVal(id: Int, name: String, ownerId: Int, tpe: Maybe[Tasty.Type]): Tasty.Symbol.Val =
        Tasty.Symbol.Val(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Maybe.Absent,
            Maybe.Absent,
            tpe,
            Chunk.empty
        )

    private def makeParameter(id: Int, name: String, ownerId: Int, tpe: Tasty.Type): Tasty.Symbol.Parameter =
        Tasty.Symbol.Parameter(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Maybe.Absent,
            Maybe.Present(tpe),
            Maybe.Absent,
            Chunk.empty
        )

    private def buildFixture(using Frame): Tasty.Classpath < Sync =
        Sync.defer:
            val pkg    = makePackage(0, "pkg")
            val retCls = makeClass(1, "String", ownerId = 0)
            val param  = makeParameter(3, "x", ownerId = 2, tpe = Tasty.Type.Named(SymbolId(1)))
            val method = makeMethod(
                2,
                "greet",
                ownerId = 0,
                plistIds = Chunk(Chunk(SymbolId(3))),
                retType = Maybe(Tasty.Type.Named(SymbolId(1)))
            )
            val valFoo = makeVal(4, "result", ownerId = 0, tpe = Maybe(Tasty.Type.Named(SymbolId(1))))
            Tasty.Classpath.make(
                symbols = Chunk(pkg, retCls, method, param, valFoo),
                rootSymbolId = SymbolId(-1),
                topLevelClassIds = Chunk(SymbolId(1)),
                packageIds = Chunk(SymbolId(0)),
                fqnIndex = Dict("pkg.String" -> SymbolId(1)),
                packageIndex = Dict("pkg" -> SymbolId(0)),
                subclassIndex = Dict.empty,
                companionIndex = Dict.empty,
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )

    "SymbolSignature.compute for Method returns def-prefixed string with param list" in run {
        buildFixture.flatMap: cp =>
            val m = cp.symbol(SymbolId(2)).asInstanceOf[Tasty.Symbol.Method]
            SymbolSignature.compute(m, cp).map: sig =>
                assert(sig.startsWith("def greet"), s"Unexpected: $sig")
                assert(sig.contains("(x: "), s"Expected param in: $sig")
                succeed
    }

    "SymbolSignature.compute for Class returns class-prefixed string" in run {
        buildFixture.flatMap: cp =>
            val c = cp.symbol(SymbolId(1)).asInstanceOf[Tasty.Symbol.Class]
            SymbolSignature.compute(c, cp).map: sig =>
                assert(sig.startsWith("class "), s"Unexpected: $sig")
                assert(sig.contains("String"), s"Expected name in: $sig")
                succeed
    }

    "SymbolSignature.compute for Val returns val-prefixed string" in run {
        buildFixture.flatMap: cp =>
            val v = cp.symbol(SymbolId(4)).asInstanceOf[Tasty.Symbol.Val]
            SymbolSignature.compute(v, cp).map: sig =>
                assert(sig.startsWith("val result"), s"Unexpected: $sig")
                succeed
    }

    "SymbolSignature.compute for Package returns package-prefixed string" in run {
        buildFixture.flatMap: cp =>
            val p = cp.symbol(SymbolId(0)).asInstanceOf[Tasty.Symbol.Package]
            SymbolSignature.compute(p, cp).map: sig =>
                assert(sig.startsWith("package "), s"Unexpected: $sig")
                succeed
    }

    "SymbolSignature.compute for TypeAlias returns type-equals string" in run {
        val ta = Tasty.Symbol.TypeAlias(
            SymbolId(0),
            Tasty.Name("MyAlias"),
            Tasty.Flags.empty,
            SymbolId(-1),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Present(Tasty.Type.Nothing),
            Chunk.empty,
            Chunk.empty
        )
        val cp = Tasty.Classpath.make(
            symbols = Chunk(ta),
            rootSymbolId = SymbolId(-1),
            topLevelClassIds = Chunk.empty,
            packageIds = Chunk.empty,
            fqnIndex = Dict.empty,
            packageIndex = Dict.empty,
            subclassIndex = Dict.empty,
            companionIndex = Dict.empty,
            moduleIndex = Dict.empty,
            errors = Chunk.empty
        )
        SymbolSignature.compute(ta, cp).map: sig =>
            assert(sig.contains("type MyAlias"), s"Unexpected: $sig")
            assert(sig.contains("="), s"Expected '=' in: $sig")
            succeed
    }

end SymbolSignatureTest
