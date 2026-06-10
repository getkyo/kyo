package kyo.test.prop.internal

import org.scalatest.NonImplicitAssertions
import org.scalatest.funsuite.AnyFunSuite

/** Tests for Tree.zipWith applicative combine. All assertions are pure synchronous; uses ScalaTest directly. */
class TreeZipWithTest extends AnyFunSuite with NonImplicitAssertions:

    test("zipWith combines root values with f") {
        val ta     = Tree.leaf(2)
        val tb     = Tree.leaf(3)
        val result = ta.zipWith(tb)((a, b) => a + b)
        assert(result.value == 5, s"Expected root value 5, got ${result.value}")
    }

    test("zipWith interleaves both subtrees' shrinks one component at a time") {
        val ta       = Tree.unfold(2)(n => if n > 0 then Iterable(n - 1) else Nil)
        val tb       = Tree.unfold(1)(n => if n > 0 then Iterable(n - 1) else Nil)
        val combined = ta.zipWith(tb)((a, b) => (a, b))
        val children = combined.shrinks().map(_.value).toList
        // this-shrinks first (ta shrinks from 2 to 1, tb fixed at 1 -> (1,1))
        // then that-shrinks (ta fixed at 2, tb shrinks from 1 to 0 -> (2,0))
        assert(children == List((1, 1), (2, 0)), s"Expected List((1,1),(2,0)), got $children")
    }

    test("zipWith of two leaves has no shrinks") {
        val result  = Tree.leaf("a").zipWith(Tree.leaf("b"))((x, y) => (x, y))
        val shrinks = result.shrinks().toList
        assert(shrinks.isEmpty, s"Expected empty shrinks list for two leaves, got $shrinks")
    }

    test("zipWith is deterministic and pure over already-sampled trees") {
        def ta        = Tree.unfold(2)(n => if n > 0 then Iterable(n - 1) else Nil)
        def tb        = Tree.unfold(1)(n => if n > 0 then Iterable(n - 1) else Nil)
        val combined1 = ta.zipWith(tb)((a, b) => (a, b))
        val combined2 = ta.zipWith(tb)((a, b) => (a, b))
        val values1   = combined1.shrinks().map(_.value).take(5).toList
        val values2   = combined2.shrinks().map(_.value).take(5).toList
        assert(values1 == values2, s"Expected equal child value tuples across two evaluations: $values1 vs $values2")
    }

end TreeZipWithTest
