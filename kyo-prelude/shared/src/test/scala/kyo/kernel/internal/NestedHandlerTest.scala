package kyo.kernel.internal

import kyo.*

class NestedHandlerTest extends Test:
    "bug #1412 - handler on Nested" - {
        "Var.update through lift/flatten/run" in {
            var executed = false

            val comp: Unit < Var[Int] = Var.update[Int] { x =>
                executed = true
                x + 1
            }.unit

            val lifted: Unit < Var[Int] < Any = Nested(comp) // Kyo.lift(comp)

            def flatten[A, B, C](v: A < B < C): A < (B & C) = v.map(a => a)

            val step1 = Var.run(0)(lifted)
            val step2 = flatten(step1)
            val step3 = Var.run(0)(step2)

            step3.eval
            assert(executed, "Var.update body should have been executed")
        }

        "details" in {
            given [A, B]: CanEqual[A, B] = CanEqual.derived

            val update: Int < Var[Int]        = Var.update[Int](_ + 1)
            val nested: Int < Var[Int] < Any  = Nested(update)
            val handled: Int < Var[Int] < Any = Var.run(0)(nested)

            assert(handled == nested, "Var.run should return the nested computation")
        }
    }

end NestedHandlerTest
