package kyo.lang

import kyo.*
import kyolang.*

/** The construct inventory's Allow rows that the language supports today, one case per row, over the `Inventory.kyo` fixture: the exact
  * exported signature and the runtime behavior under ordinary handlers. The rows a `.kyo` body cannot use yet are pinned as rejections
  * in `KyoLangPluginInventoryTest`.
  */
class BodyInventoryTest extends kyo.test.Test[Any]:

    private def run[A](value: A < Var[Int], initial: Int = 1): A = Var.run(initial)(value).eval

    "L01 backticked identifiers" in {
        Signatures.exact[Int < Var[Int]]("kyolang.Inventory", "backticked name")
        assert(run(Inventory.`backticked name`) == 2)
    }
    "L03 and E16 literals in tuples" in {
        Signatures.exact[(Int, String, Boolean)]("kyolang.Inventory", "literals")
        Signatures.exact[(Long, Double, Char)]("kyolang.Inventory", "numeric")
        Signatures.exact[Unit]("kyolang.Inventory", "unit")
        assert(Inventory.literals == (1, "s", true))
        assert(Inventory.numeric == (2L, 1.5, 'c'))
    }
    "L04 string interpolation" in {
        Signatures.exact[String < Var[Int]]("kyolang.Inventory", "interpolated")
        assert(run(Inventory.interpolated, 7) == "value 7")
    }
    "L09 rooted qualified paths" in {
        Signatures.exact[Int < Var[Int]]("kyolang.Inventory", "qualified")
        assert(run(Inventory.qualified) == 43)
    }
    "T01 generic methods" in {
        Signatures.exact[Int < Var[Int]]("kyolang.Inventory", "applied")
        assert(Inventory.generic(5) == 5)
        assert(run(Inventory.applied, 3) == 3)
    }
    "T02 type aliases" in {
        Signatures.exact[Int < Var[Int]]("kyolang.Inventory", "aliased")
        assert(run(Inventory.aliased, 4) == 4)
    }
    "T05 function-typed parameters" in {
        Signatures.exact[Int < Var[Int]]("kyolang.Inventory", "twiceApplied")
        assert(run(Inventory.twiceApplied, 1) == 3)
    }
    "D02 irrefutable destructuring" in {
        Signatures.exact[Int < (Var[Int] & Var[String])]("kyolang.Inventory", "destructured")
        assert(Var.run("abc")(Var.run(2)(Inventory.destructured)).eval == 5)
    }
    "D05 pattern bindings as fields" in {
        Signatures.exact[Int < Var[Int]]("kyolang.Inventory", "area")
        val width: Int = Inventory.width
        assert(width == 3)
        assert(Inventory.width == 3 && Inventory.height == 4)
        assert(run(Inventory.area, 1) == 13)
        assert(Across.crossPattern == 7)
    }
    "D12 pure default arguments" in {
        Signatures.exact[Int < Var[Int]]("kyolang.Inventory", "defaulted")
        assert(run(Inventory.defaulted, 5) == 10)
        assert(Inventory.scaled(5, 3) == 15)
        assert(Across.crossDefault == 4)
    }
    "M03 constant inline values" in {
        Signatures.exact[Int < Var[Int]]("kyolang.Inventory", "limited")
        assert(run(Inventory.limited, 15) == 10)
        assert(run(Inventory.limited, 5) == 5)
        assert(Across.crossInline == 10)
    }
    "L08 export forwarders" in {
        Signatures.exact[Int < Var[Int]]("kyolang.Inventory", "forwarded")
        assert(run(Inventory.forwarded, -4) == 42)
        assert(run(Inventory.forwarded, 4) == 46)
        assert(Across.crossExport == 42)
    }
    "D08 pure local methods" in {
        Signatures.exact[Int < Var[Int]]("kyolang.Inventory", "localMethod")
        assert(run(Inventory.localMethod, 1) == 2)
    }
    "D10 clauses, named arguments, repeated parameters" in {
        Signatures.exact[Int < Var[Int]]("kyolang.Inventory", "multiClause")
        Signatures.exact[Int < Var[Int]]("kyolang.Inventory", "named")
        Signatures.exact[Int < Var[Int]]("kyolang.Inventory", "repeated")
        assert(run(Inventory.multiClause, 1) == 3)
        assert(run(Inventory.named, 2) == 12)
        assert(run(Inventory.repeated, 9) == 9)
    }
    "D11 pure by-name arguments stay delayed" in {
        Signatures.exact[Int < Var[Int]]("kyolang.Inventory", "byName")
        assert(run(Inventory.byName, 1) == 4)
    }
    "D14 recursion" in {
        Signatures.exact[Int < Var[Int]]("kyolang.Inventory", "countdown")
        assert(run(Inventory.countdown(3), 8) == 8)
    }
    "E03 prefix operators" in {
        Signatures.exact[Int < Var[Int]]("kyolang.Inventory", "negated")
        assert(run(Inventory.negated, 2) == -2)
    }
    "E04 type ascription" in {
        Signatures.exact[Int < Var[Int]]("kyolang.Inventory", "ascribed")
        assert(run(Inventory.ascribed, 2) == 3)
    }
    "E05 boolean operators short-circuit" in {
        Signatures.exact[Boolean < (Var[Int] & Var[String])]("kyolang.Inventory", "conjunction")
        Signatures.exact[Boolean < (Var[Int] & Var[String])]("kyolang.Inventory", "disjunction")
        assert(Var.run("")(Var.run(1)(Inventory.conjunction)).eval == false)
        assert(Var.run("x")(Var.run(1)(Inventory.conjunction)).eval == true)
        assert(Var.run("")(Var.run(1)(Inventory.disjunction)).eval == true)
        assert(Var.run("")(Var.run(0)(Inventory.disjunction)).eval == false)
    }
    "E06 match with literal, alternative, guarded, and binder patterns" in {
        Signatures.exact[Int < (Var[Int] & Var[String])]("kyolang.Inventory", "matched")
        def matched(n: Int) = Var.run("abc")(Var.run(n)(Inventory.matched)).eval
        assert(matched(0) == 3)
        assert(matched(2) == 10)
        assert(matched(51) == -51)
        assert(matched(7) == 7)
    }
    "P07 effectful guards" in {
        Signatures.exact[Int < Var[Int]]("kyolang.Inventory", "guarded")
        assert(run(Inventory.guarded(7), 5) == 7)
        assert(run(Inventory.guarded(5), 5) == 0)
        assert(run(Inventory.guarded(1), 5) == -1)
    }
    "E07 for comprehensions" in {
        Signatures.exact[List[Int] < Var[Int]]("kyolang.Inventory", "comprehension")
        Signatures.exact[Unit < Var[Int]]("kyolang.Inventory", "traversal")
        assert(run(Inventory.comprehension, 2) == List(2, 6))
        assert(Var.runTuple(0)(Inventory.traversal).eval == (3, ()))
    }
    "E08 while loops" in {
        Signatures.exact[Int < Var[Int]]("kyolang.Inventory", "loop")
        assert(Var.runTuple(0)(Inventory.loop).eval == (3, 3))
    }
    "E13 stored pure closures" in {
        Signatures.exact[Int < Var[Int]]("kyolang.Inventory", "stored")
        assert(run(Inventory.stored, 1) == 2)
    }
    "E14 case lambdas as total functions" in {
        Signatures.exact[List[Int]]("kyolang.Inventory", "caseLambda")
        assert(Inventory.caseLambda == List(3, 7))
    }
    "E17 constructor applications" in {
        Signatures.exact[Some[Int] < Var[Int]]("kyolang.Inventory", "constructed")
        assert(run(Inventory.constructed, 4) == Some(4))
    }
    "P03 and P04 constructor, tuple, and typed patterns" in {
        Signatures.exact[Int < Var[Int]]("kyolang.Inventory", "optional")
        Signatures.exact[Int]("kyolang.Inventory", "paired")
        Signatures.exact[Int < Var[Int]]("kyolang.Inventory", "typed")
        assert(run(Inventory.optional(Some(1)), 2) == 3)
        assert(run(Inventory.optional(None), 2) == 0)
        assert(Inventory.paired((1, 2)) == 3)
        assert(run(Inventory.typed(5), 2) == 5)
        assert(run(Inventory.typed("x"), 2) == 2)
    }
    "P05 pure custom extractors" in {
        Signatures.exact[Int < Var[Int]]("kyolang.Inventory", "extracted")
        assert(run(Inventory.extracted(2), 3) == 5)
        assert(run(Inventory.extracted(-2), 3) == 0)
    }
    "P09 refutable for-case generators" in {
        Signatures.exact[List[Int] < Var[Int]]("kyolang.Inventory", "collected")
        assert(run(Inventory.collected, 10) == List(11, 13))
    }
    "C01 using clauses" in {
        Signatures.exact[Int < Var[Int]]("kyolang.Inventory", "contextualScaled")
        assert(run(Inventory.contextualScaled, 2) == 6)
    }
    "C05 context functions" in {
        Signatures.exact[Int < Var[Int]]("kyolang.Inventory", "contextFunction")
        assert(run(Inventory.contextFunction, 2) == 3)
    }
    "T13 named tuples" in {
        Signatures.exact[(count: Int, label: String) < Var[Int]]("kyolang.Inventory", "namedTuple")
        val named = run(Inventory.namedTuple, 2)
        assert(named.count == 2)
        assert(named.label == "n")
    }
    "O09 this inside an object body" in {
        Signatures.exact[Int < Var[Int]]("kyolang.Inventory", "self")
        assert(run(Inventory.self, 1) == 2)
    }
    "P10 given patterns" in {
        Signatures.exact[Int < Var[Int]]("kyolang.Inventory", "evidenced")
        assert(run(Inventory.evidenced(5), 1) == 6)
        assert(run(Inventory.evidenced("x"), 1) == 0)
    }
    "O10 private members" in {
        Signatures.exact[Int < Var[Int]]("kyolang.Inventory", "usesHelper")
        assert(run(Inventory.usesHelper, 1) == 2)
    }
    "C04 extension methods" in {
        Signatures.exact[Int < Var[Int]]("kyolang.Inventory", "extended")
        assert(run(Inventory.extended, 2) == 7)
    }
    "C08 strict equality" in {
        Signatures.exact[Boolean < Var[Int]]("kyolang.Inventory", "equal")
        assert(run(Inventory.equal, 1) == true)
        assert(run(Inventory.equal, 2) == false)
    }
    "M06 diagnostic annotations" in {
        Signatures.exact[Int < Var[Int]]("kyolang.Inventory", "counted")
        assert(run(Inventory.counted, 4) == 4)
    }
end BodyInventoryTest
