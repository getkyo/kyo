package kyo.lang

import kyo.*
import kyolang.*

/** Exercises the `.kyo` fixtures that this module's test configuration compiles through the plugin.
  *
  * The fixtures live in `shared/src/test/kyo`. Each exported signature is checked for exact type equivalence, and each program is run
  * through ordinary Kyo handlers from this ordinary Scala suite, which is the interoperability the language promises.
  */
class BodyTest extends kyo.test.Test[Any]:

    "exported signatures" - {
        "pure definitions keep ordinary types" in {
            Signatures.exact[Int]("kyolang.Programs", "pure")
            Signatures.exact[Int]("kyolang.Programs", "pair")
            succeed("checked at compile time")
        }
        "inferred and annotated single effects" in {
            Signatures.exact[Int < Var[Int]]("kyolang.Programs", "repeated")
            Signatures.exact[Int < Var[Int]]("kyolang.Programs", "annotated")
            Signatures.exact[Int < Abort[String]]("kyolang.Programs", "unusedFailure")
            Signatures.exact[Int < Var[String]]("kyolang.Programs", "length")
            succeed("checked at compile time")
        }
        "inferred and annotated effect rows" in {
            Signatures.exact[Int < (Var[Int] & Abort[String])]("kyolang.Programs", "mixed")
            Signatures.exact[Int < (Var[Int] & Abort[String])]("kyolang.Programs", "annotatedMixed")
            Signatures.exact[Int < (Var[Int] & Var[String])]("kyolang.Programs", "twoVars")
            Signatures.exact[Int < (Var[Int] & Var[String])]("kyolang.Programs", "effectfulBranch")
            Signatures.exact[List[Int] < Var[Int]]("kyolang.Programs", "generic")
            succeed("checked at compile time")
        }
        "definitions in another .kyo file keep the rows they compose" in {
            Signatures.exact[Int < Var[Int]]("kyolang.More", "repeated")
            Signatures.exact[Int < (Var[Int] & Abort[String])]("kyolang.More", "mixed")
            succeed("checked at compile time")
        }
    }

    "bindings" - {
        "an effectful initializer binds once and both references reuse the result" in {
            val typed: Int < Var[Int] = Programs.repeated
            assert(Var.runTuple(0)(typed).eval == (1, 2))
        }
        "a discarded result still executes its effect" in {
            assert(Var.runTuple(0)(Programs.discarded).eval == (1, 42))
        }
        "an unused failure still aborts" in {
            assert(Abort.run(Programs.unusedFailure).eval == Result.fail("failure"))
        }
        "a pure field and a pure method keep ordinary values" in {
            assert(Programs.constant == 42)
            assert(Programs.fromConstant == 43)
            assert(Programs.pure == 42)
        }
    }

    "control flow" - {
        "only the selected branch runs" in {
            assert(Var.runTuple(0)(Programs.branch).eval == (1, 1))
            assert(Var.runTuple(2)(Programs.branch).eval == (12, 12))
        }
        "arguments evaluate in source order" in {
            assert(Var.runTuple(0)(Programs.ordered).eval == (2, 12))
        }
        "effects inside a generic application" in {
            assert(Var.runTuple(0)(Programs.generic).eval == (1, List(1)))
        }
        "a method on the exposed result" in {
            assert(Var.run("hello")(Programs.length).eval == 5)
        }
        "branches with different effects contribute to one row" in {
            val branch: Boolean => (Int < (Var[Int] & Var[String])) = Programs.effectfulBranch
            assert(Var.run("abc")(Var.run(2)(branch(true))).eval == 2)
            assert(Var.run("abc")(Var.run(2)(branch(false))).eval == 3)
        }
    }

    "pure higher-order calls" - {
        "a pure callback keeps the ordinary collection operation" in {
            assert(Programs.mapped == List(2))
        }
        "a pure callback on an exposed result" in {
            assert(Var.run(List(1, 2))(Programs.mappedPending).eval == List(2, 3))
        }
    }

    "effect rows" - {
        "an annotated row constrains the body" in {
            assert(Var.run(4)(Programs.annotated).eval == 5)
        }
        "mixed effects handle in either order" in {
            assert(Abort.run(Var.run(0)(Programs.mixed)).eval == Result.succeed(1))
            assert(Abort.run(Var.run(1)(Programs.mixed)).eval == Result.fail("failure"))
            assert(Var.run(0)(Abort.run(Programs.annotatedMixed)).eval == Result.succeed(1))
        }
        "two instances of one effect" in {
            assert(Var.run("abc")(Var.run(2)(Programs.twoVars)).eval == 5)
        }
        "an ordinary Scala caller composes the result" in {
            assert(Abort.run(Var.run(0)(Programs.mixed.map(_ + 10))).eval == Result.succeed(11))
        }
    }

    "definitions calling definitions" - {
        "a nested object" in {
            assert(Var.run(0)(Programs.Nested.repeated).eval == 3)
        }
        "a top-level definition" in {
            assert(Var.run(0)(topLevel).eval == 4)
        }
        "another .kyo file" in {
            assert(Var.run(0)(More.repeated).eval == 5)
            assert(Abort.run(Var.run(0)(More.mixed)).eval == Result.succeed(2))
        }
    }
end BodyTest
