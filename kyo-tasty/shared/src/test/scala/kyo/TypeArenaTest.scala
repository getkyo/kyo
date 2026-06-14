package kyo

import AllowUnsafe.embrace.danger
import kyo.Tasty.SymbolId
import kyo.internal.tasty.type_.TypeArena

/** Tests for TypeArena intern and merge operations.
  *
  * Named(id) carries SymbolId; makeSym assigns unique ids so that Named types created from different
  * symbols are distinct (id equality, not reference equality).
  */
class TypeArenaTest extends kyo.test.Test[Any]:

    private var nextId: Int = 0

    private def freshId(): SymbolId =
        val id = nextId
        nextId += 1
        SymbolId(id)
    end freshId

    private def makeSym(name: String): Tasty.Symbol.Class =
        Tasty.Symbol.Class(
            freshId(),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(-1),
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
    end makeSym

    "intern called twice with the same Named(id) returns the same reference" in {
        nextId = 0
        val arena  = TypeArena.canonical()
        val symbol = makeSym("Foo")
        val t1     = Tasty.Type.Named(symbol.id)
        val t2     = Tasty.Type.Named(symbol.id)
        val r1     = arena.intern(t1)
        val r2     = arena.intern(t2)
        assert(r1 eq r2)
    }

    "intern on Applied with different args returns different references" in {
        nextId = 0
        val arena = TypeArena.canonical()
        val base  = makeSym("Base")
        val a     = makeSym("A")
        val b     = makeSym("B")
        val t1    = Tasty.Type.Applied(Tasty.Type.Named(base.id), Chunk(Tasty.Type.Named(a.id)))
        val t2    = Tasty.Type.Applied(Tasty.Type.Named(base.id), Chunk(Tasty.Type.Named(b.id)))
        val r1    = arena.intern(t1)
        val r2    = arena.intern(t2)
        assert(!(r1 eq r2))
    }

    "merge of two arenas with structurally equal types produces one canonical entry" in {
        nextId = 0
        val symbol = makeSym("X")
        // Two separately-allocated Named values: same id, different object identity.
        val t1 = Tasty.Type.Named(symbol.id)
        val t2 = Tasty.Type.Named(symbol.id)
        assert(!(t1 eq t2), "t1 and t2 must be distinct objects for this test to be non-trivial")
        val a1 = TypeArena.canonical()
        val a2 = TypeArena.canonical()
        a1.intern(t1)
        a2.intern(t2)
        val canon = TypeArena.canonical()
        a1.merge(canon)
        a2.merge(canon)
        val c1 = canon.intern(t1)
        val c2 = canon.intern(t2)
        assert(c1 eq c2, "After merge, structurally-equal types must be reference-equal in canonical arena")
    }

    "merge of Rec/RecThis cycle completes without stack overflow" in {
        nextId = 0
        val symbol   = makeSym("RecSentinel")
        val sentinel = Tasty.Type.Named(symbol.id)
        val rec      = Tasty.Type.Rec(sentinel)
        val recThis  = Tasty.Type.RecThis(rec)
        val recFull  = Tasty.Type.Rec(recThis)
        val arena    = TypeArena.canonical()
        arena.intern(recFull)
        val canon = TypeArena.canonical()
        arena.merge(canon)
        // After merging the Rec(RecThis(rec)) cycle, the canonical arena must contain exactly 2 distinct
        // interned types: the outer Rec and the inner Named sentinel (RecThis is a back-reference sharing
        // the outer Rec slot; it does not produce an additional canonical node).
        assert(canon.values.size == 2, s"Expected exactly 2 interned nodes after Rec/RecThis merge but got ${canon.values.size}")
        val interned = canon.intern(recFull)
        assert(interned == recFull, "Re-interning recFull must yield a structurally equal type")
    }

    "after merge, structurally-equal types from two arenas are reference-equal" in {
        nextId = 0
        val symbol = makeSym("Shared")
        val t1     = Tasty.Type.ByName(Tasty.Type.Named(symbol.id))
        val t2     = Tasty.Type.ByName(Tasty.Type.Named(symbol.id))
        val a1     = TypeArena.canonical()
        val a2     = TypeArena.canonical()
        a1.intern(t1)
        a2.intern(t2)
        val canon = TypeArena.canonical()
        a1.merge(canon)
        a2.merge(canon)
        val c1 = canon.intern(t1)
        val c2 = canon.intern(t2)
        assert(c1 eq c2)
    }

    // Cyclic Rec self-reference: canonical map produces reference-equal result.
    "cyclic Rec(RecThis) self-reference interns to reference-equal canonical value" in {
        nextId = 0
        val sentinel = Tasty.Type.Named(makeSym("CyclicSentinel").id)
        val inner    = Tasty.Type.Rec(sentinel)
        val recThis  = Tasty.Type.RecThis(inner)
        val outer    = Tasty.Type.Rec(recThis)
        val arena    = TypeArena.canonical()
        arena.intern(outer)
        val canon = TypeArena.canonical()
        arena.merge(canon)
        val first  = canon.intern(outer)
        val second = canon.intern(outer)
        assert(first eq second, "repeated intern of the same cyclic Rec type must return the same canonical reference")
    }

    "8-fiber concurrent interning with per-fiber arenas all return eq canonical reference" in {
        nextId = 0
        val symbol     = makeSym("ConcurrentCanon")
        val t          = Tasty.Type.Named(symbol.id)
        val fiberCount = 8
        Async.foreach(0 until fiberCount, concurrency = fiberCount) { _ =>
            Sync.defer {
                val arena = TypeArena.canonical()
                (arena.intern(t), arena)
            }
        }.map { results =>
            assert(results.size == fiberCount, s"Expected $fiberCount results, got ${results.size}")
            var allEq = true
            var idx   = 0
            while idx < results.size && allEq do
                if !(results(idx)._1 eq t) then allEq = false
                idx += 1
            end while
            assert(allEq, s"Not all $fiberCount fibers returned the same reference as t")
            val canon = TypeArena.canonical()
            var i     = 0
            while i < results.size do
                results(i)._2.merge(canon)
                i += 1
            end while
            assert(
                canon.values.size == 1,
                s"Expected 1 canonical entry after merge but got ${canon.values.size}"
            )
        }
    }

end TypeArenaTest
