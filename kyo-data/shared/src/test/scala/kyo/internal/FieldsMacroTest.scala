package kyo.internal

import kyo.~
import kyo.Fields
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class FieldsMacroTest extends AnyFreeSpec with Matchers:

    "infers structural types properly" - {
        "for a simple case" in {
            // summon[(("age" ~ Int) & ("age" ~ Boolean)) =:= ("age" ~ (Int | Boolean))]
            Fields.derive[("name" ~ String) & ("age" ~ Int) & ("age" ~ Boolean)]: Fields.Aux[
                ("name" ~ String) & ("age" ~ Int) & ("age" ~ Boolean),
                ("name" ~ String) *: ("age" ~ Int) *: ("age" ~ Boolean) *: EmptyTuple,
                Fields.Structural & { def name: String; def age: Int | Boolean }
            ]
        }
    }

end FieldsMacroTest
