package kyo

import Record.Field
import kyo.internal.TypeIntersection
import scala.compiletime.summonInline

class RecordTest extends Test:

    "creation" - {

        "single field" in {
            val record = "name" ~ "Alice" & "name" ~ 10
            assert(record.name == "Alice")
        }

        "multiple fields" in {
            val record = "name" ~ "Alice" & "age" ~ 30
            assert(record.name == "Alice")
            assert(record.age == 30)
        }

        "from product types" in {
            case class Person(name: String, age: Int)
            val person = Person("Alice", 30)
            val record = Record.fromProduct(person)
            assert(record.name == "Alice")
            assert(record.age == 30)
        }

    }

    "complex types" - {
        "nested case classes" in {
            case class Address(street: String, city: String)
            case class Person(name: String, address: Address)

            val address = Address("123 Main St", "Springfield")
            val person  = Person("Alice", address)
            val record  = Record.fromProduct(person)

            assert(record.name == "Alice")
            assert(record.address.street == "123 Main St")
            assert(record.address.city == "Springfield")
        }

        "option fields" in {
            case class User(name: String, email: Option[String])
            val user1 = User("Bob", Some("bob@email.com"))
            val user2 = User("Alice", None)

            val record1 = Record.fromProduct(user1)
            val record2 = Record.fromProduct(user2)

            assert(record1.email.contains("bob@email.com"))
            assert(record2.email.isEmpty)
        }

        "collection fields" in {
            case class Team(name: String, members: List[String])
            val team   = Team("Engineering", List("Alice", "Bob", "Charlie"))
            val record = Record.fromProduct(team)

            assert(record.name == "Engineering")
            assert(record.members.length == 3)
            assert(record.members.contains("Alice"))
        }

        "sealed trait hierarchy" in {
            sealed trait Animal
            case class Dog(name: String, age: Int)   extends Animal
            case class Cat(name: String, lives: Int) extends Animal

            val dog       = Dog("Rex", 5)
            val dogRecord = Record.fromProduct(dog)
            assert(dogRecord.name == "Rex")
            assert(dogRecord.age == 5)
        }

        "generic case classes" in {
            case class Box[T](value: T)
            val box    = Box(42)
            val record = Record.fromProduct(box)
            assert(record.value == 42)
        }
    }

    "field access" - {
        "type-safe access" in {
            val record        = "name" ~ "Bob" & "age" ~ 25
            val name0         = record.name
            val name1: String = name0
            val age0          = record.age
            val age1: Int     = age0
            assert(record.name == "Bob")
            assert(record.age == 25)
        }

        "incorrect type access should not compile" in {
            typeCheckFailure("""
                val record = "name" ~ "Bob"
                val name: Int = record.name
            """)("""Invalid field access: ("name" : String)""")
        }

        "non-existent field access should not compile" in {
            typeCheckFailure("""
                val record = "name" ~ "Bob"
                val city = record.city
            """)("""Invalid field access: ("city" : String)""")
        }
    }

    "edge cases" - {
        "empty string fields" in {
            val record = "name" ~ "" & "value" ~ ""
            assert(record.name.isEmpty)
            assert(record.value.isEmpty)
        }

        "special characters in field names" in {
            val record = "user-name" ~ "Alice" & "_internal" ~ 42
            assert(record.`user-name` == "Alice")
            assert(record._internal == 42)
        }

        "null values" in {
            val record = "name" ~ null
            assert(record.name == null)
        }

        "empty case class" in {
            case class Empty()
            val record = Record.fromProduct(Empty())
            assert(record.size == 0)
        }

        "case class with 22+ fields" in {
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
            val large  = Large(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23)
            val record = Record.fromProduct(large)
            assert(record.size == 23)
            assert(record.f1 == 1)
            assert(record.f23 == 23)
        }
    }

    "modification" - {
        "adding new fields" in {
            val record1 = "name" ~ "Charlie"
            val record2 = record1 & "age" ~ 25
            assert(record2.name == "Charlie")
            assert(record2.age == 25)
        }

        "updating existing fields" in {
            val record1 = "name" ~ "Charlie" & "age" ~ 25
            val record2 = record1 & "age" ~ 26
            assert(record2.name == "Charlie")
            assert(record2.age == 26)
        }
    }

    "combining records" - {
        "merge two records" in {
            val record1 = "name" ~ "Eve" & "age" ~ 30
            val record2 = "city" ~ "Paris" & "country" ~ "France"
            val merged  = record1 & record2

            assert(merged.name == "Eve")
            assert(merged.age == 30)
            assert(merged.city == "Paris")
            assert(merged.country == "France")
        }

        "merge with overlapping fields" in {
            val record1 = "name" ~ "Eve" & "age" ~ 30
            val record2 = "age" ~ 31 & "city" ~ "Paris"
            val merged  = record1 & record2

            assert(merged.name == "Eve")
            assert(merged.age == 31)
            assert(merged.city == "Paris")
        }

        "merge with type conflicts" in {
            val record1 = "value" ~ "string"
            val record2 = "value" ~ 42
            val merged  = record1 & record2
            assert((merged.value: Int) == 42)
            assert((merged.value: String) == "string")
        }
    }

    "type system" - {
        "type tag behavior" in {
            val record = "num" ~ 42
            val field  = record.fields.head
            assert(field.tag =:= Tag[Int])
        }

        "handle generic types" in {
            val record = "list" ~ List(1, 2, 3)
            val field  = record.fields.head
            assert(field.tag =:= Tag[List[Int]])
        }

        "handle recursive types" in {
            case class Node(value: Int, next: Option[Node])
            val node   = Node(1, Some(Node(2, None)))
            val record = Record.fromProduct(node)
            assert(record.value == 1)
            assert(record.next.isDefined)
            assert(record.next.get.value == 2)
        }

        "preserve type parameters in nested structures" in {
            case class Wrapper[A, B](first: List[A], second: Map[String, B])
            val wrapper = Wrapper(List(1, 2, 3), Map("key" -> true))
            val record  = Record.fromProduct(wrapper)
            assert(record.first.head == 1)
            assert(record.second.apply("key") == true)
        }
    }

    "map operations" - {
        "toMap consistency" in {
            val original = "name" ~ "Alice" & "age" ~ 30
            val asMap    = original.toMap

            assert(asMap.size == 2)
            assert(asMap.keys.forall(_.isInstanceOf[Field[?, ?]]))
        }

        "fields set operations" in {
            val record = "name" ~ "Alice" & "age" ~ 30
            assert(record.fields.size == 2)
            assert(record.fields.map(_.name).toSet == Set("name", "age"))
        }
    }

    "compact" - {
        "preserves defined fields" in {
            val record: Record["name" ~ String & "age" ~ Int] =
                "name" ~ "Frank" & "age" ~ 40
            val compacted = record.compact

            assert(compacted.size == 2)
            assert(compacted.name == "Frank")
            assert(compacted.age == 40)
        }

        "removes extra fields" in {
            val record: Record["name" ~ String] =
                "name" ~ "Frank" & "age" ~ 40
            val compacted = record.compact
            assert(compacted.size == 1)
            typeCheckFailure("""
                compacted.age
            """)("""Invalid field access: ("age" : String)""")
        }
    }

    "stage" - {
        case class AsColumn[A](typ: String)

        object AsColumn:
            given AsColumn[Int]    = AsColumn("bigint")
            given AsColumn[String] = AsColumn("text")

        case class Column[A](name: String)(using AsColumn[A]) derives CanEqual

        object ColumnInline extends Record.StageAs[[n, v] =>> Column[v]]:
            inline def stage[Name <: String, Value](field: Field[Name, Value]): Column[Value] =
                Column[Value](field.name)(using summonInline[AsColumn[Value]])

        "build record if all inlined" in {
            type Person = "name" ~ String & "age" ~ Int

            val columns = Record.stage[Person](ColumnInline)
            val result = "name" ~ Column[String]("name") &
                "age" ~ Column[Int]("age")

            assert(columns.name == Column[String]("name"))
            assert(columns.age == Column[Int]("age"))
            assert(columns == result)
        }

        "compile error when inlining failed" in {
            class Role()
            type Person = "name" ~ String & "age" ~ Int & "role" ~ Role

            typeCheckFailure("""
                Record.stage[Person](ColumnInline)
            """)("""No given instance of type AsColumn[Role] was found""")
        }
    }

    "AsFields behavior" - {
        "complex intersection types" in {
            val fields = Record.AsFields["a" ~ Int & "b" ~ String & "c" ~ Int]
            assert(fields.size == 3)
            assert(fields.map(_.name).toSet == Set("a", "b", "c"))
        }

        "nested intersection types" in {
            type Nested = "outer" ~ Int & ("inner1" ~ String & "inner2" ~ Boolean)
            val fields = Record.AsFields[Nested]
            assert(fields.size == 3)
            assert(fields.map(_.name).toSet == Set("outer", "inner1", "inner2"))
        }

        "intersections with 4 fields" in {
            type Fields4 = "a" ~ Int & "b" ~ String & "c" ~ Boolean & "d" ~ List[Int]
            val fields = Record.AsFields[Fields4]
            assert(fields.size == 4)
            assert(fields.map(_.name).toSet == Set("a", "b", "c", "d"))
        }

        "intersections up to 22 fields" in {
            type Fields22 =
                "1" ~ Int & "2" ~ Int & "3" ~ Int & "4" ~ Int & "5" ~ Int & "6" ~ Int & "7" ~ Int & "8" ~ Int & "9" ~ Int & "10" ~ Int &
                    "11" ~ Int & "12" ~ Int & "13" ~ Int & "14" ~ Int & "15" ~ Int & "16" ~ Int & "17" ~ Int & "18" ~ Int & "19" ~ Int &
                    "20" ~ Int & "21" ~ Int & "22" ~ Int
            assertCompiles("Record.AsFields[Fields22]")
        }

        "intersections with more than 22 fields" in {
            type Fields23 =
                "1" ~ Int & "2" ~ Int & "3" ~ Int & "4" ~ Int & "5" ~ Int & "6" ~ Int & "7" ~ Int & "8" ~ Int & "9" ~ Int & "10" ~ Int &
                    "11" ~ Int & "12" ~ Int & "13" ~ Int & "14" ~ Int & "15" ~ Int & "16" ~ Int & "17" ~ Int & "18" ~ Int & "19" ~ Int &
                    "20" ~ Int & "21" ~ Int & "22" ~ Int & "23" ~ Int
            assertCompiles("Record.AsFields[Fields23]")
        }

        "intersections with duplicate field names but different types" in {
            type DupeFields = "value" ~ Int & "value" ~ String
            val fields = Record.AsFields[DupeFields]
            assert(fields.size == 2)
            assert(fields.map(_.name).toSet == Set("value"))
            assert(fields.map(_.tag).toSet.size == 2)
        }
    }

    "AsRecord" - {
        "derive for case class" in {
            case class Person(name: String, age: Int)
            val record = Record.fromProduct(Person("Alice", 30))
            assert(record.name == "Alice")
            assert(record.age == 30)
        }

        "derive for tuple" in {
            val tuple  = ("Alice", 30)
            val record = Record.fromProduct(tuple)
            assert(record._1 == "Alice")
            assert(record._2 == 30)
        }
    }

    "nested records" in {
        val inner = "x" ~ 1 & "y" ~ 2
        assertCompiles("""("nested" ~ inner)""")
    }

    "variance behavior" - {
        "allows upcasting to fewer fields" in {
            val full: Record["name" ~ String & "age" ~ Int & "city" ~ String] =
                "name" ~ "Alice" & "age" ~ 30 & "city" ~ "Paris"

            val nameAge: Record["name" ~ String & "age" ~ Int] = full
            assert(nameAge.name == "Alice")
            assert(nameAge.age == 30)

            val nameOnly: Record["name" ~ String] = full
            assert(nameOnly.name == "Alice")
        }

        "preserves type safety" in {
            typeCheckFailure("""
                val partial: Record["name" ~ String] = "age" ~ 30
            """)("""Required: kyo.Record[("name" : String) ~ String]""")
        }

        "allows multiple upcasts" in {
            val record = "name" ~ "Bob" & "age" ~ 25 & "city" ~ "London" & "active" ~ true

            def takesNameAge(r: Record["name" ~ String & "age" ~ Int]): String =
                s"${r.name} is ${r.age}"

            def takesNameOnly(r: Record["name" ~ String]): String =
                s"Name: ${r.name}"

            assert(takesNameAge(record) == "Bob is 25")
            assert(takesNameOnly(record) == "Name: Bob")
        }
    }

    "equality and hashCode" - {

        "equal records with same fields" in {
            val record1 = "name" ~ "Alice" & "age" ~ 30
            val record2 = "name" ~ "Alice" & "age" ~ 30

            assert(record1 == record2)
            assert(record1.hashCode == record2.hashCode)
        }

        "not equal with different values" in {
            val record1 = "name" ~ "Alice" & "age" ~ 30
            val record2 = "name" ~ "Alice" & "age" ~ 31

            assert(record1 != record2)
        }

        "equal with fields in different order" in {
            val record1 = "name" ~ "Alice" & "age" ~ 30
            val record2 = "age" ~ 30 & "name" ~ "Alice"

            assert(record1 == record2)
            assert(record1.hashCode == record2.hashCode)
        }

        "not equal with different number of fields" in {
            val record1 = "name" ~ "Alice" & "age" ~ 30
            val record2 = "name" ~ "Alice"

            assert(record1 != record2)
        }

        "compile when all fields have CanEqual" in {
            val record1 = "name" ~ "Alice" & "age" ~ 30 & "scores" ~ List(1, 2, 3)
            val record2 = "name" ~ "Alice" & "age" ~ 30 & "scores" ~ List(1, 2, 3)

            assert(record1 == record2)
        }

        "not compile with different field names" in {
            val record1 = "name" ~ "Alice" & "age" ~ 30
            val record2 = "name" ~ "Alice" & "years" ~ 30

            typeCheckFailure("""
                assert(record1 != record2)
            """)("""cannot be compared with == or !=""")
        }

        "not compile when fields lack CanEqual" in {
            case class NoEqual(x: Int)

            val record1: Record["test" ~ NoEqual] = "test" ~ NoEqual(1)
            val record2                           = "test" ~ NoEqual(1)

            typeCheckFailure("""
                assert(record1 == record2)
            """)("""cannot be compared with == or !=""")
        }

        "subtype equality" - {

            "not equal when values differ" in {
                val full: Record["name" ~ String & "age" ~ Int & "city" ~ String] =
                    "name" ~ "Alice" & "age" ~ 30 & "city" ~ "Paris"
                val partial: Record["name" ~ String & "age" ~ Int] =
                    "name" ~ "Bob" & "age" ~ 30

                assert(partial != full)
                assert(full != partial)
            }

            "equal across multiple subtype levels" in {
                val full: Record["name" ~ String & "age" ~ Int & "city" ~ String] =
                    "name" ~ "Alice" & "age" ~ 30 & "city" ~ "Paris"
                val medium: Record["name" ~ String & "age" ~ Int] = full
                val minimal: Record["name" ~ String]              = full

                assert(minimal == medium)
                assert(medium == full)
                assert(minimal == full)
            }

            "maintains symmetry" in {
                val full: Record["name" ~ String & "age" ~ Int & "city" ~ String] =
                    "name" ~ "Alice" & "age" ~ 30 & "city" ~ "Paris"
                val partial: Record["name" ~ String & "age" ~ Int] = full

                assert((full == partial) == (partial == full))
            }
        }
    }

    "duplicate field names" - {
        "allows fields with same name but different types" in {
            val record = "value" ~ "string" & "value" ~ 42

            assert((record.value: String) == "string")
            assert((record.value: Int) == 42)
        }

        "preserves both values when merging records" in {
            val record1 = "value" ~ "string"
            val record2 = "value" ~ 42
            val merged  = record1 & record2

            assert((merged.value: String) == "string")
            assert((merged.value: Int) == 42)
        }

        "handles multiple duplicate fields" in {
            val record = "x" ~ "str" & "x" ~ 42 & "x" ~ true

            assert((record.x: String) == "str")
            assert((record.x: Int) == 42)
            assert((record.x: Boolean) == true)
        }
    }

    "Tag derivation" - {
        "derives tags for record" in {
            assertCompiles("""summon[Tag[Record["name" ~ String & "age" ~ Int]]]""")
        }

        "doesn't limit the use of nested records" in {
            val inner = "x" ~ 1 & "y" ~ 2
            assertCompiles(""""z" ~ inner""")
        }
    }

    "Render" - {
        "simple record" in {
            val record = "name" ~ "John" & "age" ~ 30
            assert(Render.asText(record).show == """name ~ John & age ~ 30""")
        }

        "long simple record" in {
            val record = "name" ~ "Bob" & "age" ~ 25 & "city" ~ "London" & "active" ~ true
            assert(Render.asText(record).show == """name ~ Bob & age ~ 25 & city ~ London & active ~ true""")
        }

        "empty record" in {
            val record = Record.empty
            assert(Render.asText(record).show == "")
        }

        "render with upper type instance" in {
            val record = "name" ~ "Bob" & "age" ~ 25 & "city" ~ "London" & "active" ~ true
            val render = Render[Record["name" ~ String & "city" ~ String]]
            assert(render.asString(record) == """name ~ Bob & city ~ London""")
        }

        "respects custom render instances" in {
            case class Name(u: String)
            given Render[Name] with
                def asText(name: Name): Text =
                    val (prefix, suffix) = name.u.splitAt(3)
                    prefix ++ suffix.map(_ => '*')
            end given

            val record = "first" ~ Name("John") & "last" ~ Name("Johnson")
            assert(Render.asText(record).show == """first ~ Joh* & last ~ Joh****""")
        }
    }

    "malformed record API behavior" - {

        "method call restrictions" - {
            type MalformedRecord = Record[Int & "name" ~ String & "age" ~ Int]

            "cannot call methods that take malformed types" in {
                def takesMalformed(r: MalformedRecord): String = r.name
                typeCheckFailure("""
                    takesMalformed("name" ~ "test" & "age" ~ 42)
                """)("""Required: kyo.Record[Int & ("age" : String) ~ Int]""")
            }

            "cannot return malformed types" in {
                typeCheckFailure("""
                    def returnsMalformed(): MalformedRecord =
                        "name" ~ "test" & "age" ~ 42
                """)("""Required: kyo.Record[Int & ("age" : String) ~ Int]""")
            }
        }

        "behavior with unsafe type cast" - {
            val record =
                ("name" ~ "test" & "age" ~ 42)
                    .asInstanceOf[Record[Int & "name" ~ String & "age" ~ Int]]

            "selectDynamic works" in {
                val name: String = record.name
                val age: Int     = record.age
                assert(name == "test")
                assert(age == 42)
            }

            "toMap preserves fields" in {
                given [A]: CanEqual[A, A]      = CanEqual.derived
                val map: Map[Field[?, ?], Any] = record.toMap
                assert(map.size == 2)
                assert(map(Field("name", Tag[String])) == "test")
                assert(map(Field("age", Tag[Int])) == 42)
            }

            "fields returns correct set" in {
                val fields: Set[Field[?, ?]] = record.fields
                assert(fields.size == 2)
                assert(fields.map(_.name) == Set("name", "age"))
            }

            "size returns correct count" in {
                val size: Int = record.size
                assert(size == 2)
            }

            "& operator works with malformed base" in {
                val extended = record & ("extra" ~ "value")
                assert(extended.name == "test")
                assert(extended.age == 42)
                assert(extended.extra == "value")
            }
        }

        "AsFields behavior" - {
            val error = "Given type doesn't match to expected field shape: Name ~ Value"

            "summoning AsFields instance" in {
                typeCheckFailure("""
                    summon[Record.AsFields[Int & "name" ~ String & "age" ~ Int]]
                """)(error)
            }

            "AsFields with multiple raw types" in {
                typeCheckFailure("""
                    Record.AsFields[Int & Boolean & "value" ~ String & String]
                """)(error)
            }

            "AsFields with duplicate field names" in {
                typeCheckFailure("""
                   Record.AsFields[Int & "value" ~ String & "value" ~ Int]
                """)(error)
            }

            "compact with AsFields" in {
                val record = ("name" ~ "test" & "age" ~ 42)
                    .asInstanceOf[Record[Int & "name" ~ String & "age" ~ Int]]
                typeCheckFailure("""
                    record.compact
                """)(error)
            }
        }
    }

    "nested records" - {

        "creation and access" in {
            val inner = "x" ~ 1 & "y" ~ 2
            val outer = "nested" ~ inner & "name" ~ "test"

            assert(outer.nested.x == 1)
            assert(outer.nested.y == 2)
            assert(outer.name == "test")
        }

        "multi-level nesting" in {
            val deepest = "value" ~ "deep" & "num" ~ 42
            val middle  = "deepRecord" ~ deepest & "flag" ~ true
            val outer   = "middleRecord" ~ middle & "name" ~ "test"

            assert(outer.middleRecord.deepRecord.value == "deep")
            assert(outer.middleRecord.deepRecord.num == 42)
            assert(outer.middleRecord.flag == true)
            assert(outer.name == "test")
        }

        "adding fields to nested records" in {
            val inner = "x" ~ 1 & "y" ~ 2
            val outer = "nested" ~ inner & "name" ~ "test"

            val outerWithExtra = outer & "extra" ~ "value"
            assert(outerWithExtra.extra == "value")
            assert(outerWithExtra.nested.x == 1)

            val innerWithExtra = inner & "z" ~ 3
            val newOuter       = "nested" ~ innerWithExtra & "name" ~ "test"
            assert(newOuter.nested.z == 3)
            assert(newOuter.nested.x == 1)
        }

        "combining records with nested fields" in {
            val inner1 = "x" ~ 1 & "y" ~ 2
            val outer1 = "nested" ~ inner1 & "name" ~ "test1"

            val inner2 = "a" ~ "A" & "b" ~ "B"
            val outer2 = "nested2" ~ inner2 & "count" ~ 5

            val combined = outer1 & outer2

            assert(combined.nested.x == 1)
            assert(combined.nested.y == 2)
            assert(combined.name == "test1")
            assert(combined.nested2.a == "A")
            assert(combined.nested2.b == "B")
            assert(combined.count == 5)
        }

        "duplicate field names in nested records" in {
            val inner1 = "value" ~ "string" & "count" ~ 1
            val inner2 = "value" ~ 42 & "count" ~ "two"

            val outer = "data" ~ inner1 & "info" ~ inner2

            assert((outer.data.value: String) == "string")
            assert((outer.data.count: Int) == 1)
            assert((outer.info.value: Int) == 42)
            assert((outer.info.count: String) == "two")
        }

        "nested record type safety" in {
            val inner1 = "x" ~ 1 & "y" ~ 2
            val inner2 = "x" ~ "string" & "y" ~ true

            val outer1 = "nested" ~ inner1 & "name" ~ "test1"
            val outer2 = "nested" ~ inner2 & "name" ~ "test2"

            typeCheckFailure("""
                val fail: Record["nested" ~ Record["x" ~ Int & "y" ~ Int] & "name" ~ String] = outer2
            """)("""Required: kyo.Record[("nested" : String)""")
        }

        "case class conversion with nested records" in {
            case class Point(x: Int, y: Int)
            case class NamedPoint(point: Point, name: String)

            val point      = Point(10, 20)
            val namedPoint = NamedPoint(point, "Origin")

            val record = Record.fromProduct(namedPoint)

            assert(record.point.x == 10)
            assert(record.point.y == 20)
            assert(record.name == "Origin")

            case class Container(record: Record["x" ~ Int & "y" ~ Int], label: String)
            val innerRecord = "x" ~ 5 & "y" ~ 10
            val container   = Container(innerRecord, "Container")

            val containerRecord = Record.fromProduct(container)
            assert(containerRecord.record.x == 5)
            assert(containerRecord.record.y == 10)
            assert(containerRecord.label == "Container")
        }

        "recursive nested records" in {
            val leaf1  = "value" ~ "leaf1" & "leaf" ~ true
            val leaf2  = "value" ~ "leaf2" & "leaf" ~ true
            val branch = "left" ~ leaf1 & "right" ~ leaf2 & "branch" ~ true
            val root   = "main" ~ branch & "root" ~ true

            assert(root.main.left.value == "leaf1")
            assert(root.main.left.leaf == true)
            assert(root.main.right.value == "leaf2")
            assert(root.main.branch == true)
            assert(root.root == true)
        }

        "equality and hashCode with nested records" in {
            val inner1 = "x" ~ 1 & "y" ~ 2
            val inner2 = "x" ~ 1 & "y" ~ 2
            val inner3 = "x" ~ 1 & "y" ~ 3

            val outer1 = "nested" ~ inner1 & "name" ~ "test"
            val outer2 = "nested" ~ inner2 & "name" ~ "test"
            val outer3 = "nested" ~ inner3 & "name" ~ "test"

            assert(outer1 == outer2)
            assert(outer1.hashCode == outer2.hashCode)
            assert(outer1 != outer3)
        }

        "render with nested records" in {
            val inner = "x" ~ 1 & "y" ~ 2
            val outer = "nested" ~ inner & "name" ~ "test"

            val rendered = Render.asText(outer).show
            assert(rendered.contains("nested ~"))
            assert(rendered.contains("name ~ test"))
        }

        "mixed complex nested structures" in {
            case class Point(x: Int, y: Int)
            case class Circle(center: Point, radius: Int)

            val circleRecord = Record.fromProduct(Circle(Point(0, 0), 10))

            val manual = "width" ~ 100 & "height" ~ 200
            val mixed  = "circle" ~ circleRecord & "rectangle" ~ manual & "name" ~ "shapes"

            assert(mixed.circle.center.x == 0)
            assert(mixed.circle.center.y == 0)
            assert(mixed.circle.radius == 10)
            assert(mixed.rectangle.width == 100)
            assert(mixed.rectangle.height == 200)
            assert(mixed.name == "shapes")
        }

        "nested option fields" in {
            val inner = "x" ~ 1 & "y" ~ 2

            val withSome = "nested" ~ Some(inner) & "name" ~ "hasValue"
            val withNone = "nested" ~ Option.empty[Record["x" ~ Int & "y" ~ Int]] & "name" ~ "noValue"

            assert(withSome.nested.isDefined)
            assert(withSome.nested.get.x == 1)
            assert(withSome.nested.get.y == 2)
            assert(withSome.name == "hasValue")

            assert(withNone.nested.isEmpty)
            assert(withNone.name == "noValue")
        }

        "nested collection fields" in {
            val record1 = "id" ~ 1 & "value" ~ "one"
            val record2 = "id" ~ 2 & "value" ~ "two"
            val record3 = "id" ~ 3 & "value" ~ "three"

            val listRecord = "items" ~ List(record1, record2, record3) & "count" ~ 3

            val items = listRecord.items
            assert(items.length == 3)
            assert(items(0).id == 1)
            assert(items(1).value == "two")
            assert(items(2).id == 3)

            // Map of records
            val mapRecord = "mapping" ~ Map(
                "first"  -> record1,
                "second" -> record2
            ) & "size" ~ 2

            val mapping = mapRecord.mapping
            assert(mapping.size == 2)
            assert(mapping("first").id == 1)
            assert(mapping("second").value == "two")
        }

        "paths with mixed nested records and case classes" in {
            case class Location(lat: Double, lng: Double)
            case class Address(street: String, location: Location)
            case class Company(name: String, address: Address)

            val company       = Company("Acme", Address("123 Main St", Location(37.7749, -122.4194)))
            val companyRecord = Record.fromProduct(company)

            val department = "id" ~ 42 & "title" ~ "Engineering"
            val employee   = "name" ~ "Alice" & "company" ~ companyRecord & "department" ~ department

            assert(employee.name == "Alice")
            assert(employee.company.name == "Acme")
            assert(employee.company.address.street == "123 Main St")
            assert(employee.company.address.location.lat == 37.7749)
            assert(employee.company.address.location.lng == -122.4194)
            assert(employee.department.id == 42)
            assert(employee.department.title == "Engineering")
        }
    }
end RecordTest
