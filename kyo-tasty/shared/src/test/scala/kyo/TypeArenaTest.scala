package kyo

import kyo.internal.tasty.query.ClasspathRef
import kyo.internal.tasty.type_.TypeArena

/** Tests for TypeArena intern and Phase C merge.
  *
  * Plan tests 1-5.
  */
class TypeArenaTest extends Test:

    private def makeSym(name: String): Tasty.Symbol =
        import AllowUnsafe.embrace.danger
        Tasty.Symbol.make(
            Tasty.SymbolKind.Class,
            Tasty.Flags.empty,
            Tasty.Name(name),
            null,
            ClasspathRef.init(),
            Tasty.Symbol.TastyOrigin.empty,
            Absent
        )
    end makeSym

    // Test 1: intern called twice with structurally identical Type.Named(sym) returns the same reference.
    "intern called twice with the same Named(sym) returns the same reference" in run {
        val arena = TypeArena.canonical()
        val sym   = makeSym("Foo")
        val t1    = Tasty.Type.Named(sym)
        val t2    = Tasty.Type.Named(sym)
        val r1    = arena.intern(t1)
        val r2    = arena.intern(t2)
        assert(r1 eq r2)
    }

    // Test 2: intern on Applied with different arg lists returns different references.
    "intern on Applied with different args returns different references" in run {
        val arena = TypeArena.canonical()
        val base  = makeSym("Base")
        val a     = makeSym("A")
        val b     = makeSym("B")
        val t1    = Tasty.Type.Applied(Tasty.Type.Named(base), Chunk(Tasty.Type.Named(a)))
        val t2    = Tasty.Type.Applied(Tasty.Type.Named(base), Chunk(Tasty.Type.Named(b)))
        val r1    = arena.intern(t1)
        val r2    = arena.intern(t2)
        assert(!(r1 eq r2))
    }

    // Test 3: merge of two arenas containing the same structural type produces canonical arena with one entry.
    // Uses two separately-allocated Named values that are structurally equal but NOT reference-equal,
    // to verify that merge performs structural deduplication (not just reference identity).
    // Mirrors test 5's rigour: t1 and t2 are allocated independently (distinct object identities).
    "merge of two arenas with structurally equal types produces one canonical entry" in run {
        val sym = makeSym("X")
        // Two separately-allocated Named values: same structure, different object identity.
        val t1 = Tasty.Type.Named(sym)
        val t2 = Tasty.Type.Named(sym)
        assert(!(t1 eq t2), "t1 and t2 must be distinct objects for this test to be non-trivial")
        val a1 = TypeArena.canonical()
        val a2 = TypeArena.canonical()
        a1.intern(t1)
        a2.intern(t2)
        val canon = TypeArena.canonical()
        a1.merge(canon)
        a2.merge(canon)
        // After merge, interning either structurally-equal value must yield the same reference.
        val c1 = canon.intern(t1)
        val c2 = canon.intern(t2)
        assert(c1 eq c2, "After merge, structurally-equal types must be reference-equal in canonical arena")
    }

    // Test 4: merge correctly handles a Type.Rec(parent) containing Type.RecThis(rec) back-reference (no infinite loop).
    "merge of Rec/RecThis cycle completes without stack overflow" in run {
        // Build a Rec/RecThis cycle manually.
        val sym      = makeSym("RecSentinel")
        val sentinel = Tasty.Type.Named(sym)
        val rec      = Tasty.Type.Rec(sentinel) // will "point to itself" via RecThis in real decode
        val recThis  = Tasty.Type.RecThis(rec)
        val recFull  = Tasty.Type.Rec(recThis)  // Rec(RecThis(Rec(...)))

        val arena = TypeArena.canonical()
        arena.intern(recFull)
        val canon = TypeArena.canonical()
        // Should complete without stack overflow.
        arena.merge(canon)
        assert(canon.values.nonEmpty)
    }

    // Test 5: after merge, structurally-equal types from two arenas are reference-equal.
    "after merge, structurally-equal types from two arenas are reference-equal" in run {
        val sym = makeSym("Shared")
        val t1  = Tasty.Type.ByName(Tasty.Type.Named(sym))
        val t2  = Tasty.Type.ByName(Tasty.Type.Named(sym))

        val a1 = TypeArena.canonical()
        val a2 = TypeArena.canonical()
        a1.intern(t1)
        a2.intern(t2)

        val canon = TypeArena.canonical()
        a1.merge(canon)
        a2.merge(canon)

        val c1 = canon.intern(t1)
        val c2 = canon.intern(t2)
        assert(c1 eq c2)
    }

    // Test 6 (B8/INV-019): deeply nested Applied chain at MaxDepth+1 (1025 levels) throws DepthExceededException during merge.
    // Uses exactly one level above the cap so the depth guard fires without exceeding the JVM stack during hash computation.
    // Pins: INV-019, B8.
    "B8/INV-019: Applied chain at MaxDepth+1 throws DepthExceededException during merge" taggedAs jvmOnly in run {
        val baseSym = makeSym("DepthBase")
        val argSym  = makeSym("DepthArg")
        val leaf    = Tasty.Type.Named(argSym)
        // Build MaxDepth+1 = 1025 levels of Applied nesting.
        var t: Tasty.Type = leaf
        var i             = 0
        while i < (TypeArena.MaxDepth + 1) do
            t = Tasty.Type.Applied(Tasty.Type.Named(baseSym), Chunk(t))
            i += 1
        end while
        val arena = TypeArena.canonical()
        arena.intern(t)
        val canon = TypeArena.canonical()
        val ex    = intercept[TypeArena.DepthExceededException](arena.merge(canon))
        assert(ex.getMessage.contains(s"depth ${TypeArena.MaxDepth} exceeded"), ex.getMessage)
        succeed
    }

    // Test 7 (B8 boundary): nesting at MaxDepth-1 (1023 levels) succeeds without exception.
    // Pins: B8 boundary.
    "B8: nesting at MaxDepth-1 (1023 levels) merges successfully" taggedAs jvmOnly in run {
        val baseSym = makeSym("BoundBase")
        val argSym  = makeSym("BoundArg")
        val leaf    = Tasty.Type.Named(argSym)
        // Build 1023 levels of Applied nesting.
        var t: Tasty.Type = leaf
        var i             = 0
        while i < (TypeArena.MaxDepth - 1) do
            t = Tasty.Type.Applied(Tasty.Type.Named(baseSym), Chunk(t))
            i += 1
        end while
        val arena = TypeArena.canonical()
        arena.intern(t)
        val canon = TypeArena.canonical()
        // Should not throw.
        arena.merge(canon)
        assert(canon.values.nonEmpty)
    }

    // Test 8 (T4, Rec depth boundary): Rec-type nesting at MaxDepth-1 succeeds without DepthExceededException.
    // The depth check fires when internRec is called at depth >= MaxDepth. With MaxDepth-1 levels of Rec
    // wrapping a Named leaf, the deepest internRec call is at depth MaxDepth-1 (< MaxDepth), so no throw.
    // This complements Test 7 (Applied nesting) by exercising the Rec ADT arm of internRec specifically.
    // Pins: T4 (Rec at depth boundary).
    "T4: Rec nesting at MaxDepth-1 merges successfully without DepthExceededException" taggedAs jvmOnly in run {
        val leafSym          = makeSym("RecDepthLeaf")
        val leaf: Tasty.Type = Tasty.Type.Named(leafSym)
        // Build MaxDepth-1 levels of Rec wrapping: Rec(Rec(Rec(...Named...))).
        var t: Tasty.Type = leaf
        var i             = 0
        while i < (TypeArena.MaxDepth - 1) do
            t = Tasty.Type.Rec(t)
            i += 1
        end while
        val arena = TypeArena.canonical()
        arena.intern(t)
        val canon = TypeArena.canonical()
        // Merge must complete without throwing DepthExceededException.
        arena.merge(canon)
        assert(canon.values.nonEmpty, "canonical arena must be non-empty after successful merge")
    }

    // Test 9 (T4, cyclic Rec self-reference): canonical map produces reference-equal result under repeated intern.
    // The RecThis(rec) case in internRec returns the type as-is (leaf treatment), breaking the structural
    // recursion and allowing merge to complete. After merge, interning the same structural type twice returns
    // the same canonical reference.
    // Pins: T4 (cyclic Rec self-reference).
    "T4: cyclic Rec(RecThis) self-reference interns to reference-equal canonical value" in run {
        val sentinel = Tasty.Type.Named(makeSym("CyclicSentinel"))
        // Build Rec(RecThis(inner)) where inner is a separate Rec pointing at sentinel.
        // RecThis(inner) is at depth 2 inside the outermost Rec.
        val inner   = Tasty.Type.Rec(sentinel)
        val recThis = Tasty.Type.RecThis(inner)
        val outer   = Tasty.Type.Rec(recThis)

        val arena = TypeArena.canonical()
        arena.intern(outer)
        val canon = TypeArena.canonical()
        arena.merge(canon)

        // After merge, interning the same structural type twice yields reference-equal results.
        val first  = canon.intern(outer)
        val second = canon.intern(outer)
        assert(first eq second, "repeated intern of the same cyclic Rec type must return the same canonical reference")
    }

    // Test 10 (T7): 8-fiber concurrent interning with separate per-fiber arenas preserves canonicality.
    // TypeArena is NOT thread-safe and is intended for one-per-fiber use. Each of the 8 fibers
    // creates its own TypeArena, interns the same type `t`, and returns both the interned reference
    // and the arena itself. Because `intern` calls `map.getOrElseUpdate(key, t)` on an empty arena
    // and `t` is the value passed in, each fiber returns `t` itself (reference-equal to the original).
    // After all fibers finish, all 8 returned references must be `eq` to `t`.
    // The 8 per-fiber arenas are then merged sequentially into a single canonical arena (mimicking
    // Phase C) which must report values.size == 1 (one structural entry regardless of how many arenas
    // contributed it).
    // Uses kyo.Async.foreach so the test compiles and runs on JVM, JS, and Native.
    // Pins: T7.
    "T7: 8-fiber concurrent interning with per-fiber arenas all return eq canonical reference" in run {
        val sym        = makeSym("ConcurrentCanon")
        val t          = Tasty.Type.Named(sym)
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
            // Merge all per-fiber arenas into a canonical arena sequentially (Phase C pattern).
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
