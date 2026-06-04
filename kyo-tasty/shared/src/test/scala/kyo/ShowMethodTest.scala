package kyo

/** Tests for show methods on Symbol, Type, Tree, and Constant (Phase 10 Items 4 and 14).
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
                Chunk.empty,
                kyo.Maybe.Absent
            )
            Tasty.Classpath.make(
                symbols = Chunk(pkgWithId, clsWithId),
                rootSymbolId = SymbolId(0),
                topLevelClassIds = Chunk(SymbolId(1)),
                packageIds = Chunk(SymbolId(0)),
                fqnIndex = Map("p.Foo" -> SymbolId(1)),
                packageIndex = Map("p" -> SymbolId(0)),
                subclassIndex = Map.empty,
                companionIndex = Map.empty,
                moduleIndex = Map.empty,
                errors = Chunk.empty,
                canonical = TypeArena.canonical()
            )
        }
    end makeClasspath

    // Leaf id:7 -- Symbol.show, Type.show, Tree.show, Constant.show emit non-empty strings
    "Symbol.show returns non-empty string" in run {
        makeClasspath.flatMap: cp =>
            given Tasty.Classpath = cp
            val sym               = cp.findClass("p.Foo").get
            sym.show.map: s =>
                assert(s.nonEmpty, s"Symbol.show was empty for $sym")
                succeed
    }

    "Type.show returns non-empty string" in run {
        makeClasspath.map: cp =>
            import kyo.Tasty.SymbolId
            val tpe = Tasty.Type.Named(SymbolId(1))
            val s   = Tasty.typeShow(tpe)(using cp)
            assert(s.nonEmpty, s"Type.show was empty for $tpe")
            succeed
    }

    "Tree.show returns non-empty string" in run {
        makeClasspath.map: cp =>
            val tree: Tasty.Tree = Tasty.Tree.Literal(Tasty.Constant.IntConst(42))
            val s                = Tasty.treeShow(tree)(using cp)
            assert(s.nonEmpty, s"Tree.show was empty for $tree")
            succeed
    }

    "Constant.show returns expected format" in {
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
