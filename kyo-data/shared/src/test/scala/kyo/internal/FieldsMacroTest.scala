package kyo.internal

import kyo.~
import kyo.Fields
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class FieldsMacroTest extends AnyFreeSpec with Matchers:

    "infers structural types properly" - {
        "for a simple case" in {
            Fields.derive[("name" ~ String) & ("age" ~ Int)]: Fields.Aux[
                ("name" ~ String) & ("age" ~ Int),
                ("name" ~ String) *: ("age" ~ Int) *: EmptyTuple,
                Fields.Structural & { def name: String; def age: Int }
            ]
        }
    }

end FieldsMacroTest
