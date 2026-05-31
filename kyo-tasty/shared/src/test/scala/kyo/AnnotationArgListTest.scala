package kyo

import kyo.internal.tasty.symbol.SymbolId

/** Plan-mandated tests for Phase 08 (leaf 164): Annotation.argList.
  *
  * Pins: INV-010.
  */
class AnnotationArgListTest extends Test:

    // ── Leaf 164: argList-decoded-chunk ──────────────────────────────────────
    // Given: an Annotation with args = Maybe.Present(Tree.Apply(_, Chunk(Literal("hi"))))
    // When: a.argList
    // Then: returns Chunk(Literal("hi"))
    "Leaf 164: argList returns Chunk of args from Tree.Apply" in run {
        val tpe    = Tasty.Type.Named(SymbolId(0))
        val fn     = Tasty.Tree.Ident(Tasty.Name("apply"), tpe)
        val lit    = Tasty.Tree.Literal(Tasty.Constant.StringConst("hi"))
        val apply  = Tasty.Tree.Apply(fn, Chunk(lit))
        val ann    = Tasty.Annotation(tpe, Maybe(apply))
        val result = ann.argList
        assert(result.length == 1, s"Expected 1 arg, got ${result.length}")
        assert(result(0).isInstanceOf[Tasty.Tree.Literal], s"Expected Literal, got ${result(0).getClass.getSimpleName}")
        succeed
    }

    // Additional coverage: argList on non-Apply tree wraps it in Chunk(tree)
    "Leaf 164b: argList wraps non-Apply tree in a single-element Chunk" in run {
        val tpe    = Tasty.Type.Named(SymbolId(0))
        val ident  = Tasty.Tree.Ident(Tasty.Name("x"), tpe)
        val ann    = Tasty.Annotation(tpe, Maybe(ident))
        val result = ann.argList
        assert(result.length == 1, s"Expected 1 element for non-Apply tree, got ${result.length}")
        succeed
    }

    // Additional coverage: argList on Absent args returns Chunk.empty
    "Leaf 164c: argList returns Chunk.empty when args is Absent" in run {
        val tpe = Tasty.Type.Named(SymbolId(0))
        val ann = Tasty.Annotation(tpe, Maybe.Absent)
        assert(ann.argList.isEmpty, "argList must be empty when args is Absent")
        succeed
    }

end AnnotationArgListTest
