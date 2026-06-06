package kyo

/** Tests for show methods on Symbol, Type, Tree, and Constant.
  *
  * Leaf id:7. Pins: INV-005.
  */
class ShowMethodTest extends Test with TastyTestSupport:

    import AllowUnsafe.embrace.danger

    private def makeClasspath(using Frame): Tasty.Classpath < Sync =
        import kyo.Tasty.SymbolId
        import kyo.internal.tasty.type_.TypeArena
        Sync.defer {
            val pkgWithId = Tasty.Symbol.Package(SymbolId(0), Tasty.Name("p"), Tasty.Flags.empty, SymbolId(-1), Chunk.empty)
            val clsWithId = Tasty.Symbol.Class(
                SymbolId(1),
                Tasty.Name("Foo"),
                Tasty.Flags.empty,
                SymbolId(0),
                kyo.Maybe.Absent,
                kyo.Maybe.Absent,
                kyo.Maybe.Absent,
                Chunk.empty,
                Chunk.empty,
                Chunk.empty,
                kyo.Maybe.Absent,
                Chunk.empty,
                Chunk.empty
            )
            Tasty.Classpath.make(
                symbols = Chunk(pkgWithId, clsWithId),
                rootSymbolId = SymbolId(0),
                topLevelClassIds = Chunk(SymbolId(1)),
                packageIds = Chunk(SymbolId(0)),
                fqnIndex = Dict("p.Foo" -> SymbolId(1)),
                packageIndex = Dict("p" -> SymbolId(0)),
                subclassIndex = Dict.empty,
                companionIndex = Dict.empty,
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )
        }
    end makeClasspath

    // Leaf id:7 -- Tasty.show(Symbol), Tasty.show(Type), Tasty.show(Tree), Tasty.show(Constant) emit non-empty strings
    "Tasty.show(Symbol) returns non-empty string" in run {
        makeClasspath.flatMap: cp =>
            Tasty.withClasspath(cp):
                val sym = cp.findClass("p.Foo").get
                Tasty.show(sym).map: s =>
                    assert(s.nonEmpty, s"Tasty.show(Symbol) was empty for $sym")
                    succeed
    }

    "Tasty.show(Type) returns non-empty string" in run {
        makeClasspath.flatMap: cp =>
            Tasty.withClasspath(cp):
                import kyo.Tasty.SymbolId
                val tpe = Tasty.Type.Named(SymbolId(1))
                Tasty.typeShow(tpe).map: s =>
                    assert(s.nonEmpty, s"Tasty.show(Type) was empty for $tpe")
                    succeed
    }

    "Tasty.show(Tree) returns non-empty string" in run {
        makeClasspath.flatMap: cp =>
            Tasty.withClasspath(cp):
                val tree: Tasty.Tree = Tasty.Tree.Literal(Tasty.Constant.IntConst(42))
                Tasty.treeShow(tree).map: s =>
                    assert(s.nonEmpty, s"Tasty.show(Tree) was empty for $tree")
                    succeed
    }

    "Tasty.show(Constant) returns expected format" in {
        assert(Tasty.Constant.IntConst(42).show == "42")
        assert(Tasty.Constant.StringConst("hi").show == "\"hi\"")
        assert(Tasty.Constant.LongConst(7L).show == "7L")
        assert(Tasty.Constant.BooleanConst(true).show == "true")
        assert(Tasty.Constant.FloatConst(1.5f).show == "1.5f")
        assert(Tasty.Constant.DoubleConst(3.14).show == "3.14")
        assert(Tasty.Constant.CharConst('x').show == "'x'")
        assert(Tasty.Constant.ByteConst(1.toByte).show == "1")
        assert(Tasty.Constant.ShortConst(2.toShort).show == "2")
        assert(Tasty.Constant.UnitConst.show == "()")
        assert(Tasty.Constant.NullConst.show == "null")
    }

end ShowMethodTest
