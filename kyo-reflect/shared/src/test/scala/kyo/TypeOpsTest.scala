package kyo

import kyo.internal.reflect.query.ClasspathRef
import kyo.internal.reflect.type_.TypeOps

/** Tests for TypeOps smart constructors.
  *
  * Plan tests 6-11.
  */
class TypeOpsTest extends Test:

    private def makeNamedSym(fqn: String): Reflect.Type.Named =
        val parts = fqn.split("\\.").toList
        val root = Reflect.Symbol.make(
            Reflect.SymbolKind.Package,
            Reflect.Flags.empty,
            Reflect.Name(""),
            null,
            new ClasspathRef,
            Reflect.Symbol.TastyOrigin.empty,
            Absent
        )
        parts.foldLeft(root) { (owner, part) =>
            Reflect.Symbol.make(
                Reflect.SymbolKind.Class,
                Reflect.Flags.empty,
                Reflect.Name(part),
                owner,
                new ClasspathRef,
                Reflect.Symbol.TastyOrigin.empty,
                Absent
            )
        }
        val finalSym = parts.foldLeft(root) { (owner, part) =>
            Reflect.Symbol.make(
                Reflect.SymbolKind.Class,
                Reflect.Flags.empty,
                Reflect.Name(part),
                owner,
                new ClasspathRef,
                Reflect.Symbol.TastyOrigin.empty,
                Absent
            )
        }
        Reflect.Type.Named(finalSym)
    end makeNamedSym

    private val A: Reflect.Type = makeNamedSym("A")
    private val B: Reflect.Type = makeNamedSym("B")
    private val C: Reflect.Type = makeNamedSym("C")
    private val T: Reflect.Type = makeNamedSym("T")
    private val X: Reflect.Type = makeNamedSym("X")

    // Test 6: applied(Named(scala.Function2), Chunk(A, B, C)) produces Function(Chunk(A, B), C, false).
    "applied(Named(scala.Function2), [A, B, C]) => Function([A, B], C, false)" in run {
        val base   = makeNamedSym("scala.Function2")
        val result = TypeOps.applied(base, Chunk(A, B, C))
        result match
            case Reflect.Type.Function(ps, r, ctx) =>
                assert(ps.length == 2)
                assert(r eq C)
                assert(!ctx)
            case other =>
                fail(s"Expected Function but got $other")
        end match
    }

    // Test 7: applied(Named(scala.Tuple2), Chunk(A, B)) produces Tuple(Chunk(A, B)).
    "applied(Named(scala.Tuple2), [A, B]) => Tuple([A, B])" in run {
        val base   = makeNamedSym("scala.Tuple2")
        val result = TypeOps.applied(base, Chunk(A, B))
        result match
            case Reflect.Type.Tuple(elems) =>
                assert(elems.length == 2)
            case other =>
                fail(s"Expected Tuple but got $other")
        end match
    }

    // Test 8: applied(Named(scala.ContextFunction1), Chunk(A, B)) produces Function(Chunk(A), B, true).
    "applied(Named(scala.ContextFunction1), [A, B]) => Function([A], B, true)" in run {
        val base   = makeNamedSym("scala.ContextFunction1")
        val result = TypeOps.applied(base, Chunk(A, B))
        result match
            case Reflect.Type.Function(ps, r, ctx) =>
                assert(ps.length == 1)
                assert(r eq B)
                assert(ctx)
            case other =>
                fail(s"Expected Function but got $other")
        end match
    }

    // Test 9: applied(Named(scala.Array), Chunk(T)) produces Type.Array(T).
    "applied(Named(scala.Array), [T]) => Array(T)" in run {
        val base   = makeNamedSym("scala.Array")
        val result = TypeOps.applied(base, Chunk(T))
        result match
            case Reflect.Type.Array(elem) =>
                assert(elem eq T)
            case other =>
                fail(s"Expected Array but got $other")
        end match
    }

    // Test 10: andType(Named(scala.Singleton), X) collapses to X.
    "andType(Named(scala.Singleton), X) => X" in run {
        val singleton = makeNamedSym("scala.Singleton")
        val result    = TypeOps.andType(singleton, X)
        assert(result eq X)
    }

    // Test 11: andType(X, Named(scala.Singleton)) collapses to X.
    "andType(X, Named(scala.Singleton)) => X" in run {
        val singleton = makeNamedSym("scala.Singleton")
        val result    = TypeOps.andType(X, singleton)
        assert(result eq X)
    }

end TypeOpsTest
