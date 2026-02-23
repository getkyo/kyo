package kyo

import Record.*
import scala.language.implicitConversions

class RecordTest extends Test:

    "creation" - {

        "single field" in {
            val r = "name" ~ "Alice"
            assert(r.name == "Alice")
        }

        "multiple fields" in {
            val r = ("name" ~ "Alice") & ("age" ~ 30)
            assert(r.name == "Alice")
            assert(r.age == 30)
        }

        "arbitrary field names (reserved names)" in {
            val r =
                ("&" ~ "and") &
                    ("toMap" ~ "map") &
                    ("equals" ~ "eq") &
                    ("getField" ~ "gf") &
                    ("" ~ "empty")
            assert(r.getField("&") == "and")
            assert(r.getField("toMap") == "map")
            assert(r.getField("equals") == "eq")
            assert(r.getField("getField") == "gf")
            assert(r.getField("") == "empty")
        }

        "fromProduct basic" in {
            case class Person(name: String, age: Int)
            val r            = Record.fromProduct(Person("Alice", 30))
            val name: String = r.name
            val age: Int     = r.age
            assert(name == "Alice")
            assert(age == 30)
        }

        "fromProduct generic" in {
            case class Box[A](value: A, label: String)
            val r          = Record.fromProduct(Box(42, "answer"))
            val value: Int = r.value
            assert(value == 42)
            assert(r.label == "answer")
        }

        "fromProduct single field" in {
            case class Single(only: Boolean)
            val r          = Record.fromProduct(Single(true))
            val v: Boolean = r.only
            assert(v == true)
            assert(r.size == 1)
        }

        "fromProduct many fields" in {
            case class Big(a: Int, b: String, c: Boolean, d: Double, e: Long, f: Char, g: Float)
            val r = Record.fromProduct(Big(1, "two", true, 4.0, 5L, '6', 7.0f))
            assert(r.a == 1)
            assert(r.b == "two")
            assert(r.c == true)
            assert(r.d == 4.0)
            assert(r.e == 5L)
            assert(r.f == '6')
            assert(r.g == 7.0f)
            assert(r.size == 7)
        }

        "fromProduct 22+ fields" in {
            case class Large(
                f1: Int,
                f2: Int,
                f3: Int,
                f4: Int,
                f5: Int,
                f6: Int,
                f7: Int,
                f8: Int,
                f9: Int,
                f10: Int,
                f11: Int,
                f12: Int,
                f13: Int,
                f14: Int,
                f15: Int,
                f16: Int,
                f17: Int,
                f18: Int,
                f19: Int,
                f20: Int,
                f21: Int,
                f22: Int,
                f23: Int
            )
            val r = Record.fromProduct(Large(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23))
            assert(r.size == 23)
            assert(r.f1 == 1)
            assert(r.f23 == 23)
        }

        "fromProduct rejects non-case-class" in {
            class NotACase(val x: Int)
            typeCheckFailure("""Record.fromProduct(new NotACase(1))""")("Required: Product")
        }

        "fromProduct with Option and Collection fields" in {
            case class User(name: String, email: Option[String])
            val r1 = Record.fromProduct(User("Bob", Some("bob@email.com")))
            val r2 = Record.fromProduct(User("Alice", None))
            assert(r1.email.contains("bob@email.com"))
            assert(r2.email.isEmpty)

            case class Team(name: String, members: List[String])
            val r3 = Record.fromProduct(Team("Engineering", List("Alice", "Bob")))
            assert(r3.members.length == 2)
            assert(r3.members.contains("Alice"))
        }
    }

    "map" - {

        "map values" in {
            val r      = ("name" ~ "Alice") & ("age" ~ 30)
            val mapped = r.map([t] => (v: t) => Option(v))
            assert(mapped.name == Some("Alice"))
            assert(mapped.age == Some(30))
        }

        "mapFields receives field metadata" in {
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
    }

    "zip" - {

        "pairs values by name" in {
            val r1     = ("x" ~ 1) & ("y" ~ 2)
            val r2     = ("x" ~ "a") & ("y" ~ "b")
            val zipped = r1.zip(r2)
            assert(zipped.x == (1, "a"))
            assert(zipped.y == (2, "b"))
        }

        "type safety" in {
            val r1               = ("x" ~ 1) & ("y" ~ 2)
            val r2               = ("x" ~ "a") & ("y" ~ "b")
            val zipped           = r1.zip(r2)
            val x: (Int, String) = zipped.x
            val y: (Int, String) = zipped.y
            assert(x == (1, "a"))
            assert(y == (2, "b"))
        }

        "single field" in {
            val r1     = "x" ~ 1
            val r2     = "x" ~ "a"
            val zipped = r1.zip(r2)
            assert(zipped.x == (1, "a"))
        }

        "three fields" in {
            val r1                 = ("a" ~ 1) & ("b" ~ "hello") & ("c" ~ true)
            val r2                 = ("a" ~ 10.0) & ("b" ~ 'x') & ("c" ~ 42L)
            val zipped             = r1.zip(r2)
            val a: (Int, Double)   = zipped.a
            val b: (String, Char)  = zipped.b
            val c: (Boolean, Long) = zipped.c
            assert(a == (1, 10.0))
            assert(b == ("hello", 'x'))
            assert(c == (true, 42L))
        }
    }

    "values" - {

        "typed tuple" in {
            val r  = ("name" ~ "Alice") & ("age" ~ 30)
            val vs = r.values
            assert(vs == ("Alice", 30))
        }

        "type safety" in {
            val r              = ("x" ~ 1) & ("y" ~ 2)
            val vs: (Int, Int) = r.values
            assert(vs == (1, 2))
        }

        "single field" in {
            val r = "name" ~ "Alice"
            val v = r.values
            assert(v == Tuple1("Alice"))
        }

        "three fields" in {
            val r = ("name" ~ "Alice") & ("age" ~ 30) & ("active" ~ true)
            val v = r.values
            assert(v == ("Alice", 30, true))
        }
    }

    "field access" - {

        "selectDynamic with inference" in {
            val r = ("name" ~ "Alice") & ("age" ~ 30)
            assert(r.name.length == 5)
            assert(r.age + 1 == 31)
        }

        "getField for special names" in {
            val r = ("user-name" ~ "Alice") & ("age" ~ 30)
            assert(r.getField("user-name") == "Alice")
            assert(r.getField("age") == 30)
        }

        "compile error for missing field" in {
            typeCheckFailure("""
                val r = "name" ~ "Alice"
                summon[Fields.Have[r.type, "missing"]]
            """)("Have")
        }
    }

    "edge cases" - {

        "empty string values" in {
            val r = ("name" ~ "") & ("value" ~ "")
            assert(r.name.isEmpty)
            assert(r.value.isEmpty)
        }

        "null values" in {
            val r = "name" ~ (null: String)
            assert(r.name == null)
        }

        "nested records creation and access" in {
            val inner = ("x" ~ 1) & ("y" ~ 2)
            val outer = ("nested" ~ inner) & ("name" ~ "test")
            assert(outer.nested.x == 1)
            assert(outer.nested.y == 2)
            assert(outer.name == "test")
        }
    }

    "update" - {

        "existing field" in {
            val r  = ("name" ~ "Alice") & ("age" ~ 30)
            val r2 = r.update("name", "Bob")
            assert(r2.name == "Bob")
            assert(r2.age == 30)
        }

        "wrong field doesn't compile" in {
            val r = "name" ~ "Alice"
            typeCheckFailure("""r.update("nope", 1)""")("Cannot prove")
        }

        "wrong type doesn't compile" in {
            val r = "name" ~ "Alice"
            typeCheckFailure("""r.update("name", 42)""")("Cannot prove")
        }
    }

    "combining records" - {

        "merge via &" in {
            val r1     = ("name" ~ "Alice") & ("age" ~ 30)
            val r2     = ("city" ~ "Paris") & ("country" ~ "France")
            val merged = r1 & r2
            assert(merged.name == "Alice")
            assert(merged.age == 30)
            assert(merged.city == "Paris")
            assert(merged.country == "France")
        }

        "fromProduct merge with manual" in {
            case class Name(first: String)
            val r = Record.fromProduct(Name("Alice")) & ("last" ~ "Smith")
            assert(r.first == "Alice")
            assert(r.last == "Smith")
        }

        "empty & record" in {
            val r      = ("name" ~ "Alice") & ("age" ~ 30)
            val merged = Record.empty & r
            assert(merged.name == "Alice")
            assert(merged.age == 30)
        }

        "record & empty" in {
            val r      = ("name" ~ "Alice") & ("age" ~ 30)
            val merged = r & Record.empty
            assert(merged.name == "Alice")
            assert(merged.age == 30)
        }
    }

    "type system" - {

        "duplicate fields merge to union" in {
            summon[("f" ~ Int & "f" ~ String) =:= ("f" ~ (Int | String))]
            succeed
        }

        "structural subtyping assignment" in {
            val full                              = ("name" ~ "Alice") & ("age" ~ 30)
            val nameOnly: Record["name" ~ String] = full
            assert(nameOnly.name == "Alice")
        }

        "structural subtyping function param" in {
            def getName(r: Record["name" ~ String]): String = r.name
            val r                                           = ("name" ~ "Alice") & ("age" ~ 30)
            assert(getName(r) == "Alice")
        }

        "structural subtyping type bounds" in {
            def getName[F <: "name" ~ String](r: Record[F]): String = r.name
            val r                                                   = ("name" ~ "Alice") & ("age" ~ 30)
            assert(getName(r) == "Alice")
        }

        "generic passthrough preserves type" in {
            def passthrough[F <: "name" ~ String](r: Record[F]): Record[F] = r
            val r                                                          = ("name" ~ "Alice") & ("age" ~ 30)
            val r2                                                         = passthrough(r)
            assert(r2.name == "Alice")
            assert(r2.age == 30)
        }

        "mixed duplicate and unique" in {
            summon[("a" ~ Int & "a" ~ String & "b" ~ Boolean) =:= ("a" ~ (Int | String) & "b" ~ Boolean)]
            succeed
        }

        "duplicate field: last writer wins at runtime" in {
            val r = "f" ~ 1 & "f" ~ "hello"
            // Type is "f" ~ (Int | String) due to contravariance
            val v: Int | String = r.f
            // Dict uses last-writer-wins, so "hello" from the right operand is stored
            assert(v.equals("hello"))
        }

        "duplicate field: left operand value when no right override" in {
            val r1 = "f" ~ 42
            val r2 = "g" ~ "other"
            val r  = r1 & r2
            assert(r.f == 42)
        }

        "duplicate field: right operand overrides left" in {
            val r1 = "f" ~ 1 & "g" ~ "a"
            val r2 = "f" ~ 2 & "h" ~ true
            val r  = r1 & r2
            assert(r.f == 2)
            assert(r.g == "a")
            assert(r.h == true)
        }

        "duplicate field: return type is union" in {
            val r = "f" ~ 1 & "f" ~ "hello"
            // Verify the return type is Int | String (compile-time check)
            typeCheck("""val _: Int | String = r.f""")
            // Should NOT compile as just Int
            typeCheckFailure("""val _: Int = r.f""")("Found:    Int | String")
        }

        "duplicate field: three-way union" in {
            summon[("f" ~ Int & "f" ~ String & "f" ~ Boolean) =:= ("f" ~ (Int | String | Boolean))]
            val r                         = "f" ~ 1 & "f" ~ "hello" & "f" ~ true
            val v: Int | String | Boolean = r.f
            assert(v.equals(true)) // last writer wins
        }

        "duplicate field: explicit union type annotation" in {
            val r: Record["f" ~ (Int | String)] = "f" ~ 42
            val v: Int | String                 = r.f
            assert(v.equals(42))
        }

        "duplicate field: combine with non-duplicate" in {
            val r = "name" ~ "Alice" & "value" ~ 1 & "value" ~ "str"
            assert(r.name == "Alice")
            val v: Int | String = r.value
            assert(v.equals("str")) // last writer wins
        }

        "Tag derivation" in {
            typeCheck("""summon[Tag[Record["name" ~ String & "age" ~ Int]]]""")
        }
    }

    "toDict" - {

        "preserves all entries" in {
            case class Pair(a: Int, b: String)
            val r                    = Record.fromProduct(Pair(1, "two"))
            given CanEqual[Any, Any] = CanEqual.derived
            assert(r.toDict.is(Dict("a" -> 1, "b" -> "two")))
        }
    }

    "equality and hashCode" - {

        "equal records" in {
            val r1 = ("name" ~ "Alice") & ("age" ~ 30)
            val r2 = ("name" ~ "Alice") & ("age" ~ 30)
            assert(r1 == r2)
        }

        "not equal different values" in {
            val r1 = ("name" ~ "Alice") & ("age" ~ 30)
            val r2 = ("name" ~ "Bob") & ("age" ~ 25)
            assert(r1 != r2)
        }

        "field order independence" in {
            val r1 = ("name" ~ "Alice") & ("age" ~ 30)
            val r2 = ("age" ~ 30) & ("name" ~ "Alice")
            assert(r1 == r2)
            assert(r1.hashCode == r2.hashCode)
        }

        "rejects without Comparable" in {
            class NoEq
            val r1: Record["x" ~ NoEq] = "x" ~ new NoEq
            val r2: Record["x" ~ NoEq] = "x" ~ new NoEq
            typeCheckFailure("""r1 == r2""")("cannot be compared")
        }

        "== works with Comparable" in {
            val r1 = ("name" ~ "Alice") & ("age" ~ 30)
            val r2 = ("name" ~ "Alice") & ("age" ~ 30)
            val r3 = ("name" ~ "Bob") & ("age" ~ 25)
            assert(r1 == r2)
            assert(r1 != r3)
        }

    }

    "show and Render" - {

        "show basic" in {
            val r = "name" ~ "Alice"
            assert(r.show.contains("name ~ Alice"))
        }

        "show fromProduct" in {
            case class Single(name: String)
            val r = Record.fromProduct(Single("Alice"))
            assert(r.show == "name ~ Alice")
        }

        "Render given" in {
            val r      = ("name" ~ "Bob") & ("age" ~ 25)
            val render = Render[Record["name" ~ String & "age" ~ Int]]
            val text   = render.asString(r)
            assert(text.contains("name"))
            assert(text.contains("Bob"))
            assert(text.contains("age"))
            assert(text.contains("25"))
        }
    }

    "stage" - {

        "with type class" in {
            trait AsColumn[A]:
                def sqlType: String
            object AsColumn:
                def apply[A](tpe: String): AsColumn[A] = new AsColumn[A]:
                    def sqlType = tpe
                given AsColumn[Int]    = AsColumn("bigint")
                given AsColumn[String] = AsColumn("text")
            end AsColumn

            case class Column[A](name: String, sqlType: String) derives CanEqual

            type Person = "name" ~ String & "age" ~ Int
            val columns = Record.stage[Person].using[AsColumn]([v] =>
                (field: Field[?, v], ac: AsColumn[v]) =>
                    Column[v](field.name, ac.sqlType))
            assert(columns.name == Column[String]("name", "text"))
            assert(columns.age == Column[Int]("age", "bigint"))
        }

        "without type class" in {
            type Person = "name" ~ String & "age" ~ Int
            val staged = Record.stage[Person]([v] => (field: Field[?, v]) => Option.empty[v])
            assert(staged.name == None)
            assert(staged.age == None)
        }

        "compile error when type class missing" in {
            trait AsColumn[A]
            object AsColumn:
                given AsColumn[Int]    = new AsColumn[Int] {}
                given AsColumn[String] = new AsColumn[String] {}

            class Role()
            type BadPerson = "name" ~ String & "age" ~ Int & "role" ~ Role

            typeCheckFailure("""Record.stage[BadPerson].using[AsColumn]([v] =>
                (field: Field[?, v], ac: AsColumn[v]) => Option.empty[v])""")("AsColumn[Role]")
        }
    }

    "compact" - {

        "filters to declared fields" in {
            val full                              = ("name" ~ "Alice") & ("age" ~ 30)
            val nameOnly: Record["name" ~ String] = full
            val compacted                         = nameOnly.compact
            assert(compacted.size == 1)
            assert(compacted.name == "Alice")
        }

        "no-op when exact" in {
            val r: Record["name" ~ String & "age" ~ Int] = ("name" ~ "Alice") & ("age" ~ 30)
            val compacted                                = r.compact
            assert(compacted.size == 2)
            assert(compacted.name == "Alice")
            assert(compacted.age == 30)
        }
    }

    "fields metadata" - {

        "Fields.names" in {
            val names = Fields.names["name" ~ String & "age" ~ Int]
            assert(names == Set("name", "age"))
        }

        "Fields.fields" in {
            val fs = Fields.fields["name" ~ String & "age" ~ Int]
            assert(fs.size == 2)
            assert(fs.map(_.name).toSet == Set("name", "age"))
        }

        "nested Fields" in {
            type Inner = "x" ~ Int & "y" ~ Int
            type Outer = "point" ~ Record[Inner]
            val fs = Fields.fields[Outer]
            assert(fs.size == 1)
            assert(fs.head.name == "point")
            assert(fs.head.nested.size == 2)
            assert(fs.head.nested.map(_.name).toSet == Set("x", "y"))
        }
    }

    "large record: values" in {
        type F = "f1" ~ Int & "f2" ~ Int & "f3" ~ Int & "f4" ~ Int & "f5" ~ Int &
            "f6" ~ Int & "f7" ~ Int & "f8" ~ Int & "f9" ~ Int & "f10" ~ Int &
            "f11" ~ Int & "f12" ~ Int & "f13" ~ Int & "f14" ~ Int & "f15" ~ Int &
            "f16" ~ Int & "f17" ~ Int & "f18" ~ Int & "f19" ~ Int & "f20" ~ Int &
            "f21" ~ Int & "f22" ~ Int & "f23" ~ Int & "f24" ~ Int & "f25" ~ Int
        val r: Record[F] = "f1" ~ 1 & "f2" ~ 2 & "f3" ~ 3 & "f4" ~ 4 & "f5" ~ 5 &
            "f6" ~ 6 & "f7" ~ 7 & "f8" ~ 8 & "f9" ~ 9 & "f10" ~ 10 &
            "f11" ~ 11 & "f12" ~ 12 & "f13" ~ 13 & "f14" ~ 14 & "f15" ~ 15 &
            "f16" ~ 16 & "f17" ~ 17 & "f18" ~ 18 & "f19" ~ 19 & "f20" ~ 20 &
            "f21" ~ 21 & "f22" ~ 22 & "f23" ~ 23 & "f24" ~ 24 & "f25" ~ 25
        val v                    = r.values
        given CanEqual[Any, Any] = CanEqual.derived
        val elems                = (0 until v.productArity).map(v.productElement).toSet
        assert(elems == (1 to 25).toSet)
    }

    "large record: stage" in {
        type F = "f1" ~ Int & "f2" ~ Int & "f3" ~ Int & "f4" ~ Int & "f5" ~ Int &
            "f6" ~ Int & "f7" ~ Int & "f8" ~ Int & "f9" ~ Int & "f10" ~ Int &
            "f11" ~ Int & "f12" ~ Int & "f13" ~ Int & "f14" ~ Int & "f15" ~ Int &
            "f16" ~ Int & "f17" ~ Int & "f18" ~ Int & "f19" ~ Int & "f20" ~ Int &
            "f21" ~ Int & "f22" ~ Int & "f23" ~ Int & "f24" ~ Int & "f25" ~ Int
        val staged = Record.stage[F]([v] => (field: Field[?, v]) => Option.empty[v])
        assert(staged.f1 == None)
        assert(staged.f25 == None)
    }

    "large record: stage with type class" in {
        type F = "f1" ~ Int & "f2" ~ Int & "f3" ~ Int & "f4" ~ Int & "f5" ~ Int &
            "f6" ~ Int & "f7" ~ Int & "f8" ~ Int & "f9" ~ Int & "f10" ~ Int &
            "f11" ~ Int & "f12" ~ Int & "f13" ~ Int & "f14" ~ Int & "f15" ~ Int &
            "f16" ~ Int & "f17" ~ Int & "f18" ~ Int & "f19" ~ Int & "f20" ~ Int &
            "f21" ~ Int & "f22" ~ Int & "f23" ~ Int & "f24" ~ Int & "f25" ~ Int
        val staged = Record.stage[F].using[Render]([v] => (field: Field[?, v], r: Render[v]) => Option.empty[v])
        assert(staged.f1 == None)
        assert(staged.f25 == None)
    }

end RecordTest
