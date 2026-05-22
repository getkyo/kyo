package kyo.internal

import kyo.~
import kyo.Fields
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class FieldsMacroTest extends AnyFreeSpec with Matchers:

    "infers tuple type via Fields.derive" - {
        "for a simple case" in {
            Fields.derive[("name" ~ String) & ("age" ~ Int) & ("age" ~ Boolean)]: Fields.Aux[
                ("name" ~ String) & ("age" ~ Int) & ("age" ~ Boolean),
                ("name" ~ String) *: ("age" ~ Int) *: ("age" ~ Boolean) *: EmptyTuple,
                Fields.Structural
            ]
        }
    }

    "infers full structural refinement via Fields.Structure.derive" - {
        // Fields.derive intentionally uses the bare Fields.Structural for its `Struct` member — the per-field
        // refinement is built only by Fields.Structure.derive, which is summoned exactly where it's needed (the
        // implicit `Record[F] => S` conversion). See FieldsMacros.deriveImpl for the rationale.
        "for a simple case" in {
            summon[Fields.Structure[("name" ~ String) & ("age" ~ Int) & ("age" ~ Boolean)]]: Fields.Structure.Aux[
                ("name" ~ String) & ("age" ~ Int) & ("age" ~ Boolean),
                Fields.Structural & Any { def name: String; def age: Int | Boolean }
            ]
        }
    }

end FieldsMacroTest
