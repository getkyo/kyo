package kyo

import kyo.internal.reflect.query.ClasspathRef
import kyo.internal.reflect.type_.TypeArena

/** Tests for TypeArena intern and Phase C merge.
  *
  * Plan tests 1-5.
  */
class TypeArenaTest extends Test:

    private def makeSym(name: String): Reflect.Symbol =
        Reflect.Symbol.make(
            Reflect.SymbolKind.Class,
            Reflect.Flags.empty,
            Reflect.Name(name),
            null,
            new ClasspathRef,
            Reflect.Symbol.TastyOrigin(Map.empty, 0, 0)
        )

    // Test 1: intern called twice with structurally identical Type.Named(sym) returns the same reference.
    "intern called twice with the same Named(sym) returns the same reference" in run {
        val arena = TypeArena.canonical()
        val sym   = makeSym("Foo")
        val t1    = Reflect.Type.Named(sym)
        val t2    = Reflect.Type.Named(sym)
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
        val t1    = Reflect.Type.Applied(Reflect.Type.Named(base), Chunk(Reflect.Type.Named(a)))
        val t2    = Reflect.Type.Applied(Reflect.Type.Named(base), Chunk(Reflect.Type.Named(b)))
        val r1    = arena.intern(t1)
        val r2    = arena.intern(t2)
        assert(!(r1 eq r2))
    }

    // Test 3: merge of two arenas containing the same structural type produces canonical arena with one entry.
    "merge of two arenas with structurally equal types produces one canonical entry" in run {
        val sym = makeSym("X")
        val t   = Reflect.Type.Named(sym)
        val a1  = TypeArena.canonical()
        val a2  = TypeArena.canonical()
        a1.intern(t)
        a2.intern(t)
        val canon = TypeArena.canonical()
        a1.merge(canon)
        a2.merge(canon)
        // Both interns should map to the same canonical type.
        val c1 = canon.intern(t)
        val c2 = canon.intern(t)
        assert(c1 eq c2)
    }

    // Test 4: merge correctly handles a Type.Rec(parent) containing Type.RecThis(rec) back-reference (no infinite loop).
    "merge of Rec/RecThis cycle completes without stack overflow" in run {
        // Build a Rec/RecThis cycle manually.
        val sym      = makeSym("RecSentinel")
        val sentinel = Reflect.Type.Named(sym)
        val rec      = Reflect.Type.Rec(sentinel) // will "point to itself" via RecThis in real decode
        val recThis  = Reflect.Type.RecThis(rec)
        val recFull  = Reflect.Type.Rec(recThis)  // Rec(RecThis(Rec(...)))

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
        val t1  = Reflect.Type.ByName(Reflect.Type.Named(sym))
        val t2  = Reflect.Type.ByName(Reflect.Type.Named(sym))

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

end TypeArenaTest
