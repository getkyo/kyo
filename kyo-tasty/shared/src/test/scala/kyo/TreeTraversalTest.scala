package kyo

import AllowUnsafe.embrace.danger

/** Tests for Tree traversal members .
  *
  * Leaf id:12. Pins: INV-005 (Tree traversal returns plain data).
  */
class TreeTraversalTest extends Test:

    // Leaf id:12 -- Tree.children covers all direct children
    "Ident has empty children" in {
        val n = Tasty.Name("x")
        import AllowUnsafe.embrace.danger
        val tpe = Tasty.Type.Named(kyo.Tasty.SymbolId(-1))
        val t   = Tasty.Tree.Ident(n, tpe)
        assert(t.children.isEmpty)
    }

    "Apply has fun and args as children" in {
        import AllowUnsafe.embrace.danger
        val tpe  = Tasty.Type.Named(kyo.Tasty.SymbolId(-1))
        val fun  = Tasty.Tree.Ident(Tasty.Name("f"), tpe)
        val arg1 = Tasty.Tree.Literal(Tasty.Constant.IntConst(1))
        val arg2 = Tasty.Tree.Literal(Tasty.Constant.IntConst(2))
        val app  = Tasty.Tree.Apply(fun, Chunk(arg1, arg2))
        assert(app.children.size == 3)
        // Use .equals (no CanEqual for Tree in scope)
        assert(app.children(0).equals(fun))
        assert(app.children(1).equals(arg1))
        assert(app.children(2).equals(arg2))
    }

    "Block children are stats :+ expr" in {
        val lit1 = Tasty.Tree.Literal(Tasty.Constant.IntConst(1))
        val lit2 = Tasty.Tree.Literal(Tasty.Constant.IntConst(2))
        val lit3 = Tasty.Tree.Literal(Tasty.Constant.IntConst(3))
        val b    = Tasty.Tree.Block(Chunk(lit1, lit2), lit3)
        assert(b.children.size == 3)
        assert(b.children(2).equals(lit3))
    }

    "CaseDef with guard includes guard in children" in {
        import AllowUnsafe.embrace.danger
        val tpe     = Tasty.Type.Named(kyo.Tasty.SymbolId(-1))
        val pat     = Tasty.Tree.Ident(Tasty.Name("x"), tpe)
        val guard   = Tasty.Tree.Literal(Tasty.Constant.BooleanConst(true))
        val body    = Tasty.Tree.Literal(Tasty.Constant.IntConst(0))
        val caseDef = Tasty.Tree.CaseDef(pat, Maybe(guard), body)
        val ch      = caseDef.children
        assert(ch.size == 3, s"Expected 3 children, got ${ch.size}")
        assert(ch.exists(_.equals(guard)), "guard missing from children")
    }

    "CaseDef without guard has 2 children" in {
        import AllowUnsafe.embrace.danger
        val tpe     = Tasty.Type.Named(kyo.Tasty.SymbolId(-1))
        val pat     = Tasty.Tree.Ident(Tasty.Name("x"), tpe)
        val body    = Tasty.Tree.Literal(Tasty.Constant.IntConst(0))
        val caseDef = Tasty.Tree.CaseDef(pat, Maybe.Absent, body)
        assert(caseDef.children.size == 2)
    }

    "foreach visits all nodes in pre-order" in {
        val lit1    = Tasty.Tree.Literal(Tasty.Constant.IntConst(1))
        val lit2    = Tasty.Tree.Literal(Tasty.Constant.IntConst(2))
        val block   = Tasty.Tree.Block(Chunk(lit1), lit2)
        val outer   = Tasty.Tree.Block(Chunk(block), lit2)
        val visited = scala.collection.mutable.ArrayBuffer.empty[Tasty.Tree]
        outer.foreach(t => visited += t)
        // pre-order: outer, block, lit1, lit2, lit2
        assert(visited.size == 5, s"Expected 5 visits, got ${visited.size}")
        assert(visited(0).equals(outer), "first visit should be outer")
        assert(visited(1).equals(block), "second visit should be block")
    }

    "collect returns matching nodes" in {
        val lit1  = Tasty.Tree.Literal(Tasty.Constant.IntConst(1))
        val lit2  = Tasty.Tree.Literal(Tasty.Constant.StringConst("s"))
        val block = Tasty.Tree.Block(Chunk(lit1, lit2), lit1)
        val ints  = block.collect { case Tasty.Tree.Literal(Tasty.Constant.IntConst(i)) => i }
        assert(ints.toSet == Set(1), s"Expected Set(1), got $ints")
    }

    "find returns first matching node" in {
        val lit1  = Tasty.Tree.Literal(Tasty.Constant.IntConst(1))
        val lit2  = Tasty.Tree.Literal(Tasty.Constant.IntConst(2))
        val block = Tasty.Tree.Block(Chunk(lit1, lit2), lit2)
        val found = block.find {
            case Tasty.Tree.Literal(Tasty.Constant.IntConst(2)) => true
            case _                                              => false
        }
        // Maybe.Present wraps the found node; use get to compare via equals
        assert(found.isDefined, "Expected a node to be found")
        assert(found.get.equals(lit2), s"Expected lit2, got ${found.get}")
    }

    "find returns Absent when no node matches" in {
        val lit1  = Tasty.Tree.Literal(Tasty.Constant.IntConst(1))
        val block = Tasty.Tree.Block(Chunk(lit1), lit1)
        val found = block.find {
            case Tasty.Tree.Literal(Tasty.Constant.StringConst(_)) => true
            case _                                                 => false
        }
        assert(found == Maybe.Absent)
    }

    "foldLeft accumulates values" in {
        val lit1  = Tasty.Tree.Literal(Tasty.Constant.IntConst(1))
        val lit2  = Tasty.Tree.Literal(Tasty.Constant.IntConst(2))
        val block = Tasty.Tree.Block(Chunk(lit1), lit2)
        val sum = block.foldLeft(0) {
            case (acc, Tasty.Tree.Literal(Tasty.Constant.IntConst(i))) => acc + i
            case (acc, _)                                              => acc
        }
        assert(sum == 3, s"Expected 3, got $sum")
    }

    // visit and foreach are private[kyo]: accessible within package kyo, blocked from user code.
    "visit is not accessible from outside package kyo" in {
        // From inside package kyo, private[kyo] is accessible.
        val errs = compiletime.testing.typeCheckErrors(
            "(kyo.Tasty.Tree.Literal(kyo.Tasty.Constant.IntConst(1)): kyo.Tasty.Tree).visit(_ => ())"
        )
        assert(errs.isEmpty, "visit should be accessible from inside package kyo (private[kyo])")
        // From a user package, visit must not be accessible.
        val errsFromUser = compiletime.testing.typeCheckErrors(
            "package testuser; object Probe { kyo.Tasty.Tree.Literal(kyo.Tasty.Constant.IntConst(1)).visit(_ => ()) }"
        )
        assert(errsFromUser.nonEmpty, "Expected compile error for visit from outside package kyo")
    }

    "foreach is not accessible from outside package kyo" in {
        val errs = compiletime.testing.typeCheckErrors(
            "(kyo.Tasty.Tree.Literal(kyo.Tasty.Constant.IntConst(1)): kyo.Tasty.Tree).foreach(_ => ())"
        )
        assert(errs.isEmpty, "foreach should be accessible from inside package kyo (private[kyo])")
        val errsFromUser = compiletime.testing.typeCheckErrors(
            "package testuser; object Probe { kyo.Tasty.Tree.Literal(kyo.Tasty.Constant.IntConst(1)).foreach(_ => ()) }"
        )
        assert(errsFromUser.nonEmpty, "Expected compile error for foreach from outside package kyo")
    }

end TreeTraversalTest
