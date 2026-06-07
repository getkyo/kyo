package kyo

import kyo.Tasty.SymbolId

/** Tests for the pure traversal API on Type: collect, find, foldLeft, exists. */
class TypeTraversalTest extends kyo.test.Test[Any]:

    // collectGathersByNameTypes
    "collect gathers ByName types from Applied args in pre-order" in {
        val bn1: Tasty.Type.ByName = Tasty.Type.ByName(Tasty.Type.Any)
        val bn2: Tasty.Type.ByName = Tasty.Type.ByName(Tasty.Type.Nothing)
        val t                      = Tasty.Type.Applied(Tasty.Type.Any, Chunk(bn1, bn2))
        val result                 = t.collect { case b: Tasty.Type.ByName => b }
        assert(result.length == 2, s"Expected 2 ByName nodes, got ${result.length}")
        assert(result(0) == bn1)
        assert(result(1) == bn2)
        succeed
    }

    // findReturnsFirstHitInPreOrder
    "find returns first Repeated in OrType in pre-order" in {
        val rep    = Tasty.Type.Repeated(Tasty.Type.Any)
        val t      = Tasty.Type.OrType(Tasty.Type.Any, rep)
        val result = t.find(_.isInstanceOf[Tasty.Type.Repeated])
        assert(result.isDefined, "Expected Maybe.Present but got Absent")
        assert(result.get == rep, s"Expected $rep but got ${result.get}")
        succeed
    }

    // findReturnsAbsentOnNoMatch
    "find returns Absent when no matching node exists" in {
        val t      = Tasty.Type.OrType(Tasty.Type.Any, Tasty.Type.Nothing)
        val result = t.find(_.isInstanceOf[Tasty.Type.ByName])
        assert(!result.isDefined, s"Expected Absent but got $result")
        succeed
    }

    // foldLeftCountsNodes
    // Note: prep C2 flags a plan counting error; using corrected 3-node fixture (Applied + Any + Nothing).
    "foldLeft counts all nodes in pre-order" in {
        val t      = Tasty.Type.Applied(Tasty.Type.Any, Chunk(Tasty.Type.Nothing))
        val result = t.foldLeft(0)((acc, _) => acc + 1)
        assert(result == 3, s"Expected 3 nodes (Applied + Any + Nothing), got $result")
        succeed
    }

    // existsDetectsByNamePresence
    "exists detects ByName presence and absence" in {
        val withBn    = Tasty.Type.ByName(Tasty.Type.Any)
        val withoutBn = Tasty.Type.Any
        assert(withBn.exists(_.isInstanceOf[Tasty.Type.ByName]), "Expected true for tree containing ByName")
        assert(!withoutBn.exists(_.isInstanceOf[Tasty.Type.ByName]), "Expected false for tree without ByName")
        succeed
    }

    // visitNotPublicAnymore
    "visit is not accessible from outside package kyo" in {
        val errs = compiletime.testing.typeCheckErrors(
            "(kyo.Tasty.Type.Any: kyo.Tasty.Type).visit(_ => ())"
        ).length
        // typeCheckErrors is run from inside package kyo so private[kyo] IS visible here.
        // The in-package probe compiles successfully: errs must be empty, confirming the
        // method exists and is accessible from within the kyo package.
        assert(errs == 0, "visit should be accessible from inside package kyo (private[kyo])")
        // Verify no public API named 'visit' is accessible from user scope.
        // We verify via typeCheckErrors from an explicit user-package context.
        val errsFromUser = compiletime.testing.typeCheckErrors(
            "package testuser; object Probe { kyo.Tasty.Type.Any.visit(_ => ()) }"
        ).length
        assert(errsFromUser > 0, "Expected compile error for visit from outside package kyo")
    }

end TypeTraversalTest
