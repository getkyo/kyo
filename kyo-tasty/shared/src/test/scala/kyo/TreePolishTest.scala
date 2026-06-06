package kyo

import AllowUnsafe.embrace.danger
import kyo.Tasty.SymbolId

/** Tree.show coverage and Tree.exists.
  */
class TreePolishTest extends Test:

    // ── Leaf 161: show-covers-all-cases ──────────────────────────────────────
    // Given: one literal per Tree case
    // When: t.show
    // Then: every case returns a non-empty String
    "Leaf 161: Tree.show returns non-empty String for representative Tree cases" in run {
        val sym = Tasty.Symbol.Class(
            SymbolId(0),
            Tasty.Name("Foo"),
            Tasty.Flags.empty,
            SymbolId(0),
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
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(sym)).flatMap: cp =>
            Tasty.withClasspath(cp):
                val n     = Tasty.Name("x")
                val tpe   = Tasty.Type.Named(SymbolId(0))
                val lit   = Tasty.Tree.Literal(Tasty.Constant.IntConst(42))
                val ident = Tasty.Tree.Ident(n, tpe)
                val trees: Seq[Tasty.Tree] = Seq(
                    lit,
                    ident,
                    Tasty.Tree.Select(ident, n, tpe),
                    Tasty.Tree.Apply(ident, Chunk(lit)),
                    Tasty.Tree.TypeApply(ident, Chunk(tpe)),
                    Tasty.Tree.Block(Chunk(lit), lit),
                    Tasty.Tree.If(lit, lit, lit),
                    Tasty.Tree.New(tpe),
                    Tasty.Tree.Assign(ident, lit),
                    Tasty.Tree.Return(Maybe(lit), sym),
                    Tasty.Tree.Throw(lit),
                    Tasty.Tree.Lambda(ident, Maybe(tpe)),
                    Tasty.Tree.Typed(lit, tpe),
                    Tasty.Tree.This(sym),
                    Tasty.Tree.NamedArg(n, lit),
                    Tasty.Tree.Annotated(lit, ident),
                    Tasty.Tree.Shared(0),
                    Tasty.Tree.Modifier(Tasty.Flag.Final),
                    Tasty.Tree.TypeBounds(ident, ident),
                    Tasty.Tree.Unknown(99, 0)
                )
                Kyo.foreachDiscard(Chunk.from(trees)): t =>
                    Tasty.treeShow(t).map: s =>
                        assert(s.nonEmpty, s"Tree.show returned empty for ${t.getClass.getSimpleName}")
                .andThen(succeed)
    }

    // ── Leaf 162: exists-positive ─────────────────────────────────────────────
    // Given: Tree.Apply(fn, args) containing a Tree.Literal somewhere in args
    // When: t.exists { case _: Tree.Literal => true; case _ => false }
    // Then: returns true
    "Leaf 162: Tree.exists returns true when predicate matches a descendant" in run {
        val fn  = Tasty.Tree.Ident(Tasty.Name("f"), Tasty.Type.Named(SymbolId(0)))
        val lit = Tasty.Tree.Literal(Tasty.Constant.IntConst(1))
        val t   = Tasty.Tree.Apply(fn, Chunk(lit))
        assert(
            t.exists { case _: Tasty.Tree.Literal => true; case _ => false },
            "exists must return true when a Literal is present in the tree"
        )
        succeed
    }

    // ── Leaf 163: exists-negative ─────────────────────────────────────────────
    // Given: a tree with no Tree.Literal
    // When: same predicate
    // Then: returns false
    "Leaf 163: Tree.exists returns false when predicate matches nothing" in run {
        val fn  = Tasty.Tree.Ident(Tasty.Name("f"), Tasty.Type.Named(SymbolId(0)))
        val arg = Tasty.Tree.Ident(Tasty.Name("a"), Tasty.Type.Named(SymbolId(0)))
        val t   = Tasty.Tree.Apply(fn, Chunk(arg))
        assert(
            !t.exists { case _: Tasty.Tree.Literal => true; case _ => false },
            "exists must return false when no Literal is present"
        )
        succeed
    }

end TreePolishTest
