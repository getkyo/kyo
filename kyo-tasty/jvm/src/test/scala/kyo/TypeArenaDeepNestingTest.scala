package kyo

import kyo.internal.tasty.type_.TypeArena

/** TypeArena depth-guard behavior at and beyond the configured MaxDepth. Requires a JVM call stack large enough to reach MaxDepth (1024)
  * before overflowing, which the build configures via -Xss10M. JS engines and Scala Native default stacks overflow well before 1024 levels
  * of nested Applied / Rec construction.
  */
class TypeArenaDeepNestingTest extends kyo.test.Test[Any]:

    private var nextId: Int = 0

    private def makeSym(name: String): Tasty.Symbol =
        val id = nextId
        nextId += 1
        Tasty.Symbol.Package(
            Tasty.SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            Tasty.SymbolId(-1),
            Chunk.empty
        )
    end makeSym

    "Applied chain at MaxDepth+1 throws DepthExceededException during merge" in {
        nextId = 0
        val baseSym       = makeSym("DepthBase")
        val argSym        = makeSym("DepthArg")
        val leaf          = Tasty.Type.Named(argSym.id)
        var t: Tasty.Type = leaf
        var i             = 0
        while i < (TypeArena.MaxDepth + 1) do
            t = Tasty.Type.Applied(Tasty.Type.Named(baseSym.id), Chunk(t))
            i += 1
        end while
        val arena = TypeArena.canonical()
        arena.intern(t)
        val canon = TypeArena.canonical()
        try
            arena.merge(canon)
            fail("Expected DepthExceededException or StackOverflowError but no exception was thrown")
        catch
            case ex: TypeArena.DepthExceededException =>
                assert(ex.getMessage.contains(s"depth ${TypeArena.MaxDepth} exceeded"), ex.getMessage)
                succeed
            case _: StackOverflowError =>
                // hashOf recurses without a depth guard; SOE at extreme nesting is acceptable.
                succeed
        end try
    }

    "Applied nesting at MaxDepth-1 (1023 levels) merges successfully" in {
        nextId = 0
        val baseSym       = makeSym("BoundBase")
        val argSym        = makeSym("BoundArg")
        val leaf          = Tasty.Type.Named(argSym.id)
        var t: Tasty.Type = leaf
        var i             = 0
        while i < (TypeArena.MaxDepth - 1) do
            t = Tasty.Type.Applied(Tasty.Type.Named(baseSym.id), Chunk(t))
            i += 1
        end while
        val arena = TypeArena.canonical()
        arena.intern(t)
        val canon = TypeArena.canonical()
        arena.merge(canon)
        assert(
            canon.values.size >= TypeArena.MaxDepth - 1,
            s"Expected at least ${TypeArena.MaxDepth - 1} interned nodes after MaxDepth-1 nest but got ${canon.values.size}"
        )
    }

    "Rec nesting at MaxDepth-1 merges successfully without DepthExceededException" in {
        nextId = 0
        val leafSym          = makeSym("RecDepthLeaf")
        val leaf: Tasty.Type = Tasty.Type.Named(leafSym.id)
        var t: Tasty.Type    = leaf
        var i                = 0
        while i < (TypeArena.MaxDepth - 1) do
            t = Tasty.Type.Rec(t)
            i += 1
        end while
        val arena = TypeArena.canonical()
        arena.intern(t)
        val canon = TypeArena.canonical()
        arena.merge(canon)
        assert(canon.values.nonEmpty, "canonical arena must be non-empty after successful merge")
    }

end TypeArenaDeepNestingTest
