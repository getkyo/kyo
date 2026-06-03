package kyo

import AllowUnsafe.embrace.danger
import kyo.Tasty.SymbolId

/** Plan-mandated tests for Phase 08 (leaf 164): Annotation.arguments.
  *
  * Pins: INV-010.
  */
class AnnotationArgListTest extends Test:

    // ── Leaf 164: arguments is the eager Chunk of arg trees ───────────────────
    // Given: an Annotation built with the args expanded from Tree.Apply(_, Chunk(Literal("hi")))
    // When: a.arguments
    // Then: returns Chunk(Literal("hi"))
    "Leaf 164: arguments is the decoded arg chunk" in run {
        val tpe    = Tasty.Type.Named(SymbolId(0))
        val lit    = Tasty.Tree.Literal(Tasty.Constant.StringConst("hi"))
        val ann    = Tasty.Annotation(tpe, Chunk(lit))
        val result = ann.arguments
        assert(result.length == 1, s"Expected 1 arg, got ${result.length}")
        assert(result(0).isInstanceOf[Tasty.Tree.Literal], s"Expected Literal, got ${result(0).getClass.getSimpleName}")
        succeed
    }

    // Additional coverage: a single non-Apply tree appears as a single-element Chunk
    "Leaf 164b: arguments holds a single-element Chunk for a non-Apply tree" in run {
        val tpe    = Tasty.Type.Named(SymbolId(0))
        val ident  = Tasty.Tree.Ident(Tasty.Name.Unsafe.init("x"), tpe)
        val ann    = Tasty.Annotation(tpe, Chunk(ident))
        val result = ann.arguments
        assert(result.length == 1, s"Expected 1 element for a single-tree annotation, got ${result.length}")
        succeed
    }

    // Additional coverage: an annotation with no arg trees has an empty arguments Chunk
    "Leaf 164c: arguments is empty when the annotation carries no arg trees" in run {
        val tpe = Tasty.Type.Named(SymbolId(0))
        val ann = Tasty.Annotation(tpe, Chunk.empty)
        assert(ann.arguments.isEmpty, "arguments must be empty when no arg trees were decoded")
        succeed
    }

end AnnotationArgListTest
