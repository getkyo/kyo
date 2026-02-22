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

    // R19: equality via is
    "R19: equality" in {
        val r1 = ("name" ~ "Alice") & ("age" ~ 30)
        val r2 = ("name" ~ "Alice") & ("age" ~ 30)
        val r3 = ("name" ~ "Bob") & ("age" ~ 25)
        assert(r1.is(r2))
        assert(!r1.is(r3))
        assert(Record2.empty.is(Record2.empty))
    }

    // R19b: Comparable rejects non-comparable field types
    "R19b: Comparable rejects non-comparable fields" in {
        typeCheckFailure("""
            class NoEq
            val a: Record2["x" ~ NoEq] = "x" ~ new NoEq
            val b: Record2["x" ~ NoEq] = "x" ~ new NoEq
            a.is(b)
        """)("Comparable")
    }

    // R20: show
    "R20: show" in {
        val r = "name" ~ "Alice"
        assert(r.show.contains("name ~ Alice"))
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

    // R24: fromProduct — basic case class with type verification
    "R24: fromProduct basic" in {
        case class Person(name: String, age: Int)
        val r            = Record2.fromProduct(Person("Alice", 30))
        val name: String = r.name
        val age: Int     = r.age
        assert(name == "Alice")
        assert(age == 30)
    }

    // R25: fromProduct — return type is field intersection, not case class
    "R25: fromProduct return type" in {
        case class Point(x: Int, y: Int)
        val r: Record2["x" ~ Int & "y" ~ Int] = Record2.fromProduct(Point(1, 2))
        assert(r.x == 1)
        assert(r.y == 2)
    }

    // R26: fromProduct — merge with manually constructed record
    "R26: fromProduct merge" in {
        case class Name(first: String)
        val r = Record2.fromProduct(Name("Alice")) & ("last" ~ "Smith")
        assert(r.first == "Alice")
        assert(r.last == "Smith")
    }

    // R27: fromProduct — generic case class preserves type parameter
    "R27: fromProduct generic" in {
        case class Box[A](value: A, label: String)
        val r          = Record2.fromProduct(Box(42, "answer"))
        val value: Int = r.value
        assert(value == 42)
        assert(r.label == "answer")
    }

    // R28: fromProduct — single field
    "R28: fromProduct single field" in {
        case class Single(only: Boolean)
        val r          = Record2.fromProduct(Single(true))
        val v: Boolean = r.only
        assert(v == true)
        assert(r.size == 1)
    }

    // R29: fromProduct — toMap preserves all entries
    "R29: fromProduct toMap" in {
        case class Pair(a: Int, b: String)
        val r                    = Record2.fromProduct(Pair(1, "two"))
        given CanEqual[Any, Any] = CanEqual.derived
        assert(r.toMap == Map("a" -> 1, "b" -> "two"))
    }

    // R30: fromProduct — update works on result
    "R30: fromProduct update" in {
        case class Point(x: Int, y: Int)
        val r  = Record2.fromProduct(Point(1, 2))
        val r2 = r.update("x", 10)
        assert(r2.x == 10)
        assert(r2.y == 2)
    }

    // R31: fromProduct — show
    "R31: fromProduct show" in {
        case class Single(name: String)
        val r = Record2.fromProduct(Single("Alice"))
        assert(r.show == "name ~ Alice")
    }

    // R32: fromProduct — compact
    "R32: fromProduct compact" in {
        case class Name(first: String)
        val r                                   = Record2.fromProduct(Name("Alice")) & ("extra" ~ 1)
        val narrowed: Record2["first" ~ String] = r
        val compacted                           = narrowed.compact
        assert(compacted.size == 1)
        assert(compacted.first == "Alice")
    }

    // R33: fromProduct — map
    "R33: fromProduct map" in {
        case class Point(x: Int, y: Int)
        val r      = Record2.fromProduct(Point(1, 2))
        val mapped = r.map([t] => (v: t) => Option(v))
        assert(mapped.x == Some(1))
        assert(mapped.y == Some(2))
    }

    // R34: fromProduct — is (equality)
    "R34: fromProduct equality" in {
        case class Point(x: Int, y: Int)
        val r1 = Record2.fromProduct(Point(1, 2))
        val r2 = Record2.fromProduct(Point(1, 2))
        val r3 = Record2.fromProduct(Point(3, 4))
        assert(r1.is(r2))
        assert(!r1.is(r3))
    }

    // R35: fromProduct — rejects non-case-class
    "R35: fromProduct rejects non-case-class" in {
        assertDoesNotCompile("""
            class NotACase(val x: Int)
            Record2.fromProduct(new NotACase(1))
        """)
    }

    // R36: fromProduct — many fields
    "R36: fromProduct many fields" in {
        case class Big(a: Int, b: String, c: Boolean, d: Double, e: Long, f: Char, g: Float)
        val r = Record2.fromProduct(Big(1, "two", true, 4.0, 5L, '6', 7.0f))
        assert(r.a == 1)
        assert(r.b == "two")
        assert(r.c == true)
        assert(r.d == 4.0)
        assert(r.e == 5L)
        assert(r.f == '6')
        assert(r.g == 7.0f)
        assert(r.size == 7)
    }

    // --- getField ---

    "R37: getField basic" in {
        val r = ("user-name" ~ "Alice") & ("age" ~ 30)
        assert(r.getField("user-name") == "Alice")
        assert(r.getField("age") == 30)
    }

    // --- fields ---

    "R38: fields returns field names as list" in {
        val r  = ("name" ~ "Alice") & ("age" ~ 30)
        val fs = r.fields
        assert(fs.toSet == Set("name", "age"))
    }

    // --- values ---

    "R39: values returns typed tuple" in {
        val r  = ("name" ~ "Alice") & ("age" ~ 30)
        val vs = r.values
        assert(vs == ("Alice", 30))
    }

    "R40: values type safety" in {
        val r              = ("x" ~ 1) & ("y" ~ 2)
        val vs: (Int, Int) = r.values
        assert(vs == (1, 2))
    }

    // --- mapFields ---

    "R41: mapFields receives field metadata" in {
        val r          = ("name" ~ "Alice") & ("age" ~ 30)
        var fieldNames = List.empty[String]
        val mapped = r.mapFields([t] =>
            (field: Field[?, t], v: t) =>
                fieldNames = field.name :: fieldNames
                Option(v))
        assert(mapped.name == Some("Alice"))
        assert(mapped.age == Some(30))
        assert(fieldNames.toSet == Set("name", "age"))
    }

    // --- zip ---

    "R42: zip pairs values by name" in {
        val r1     = ("x" ~ 1) & ("y" ~ 2)
        val r2     = ("x" ~ "a") & ("y" ~ "b")
        val zipped = r1.zip(r2)
        assert(zipped.x == (1, "a"))
        assert(zipped.y == (2, "b"))
    }

    "R43: zip type safety" in {
        val r1               = ("x" ~ 1) & ("y" ~ 2)
        val r2               = ("x" ~ "a") & ("y" ~ "b")
        val zipped           = r1.zip(r2)
        val x: (Int, String) = zipped.x
        val y: (Int, String) = zipped.y
        assert(x == (1, "a"))
        assert(y == (2, "b"))
    }

    // --- CanEqual ---

    "R44: CanEqual allows == with Comparable" in {
        val r1 = ("name" ~ "Alice") & ("age" ~ 30)
        val r2 = ("name" ~ "Alice") & ("age" ~ 30)
        assert(r1 == r2)
    }

    "R45: CanEqual rejects == without Comparable" in {
        assertDoesNotCompile("""
            class NoEq
            val r1: Record2["x" ~ NoEq] = "x" ~ new NoEq
            val r2: Record2["x" ~ NoEq] = "x" ~ new NoEq
            r1 == r2
        """)
    }

end Record2Test
