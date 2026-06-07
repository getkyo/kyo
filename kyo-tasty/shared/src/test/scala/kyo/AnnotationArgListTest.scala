package kyo

import AllowUnsafe.embrace.danger
import kyo.Tasty.SymbolId

/** Tests for Annotation.arguments field access and correctness. */
class AnnotationArgListTest extends kyo.test.Test[Any]:

    "arguments is the decoded arg chunk" in {
        val tpe    = Tasty.Type.Named(SymbolId(0))
        val lit    = Tasty.Tree.Literal(Tasty.Constant.StringConst("hi"))
        val ann    = Tasty.Annotation(tpe, Chunk(lit))
        val result = ann.arguments
        assert(result.length == 1, s"Expected 1 arg, got ${result.length}")
        result(0) match
            case Tasty.Tree.Literal(Tasty.Constant.StringConst(s)) =>
                assert(s == "hi", s"""Expected "hi" but got "$s"""")
            case other =>
                fail(s"Expected Tree.Literal(StringConst(\"hi\")) but got $other")
        end match
        succeed
    }

    // Additional coverage: a single non-Apply tree appears as a single-element Chunk
    "arguments holds a single-element Chunk for a non-Apply tree" in {
        val tpe    = Tasty.Type.Named(SymbolId(0))
        val ident  = Tasty.Tree.Ident(Tasty.Name("x"), tpe)
        val ann    = Tasty.Annotation(tpe, Chunk(ident))
        val result = ann.arguments
        assert(result.length == 1, s"Expected 1 element for a single-tree annotation, got ${result.length}")
        succeed
    }

    // Additional coverage: an annotation with no arg trees has an empty arguments Chunk
    "arguments is empty when the annotation carries no arg trees" in {
        val tpe = Tasty.Type.Named(SymbolId(0))
        val ann = Tasty.Annotation(tpe, Chunk.empty)
        assert(ann.arguments.isEmpty, "arguments must be empty when no arg trees were decoded")
        succeed
    }

end AnnotationArgListTest
