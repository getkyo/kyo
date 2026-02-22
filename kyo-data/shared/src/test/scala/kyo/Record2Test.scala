package kyo

import Record2.*
import scala.language.implicitConversions

class Record2Test extends Test:

    // R1: Duplicate fields merge to union at the type level
    "R1: duplicate fields =:= union" in {
        summon[("f" ~ Int & "f" ~ String) =:= ("f" ~ (Int | String))]
        succeed
    }

    // R2: Field access with type inference (no ascription)
    "R2: field access with inference" in {
        val r = ("name" ~ "Alice") & ("age" ~ 30)
        assert(r.name.length == 5)
        assert(r.age + 1 == 31)
    }

    // R3: Structural subtyping — more fields assignable where fewer expected
    "R3: structural subtyping assignment" in {
        val full                               = ("name" ~ "Alice") & ("age" ~ 30)
        val nameOnly: Record2["name" ~ String] = full
        assert(nameOnly.name == "Alice")
    }

    // R4: Structural subtyping — function parameters
    "R4: structural subtyping function param" in {
        def getName(r: Record2["name" ~ String]): String = r.name
        val r                                            = ("name" ~ "Alice") & ("age" ~ 30)
        assert(getName(r) == "Alice")
    }

    // R5: Structural subtyping — type bounds
    "R5: structural subtyping type bound" in {
        def getName[F <: "name" ~ String](r: Record2[F]): String = r.name
        val r                                                    = ("name" ~ "Alice") & ("age" ~ 30)
        assert(getName(r) == "Alice")
    }

    // R6: Type-level — Record2 with merged fields assignable to union type
    "R6: Record2 merged fields assignable to union" in {
        val r: Record2["f" ~ Int & "f" ~ String] = ("f" ~ 42) & ("f" ~ "hello")
        val r2: Record2["f" ~ (Int | String)]    = r
        succeed
    }

    // R7: Type-level — <:< evidence for field subtyping
    "R7: <:< more fields subtype of fewer" in {
        summon[("a" ~ Int & "b" ~ String) <:< ("a" ~ Int)]
        succeed
    }

    // R8: Type-level — Record2 subtyping via Conversion
    "R8: Record2 subtype evidence" in {
        def takes(r: Record2["name" ~ String]): String = r.name
        val r                                          = ("name" ~ "Alice") & ("age" ~ 30)
        assert(takes(r) == "Alice")
    }

    // R9: Type-level — nested type bounds propagate
    "R9: nested type bound" in {
        def process[F <: "name" ~ String & "age" ~ Int](r: Record2[F]): (String, Int) =
            (r.name, r.age)
        val r = ("name" ~ "Alice") & ("age" ~ 30)
        assert(process(r) == ("Alice", 30))
    }

    // R10: Type-level — return type preserves fields through generic code
    "R10: generic passthrough preserves type" in {
        def passthrough[F <: "name" ~ String](r: Record2[F]): Record2[F] = r
        val r                                                            = ("name" ~ "Alice") & ("age" ~ 30)
        val r2                                                           = passthrough(r)
        assert(r2.name == "Alice")
        assert(r2.age == 30)
    }

    // R11: Mixed duplicates + unique fields
    "R11: mixed duplicate and unique" in {
        summon[("a" ~ Int & "a" ~ String & "b" ~ Boolean) =:= ("a" ~ (Int | String) & "b" ~ Boolean)]
        succeed
    }

    // R12: update existing field (type-safe)
    "R12: update existing field" in {
        val r  = ("name" ~ "Alice") & ("age" ~ 30)
        val r2 = r.update("name", "Bob")
        assert(r2.name == "Bob")
    }

    // R13: update wrong field doesn't compile
    "R13: update wrong field fails" in {
        assertDoesNotCompile("""
            val r = "name" ~ "Alice"
            r.update("nope", 1)
        """)
    }

    // R14: update wrong type doesn't compile
    "R14: update wrong type fails" in {
        assertDoesNotCompile("""
            val r = "name" ~ "Alice"
            r.update("name", 42)
        """)
    }

    // R15: compact
    "R15: compact filters to declared fields" in {
        val full                               = ("name" ~ "Alice") & ("age" ~ 30)
        val nameOnly: Record2["name" ~ String] = full
        val compacted                          = nameOnly.compact
        assert(compacted.toMap.size == 1)
        assert(compacted.name == "Alice")
    }

    // R16: Fields metadata
    "R16: Fields.names" in {
        val names = Fields.names["name" ~ String & "age" ~ Int]
        assert(names == Set("name", "age"))
    }

    "R17: Fields.fields" in {
        val fs = Fields.fields["name" ~ String & "age" ~ Int]
        assert(fs.size == 2)
        assert(fs.map(_.name).toSet == Set("name", "age"))
    }

    // R18: map
    "R18: map values" in {
        val r      = ("name" ~ "Alice") & ("age" ~ 30)
        val mapped = r.map([t] => (v: t) => Option(v))
        assert(mapped.name == Some("Alice"))
        assert(mapped.age == Some(30))
    }

    // R19: equality
    "R19: equality" in {
        val r1 = ("name" ~ "Alice") & ("age" ~ 30)
        val r2 = ("name" ~ "Alice") & ("age" ~ 30)
        val r3 = ("name" ~ "Bob") & ("age" ~ 25)
        assert(r1 == r2)
        assert(r1 != r3)
        assert(Record2.empty == Record2.empty)
    }

    // R19b: CanEqual rejects non-comparable field types
    "R19b: CanEqual rejects non-comparable fields" in {
        typeCheckFailure("""
            class NoEq
            val a: Record2["x" ~ NoEq] = "x" ~ new NoEq
            val b: Record2["x" ~ NoEq] = "x" ~ new NoEq
            a == b
        """)("cannot be compared")
    }

    // R20: toString
    "R20: toString" in {
        val r = "name" ~ "Alice"
        assert(r.toString.contains("name ~ Alice"))
    }

    // R21: nested record Fields
    "R21: nested Fields" in {
        type Inner = "x" ~ Int & "y" ~ Int
        type Outer = "point" ~ Record2[Inner]
        val fs = Fields.fields[Outer]
        assert(fs.size == 1)
        assert(fs.head.name == "point")
        assert(fs.head.nested.size == 2)
        assert(fs.head.nested.map(_.name).toSet == Set("x", "y"))
    }

    // R22: typed Field get/set
    "R22: typed Field get and set" in {
        val r           = ("name" ~ "Alice") & ("age" ~ 30)
        val nameField   = Field["name", String]
        val got: String = nameField.get(r)
        assert(got == "Alice")
        val r2 = nameField.set(r, "Bob")
        assert(r2.name == "Bob")
    }

    // R23: JSON-like serialization via Fields
    "R23: runtime field iteration" in {
        val r  = ("name" ~ "Alice") & ("age" ~ 30)
        val fs = Fields.fields["name" ~ String & "age" ~ Int]
        // simulate JSON serialization: collect name -> value pairs
        val map  = r.toMap
        val json = fs.map(f => f.name -> map(f.name).toString).toMap
        assert(json == Map("name" -> "Alice", "age" -> "30"))
    }

end Record2Test
