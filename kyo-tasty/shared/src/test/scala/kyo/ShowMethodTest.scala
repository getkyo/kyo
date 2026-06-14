package kyo

/** Tests for show methods on Symbol, Type, Tree, and Constant. */
class ShowMethodTest extends kyo.test.Test[Any] with TastyTestSupport:

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
                fullNameIndex = Dict("p.Foo" -> SymbolId(1)),
                packageIndex = Dict("p" -> SymbolId(0)),
                subclassIndex = Dict.empty,
                companionIndex = Dict.empty,
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )
        }
    end makeClasspath

    // Helper: build a TypeParam symbol that is self-owned so its fully-qualified name equals its simple name.
    private def typeParam(id: Int, name: String): Tasty.Symbol.TypeParam =
        import kyo.Tasty.SymbolId
        Tasty.Symbol.TypeParam(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(id), // self-owned: ownerId == id makes computeFullName return just the name
            Maybe.Absent,
            Tasty.TypeBounds(Tasty.Type.Nothing, Tasty.Type.Any),
            Tasty.Variance.Invariant
        )
    end typeParam

    // Helper: build a Parameter symbol with the given declared type.
    private def param(id: Int, name: String, tpe: Tasty.Type): Tasty.Symbol.Parameter =
        import kyo.Tasty.SymbolId
        Tasty.Symbol.Parameter(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(-1),
            Maybe.Absent,
            Maybe.Present(tpe),
            Maybe.Absent,
            Chunk.empty
        )
    end param

    // Helper: build a Class symbol that is self-owned so its fully-qualified name equals its simple name.
    private def selfOwnedClass(id: Int, name: String): Tasty.Symbol.Class =
        import kyo.Tasty.SymbolId
        Tasty.Symbol.Class(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(id), // self-owned
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty
        )
    end selfOwnedClass

    // Helper: build a Method symbol.
    private def method(
        id: Int,
        name: String,
        typeParamIds: Chunk[Tasty.SymbolId],
        paramListIds: Chunk[Chunk[Tasty.SymbolId]],
        declaredType: Maybe[Tasty.Type]
    ): Tasty.Symbol.Method =
        import kyo.Tasty.SymbolId
        Tasty.Symbol.Method(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(-1),
            Maybe.Absent,
            Maybe.Absent,
            declaredType,
            paramListIds,
            typeParamIds,
            Chunk.empty,
            Maybe.Absent
        )
    end method

    // classpath.show(Symbol), classpath.typeShow(Type), classpath.treeShow(Tree), Constant.show emit non-empty strings
    "classpath.show(Symbol) returns non-empty string" in {
        makeClasspath.map { classpath =>
            val symbol = classpath.findClass("p.Foo").get
            assert(classpath.show(symbol).nonEmpty, s"classpath.show(Symbol) was empty for $symbol")
        }
    }

    "classpath.typeShow(Type) returns non-empty string" in {
        import kyo.Tasty.SymbolId
        makeClasspath.map { classpath =>
            val tpe = Tasty.Type.Named(SymbolId(1))
            assert(classpath.typeShow(tpe).nonEmpty, s"classpath.typeShow(Type) was empty for $tpe")
        }
    }

    "classpath.treeShow(Tree) returns non-empty string" in {
        makeClasspath.map { classpath =>
            val tree: Tasty.Tree = Tasty.Tree.Literal(Tasty.Constant.IntConst(42))
            assert(classpath.treeShow(tree).nonEmpty, s"classpath.treeShow(Tree) was empty for $tree")
        }
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

    // StringConst escaping
    "Constant.show StringConst escapes embedded double-quote" in {
        // StringConst("a\"b") must render as "a\"b", not "a"b"
        assert(Tasty.Constant.StringConst("a\"b").show == "\"a\\\"b\"")
    }

    "Constant.show StringConst escapes newline" in {
        assert(Tasty.Constant.StringConst("line1\nline2").show == "\"line1\\nline2\"")
    }

    "Constant.show StringConst escapes backslash" in {
        assert(Tasty.Constant.StringConst("\\").show == "\"\\\\\"")
    }

    "Constant.show StringConst round-trips simple value" in {
        assert(Tasty.Constant.StringConst("hello").show == "\"hello\"")
    }

    // CharConst escaping
    "Constant.show CharConst escapes single-quote" in {
        // escapeCharLiteral('\'') => '\'' (4 chars: quote, backslash, quote, quote)
        assert(Tasty.Constant.CharConst('\'').show == "'\\''")
    }

    // FloatConst NaN/Infinity guards
    "Constant.show FloatConst NaN emits Float.NaN" in {
        assert(Tasty.Constant.FloatConst(Float.NaN).show == "Float.NaN")
    }

    "Constant.show FloatConst PositiveInfinity emits Float.PositiveInfinity" in {
        assert(Tasty.Constant.FloatConst(Float.PositiveInfinity).show == "Float.PositiveInfinity")
    }

    "Constant.show FloatConst NegativeInfinity emits Float.NegativeInfinity" in {
        assert(Tasty.Constant.FloatConst(Float.NegativeInfinity).show == "Float.NegativeInfinity")
    }

    "Constant.show FloatConst normal value still works" in {
        assert(Tasty.Constant.FloatConst(1.5f).show == "1.5f")
    }

    // DoubleConst NaN/Infinity guards
    "Constant.show DoubleConst NaN emits Double.NaN" in {
        assert(Tasty.Constant.DoubleConst(Double.NaN).show == "Double.NaN")
    }

    "Constant.show DoubleConst PositiveInfinity emits Double.PositiveInfinity" in {
        assert(Tasty.Constant.DoubleConst(Double.PositiveInfinity).show == "Double.PositiveInfinity")
    }

    "Constant.show DoubleConst NegativeInfinity emits Double.NegativeInfinity" in {
        assert(Tasty.Constant.DoubleConst(Double.NegativeInfinity).show == "Double.NegativeInfinity")
    }

    "Constant.show DoubleConst normal value still works" in {
        assert(Tasty.Constant.DoubleConst(3.14).show == "3.14")
    }

    // ClassConst unresolved Type.Named emits <id:N> placeholder
    "Constant.show ClassConst with unresolved Named emits placeholder" in {
        import kyo.Tasty.SymbolId
        assert(Tasty.Constant.ClassConst(Tasty.Type.Named(SymbolId(7))).show == "classOf[<id:7>]")
    }

    "Constant.show ClassConst with Type.Any emits classOf[Any]" in {
        assert(Tasty.Constant.ClassConst(Tasty.Type.Any).show == "classOf[Any]")
    }

    "Constant.show ClassConst with Type.Nothing emits classOf[Nothing]" in {
        assert(Tasty.Constant.ClassConst(Tasty.Type.Nothing).show == "classOf[Nothing]")
    }

    // renderType renders Function as arrow
    "renderType renders Function as Scala arrow syntax in signature" in {
        import kyo.Tasty.SymbolId
        // id=0: TypeParam "A" (self-owned)
        // id=1: TypeParam "B" (self-owned)
        // id=2: Class "List" (self-owned)
        // id=3: Parameter "f" with declaredType = A => B
        // id=4: Method "map" with typeParamIds=[1], paramListIds=[[3]], declaredType=List[B]
        val symA    = typeParam(0, "A")
        val symB    = typeParam(1, "B")
        val symList = selfOwnedClass(2, "List")
        val paramF  = param(3, "f", Tasty.Type.Function(Chunk(Tasty.Type.Named(SymbolId(0))), Tasty.Type.Named(SymbolId(1))))
        val mapMethod = method(
            4,
            "map",
            Chunk(SymbolId(1)),
            Chunk(Chunk(SymbolId(3))),
            Maybe.Present(Tasty.Type.Applied(Tasty.Type.Named(SymbolId(2)), Chunk(Tasty.Type.Named(SymbolId(1)))))
        )
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(symA, symB, symList, paramF, mapMethod)).map { classpath =>
            val sig = classpath.signature(mapMethod)
            assert(sig == "def map[B](f: (A) => B): List[B]", s"Expected 'def map[B](f: (A) => B): List[B]' but got: $sig")
        }
    }

    // renderType renders Tuple return type
    "renderType renders Tuple return type in signature" in {
        import kyo.Tasty.SymbolId
        // id=0: TypeParam "A" (self-owned)
        // id=1: TypeParam "B" (self-owned)
        // id=2: Method "pair" with declaredType=Tuple(A, B)
        val symA = typeParam(0, "A")
        val symB = typeParam(1, "B")
        val pairMethod = method(
            2,
            "pair",
            Chunk.empty,
            Chunk.empty,
            Maybe.Present(Tasty.Type.Tuple(Chunk(Tasty.Type.Named(SymbolId(0)), Tasty.Type.Named(SymbolId(1)))))
        )
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(symA, symB, pairMethod)).map { classpath =>
            val sig = classpath.signature(pairMethod)
            assert(sig == "def pair: (A, B)", s"Expected 'def pair: (A, B)' but got: $sig")
        }
    }

    // renderType renders Array param type
    "renderType renders Array param type in signature" in {
        import kyo.Tasty.SymbolId
        // id=0: Class "Int" (self-owned)
        // id=1: Class "Unit" (self-owned)
        // id=2: Parameter "xs" with declaredType=Array[Int]
        // id=3: Method "fill" with declaredType=Unit
        val symInt  = selfOwnedClass(0, "Int")
        val symUnit = selfOwnedClass(1, "Unit")
        val paramXs = param(2, "xs", Tasty.Type.Array(Tasty.Type.Named(SymbolId(0))))
        val fillMethod = method(
            3,
            "fill",
            Chunk.empty,
            Chunk(Chunk(SymbolId(2))),
            Maybe.Present(Tasty.Type.Named(SymbolId(1)))
        )
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(symInt, symUnit, paramXs, fillMethod)).map { classpath =>
            val sig = classpath.signature(fillMethod)
            assert(sig == "def fill(xs: Int[]): Unit", s"Expected 'def fill(xs: Int[]): Unit' but got: $sig")
        }
    }

    // renderType renders multi-arg Function param type
    "renderType renders multi-arg Function param type in signature" in {
        import kyo.Tasty.SymbolId
        // id=0: TypeParam "A" (self-owned)
        // id=1: TypeParam "B" (self-owned)
        // id=2: TypeParam "C" (self-owned)
        // id=3: Parameter "f" with declaredType=(A, B) => C
        // id=4: Method "combine" with declaredType=C
        val symA = typeParam(0, "A")
        val symB = typeParam(1, "B")
        val symC = typeParam(2, "C")
        val paramF = param(
            3,
            "f",
            Tasty.Type.Function(Chunk(Tasty.Type.Named(SymbolId(0)), Tasty.Type.Named(SymbolId(1))), Tasty.Type.Named(SymbolId(2)))
        )
        val combineMethod = method(
            4,
            "combine",
            Chunk.empty,
            Chunk(Chunk(SymbolId(3))),
            Maybe.Present(Tasty.Type.Named(SymbolId(2)))
        )
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(symA, symB, symC, paramF, combineMethod)).map { classpath =>
            val sig = classpath.signature(combineMethod)
            assert(sig == "def combine(f: (A, B) => C): C", s"Expected 'def combine(f: (A, B) => C): C' but got: $sig")
        }
    }

    // renderType renders ByName param type
    "renderType renders ByName param type in signature" in {
        import kyo.Tasty.SymbolId
        // id=0: Class "Int" (self-owned)
        // id=1: Parameter "x" with declaredType==> Int
        // id=2: Method "eval" with declaredType=Int
        val symInt = selfOwnedClass(0, "Int")
        val paramX = param(1, "x", Tasty.Type.ByName(Tasty.Type.Named(SymbolId(0))))
        val evalMethod = method(
            2,
            "eval",
            Chunk.empty,
            Chunk(Chunk(SymbolId(1))),
            Maybe.Present(Tasty.Type.Named(SymbolId(0)))
        )
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(symInt, paramX, evalMethod)).map { classpath =>
            val sig = classpath.signature(evalMethod)
            assert(sig == "def eval(x: => Int): Int", s"Expected 'def eval(x: => Int): Int' but got: $sig")
        }
    }

end ShowMethodTest
