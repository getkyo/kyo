package kyo
import AllowUnsafe.embrace.danger
import kyo.Tasty.SymbolId
import kyo.internal.tasty.symbol.SymbolKind
import kyo.internal.tasty.type_.TypeOps

/** Tests for TypeOps smart constructors.
  *
  * TypeOps.applied and TypeOps.andType return Applied/AndType pass-through because Named(symbolId) no longer carries a name directly.
  */
class TypeOpsTest extends kyo.test.Test[Any]:

    private def makeNamedSym(fqn: String): Tasty.Type.Named =
        val leafName = fqn.split("\\.").last
        val sym      = Tasty.Symbol.Package(Tasty.SymbolId(-1), Tasty.Name(leafName), Tasty.Flags.empty, Tasty.SymbolId(-1), Chunk.empty)
        Tasty.Type.Named(sym.id)
    end makeNamedSym

    private val A: Tasty.Type = makeNamedSym("A")
    private val B: Tasty.Type = makeNamedSym("B")
    private val C: Tasty.Type = makeNamedSym("C")
    private val T: Tasty.Type = makeNamedSym("T")
    private val X: Tasty.Type = makeNamedSym("X")

    "applied(Named(scala.Function2), [A, B, C]) => Applied (phase-05 pass-through)" in {
        import AllowUnsafe.embrace.danger
        val base   = makeNamedSym("scala.Function2")
        val result = TypeOps.applied(base, Chunk(A, B, C))
        result match
            case Tasty.Type.Applied(_, args) =>
                assert(args.length == 3)
            case other =>
                fail(s"Expected Applied (phase-05 pass-through) but got $other")
        end match
    }

    "applied(Named(scala.Tuple2), [A, B]) => Applied (phase-05 pass-through)" in {
        import AllowUnsafe.embrace.danger
        val base   = makeNamedSym("scala.Tuple2")
        val result = TypeOps.applied(base, Chunk(A, B))
        result match
            case Tasty.Type.Applied(_, args) =>
                assert(args.length == 2)
            case other =>
                fail(s"Expected Applied (phase-05 pass-through) but got $other")
        end match
    }

    "applied(Named(scala.ContextFunction1), [A, B]) => Applied (phase-05 pass-through)" in {
        import AllowUnsafe.embrace.danger
        val base   = makeNamedSym("scala.ContextFunction1")
        val result = TypeOps.applied(base, Chunk(A, B))
        result match
            case Tasty.Type.Applied(_, args) =>
                assert(args.length == 2)
            case other =>
                fail(s"Expected Applied (phase-05 pass-through) but got $other")
        end match
    }

    "applied(Named(scala.Array), [T]) => Applied (phase-05 pass-through)" in {
        import AllowUnsafe.embrace.danger
        val base   = makeNamedSym("scala.Array")
        val result = TypeOps.applied(base, Chunk(T))
        result match
            case Tasty.Type.Applied(_, args) =>
                assert(args.length == 1)
            case other =>
                fail(s"Expected Applied (phase-05 pass-through) but got $other")
        end match
    }

    "andType(Named(scala.Singleton), X) => AndType (phase-05 pass-through)" in {
        import AllowUnsafe.embrace.danger
        val singleton = makeNamedSym("scala.Singleton")
        val result    = TypeOps.andType(singleton, X)
        result match
            case Tasty.Type.AndType(l, r) =>
                assert(l eq singleton)
                assert(r eq X)
            case other =>
                fail(s"Expected AndType (phase-05 pass-through) but got $other")
        end match
    }

    "andType(X, Named(scala.Singleton)) => AndType (phase-05 pass-through)" in {
        import AllowUnsafe.embrace.danger
        val singleton = makeNamedSym("scala.Singleton")
        val result    = TypeOps.andType(X, singleton)
        result match
            case Tasty.Type.AndType(l, r) =>
                assert(l eq X)
                assert(r eq singleton)
            case other =>
                fail(s"Expected AndType (phase-05 pass-through) but got $other")
        end match
    }

end TypeOpsTest
