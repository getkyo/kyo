package kyo

class RowTest extends Test:

    given [A]: CanEqual[A, A] = CanEqual.derived

    case class RowTestPerson(name: String, age: Int)

    "creation" - {

        "empty" in {
            val row = Row.empty
            typeCheck("val _: Row[NamedTuple.Empty] = row")
            assert(row.size == 0)
        }

        "from named tuple" in {
            val row = Row((name = "Alice", age = 30))
            typeCheck("val _: Row[(name: String, age: Int)] = row")
            assert(row.name == "Alice")
            assert(row.age == 30)
        }

        "from product" in {
            val row = Row.fromProduct(RowTestPerson("Alice", 30))
            typeCheck("val _: Row[(name: String, age: Int)] = row")
            assert(row.name == "Alice")
            assert(row.age == 30)
        }

        "fromRecord" in {
            val record = "name" ~ "Alice" & "age" ~ 30
            val row    = Row.fromRecord(record)
            assert(row.name == "Alice")
            assert(row.age == 30)
        }

        "fromRecord rejects duplicate field names" in {
            typeCheckFailure("""
                val record = "x" ~ 1 & "x" ~ "hello"
                Row.fromRecord(record)
            """)("duplicate field names")
        }
    }

    "field access" - {

        "single field" in {
            val row = Row((name = "Alice"))
            typeCheck("val _: Row[(name: String)] = row")
            assert(row.name == "Alice")
        }

        "multiple fields" in {
            val row = Row((name = "Alice", age = 30, active = true))
            typeCheck("val _: Row[(name: String, age: Int, active: Boolean)] = row")
            assert(row.name == "Alice")
            assert(row.age == 30)
            assert(row.active == true)
        }

        "preserves types" in {
            val row               = Row((name = "Alice", age = 30, scores = List(1, 2, 3)))
            val name: String      = row.name
            val age: Int          = row.age
            val scores: List[Int] = row.scores
            assert(name == "Alice")
            assert(age == 30)
            assert(scores == List(1, 2, 3))
        }

        "from product" in {
            val row = Row.fromProduct(RowTestPerson("Bob", 25))
            typeCheck("val _: Row[(name: String, age: Int)] = row")
            assert(row.name == "Bob")
            assert(row.age == 25)
        }

        "option fields" in {
            val row = Row((name = "Alice", email = Option("alice@test.com")))
            typeCheck("val _: Row[(name: String, email: Option[String])] = row")
            assert(row.name == "Alice")
            assert(row.email.contains("alice@test.com"))
        }

        "collection fields" in {
            val row = Row((tags = List("a", "b"), counts = Map("x" -> 1)))
            typeCheck("val _: Row[(tags: List[String], counts: Map[String, Int])] = row")
            assert(row.tags == List("a", "b"))
            assert(row.counts("x") == 1)
        }

        "repeated fields rejected" in {
            typeCheckFailure("Row((x = 1, x = \"hello\"))")("Duplicate tuple element name")
        }

        "field name shadows extension method" in {
            val row = Row((size = 42, name = "Alice"))
            assert(row(0) == 42)
            assert(row.name == "Alice")
        }
    }

    "add" - {

        "add field to existing" in {
            val row = Row((name = "Alice")).add("age", 30)
            typeCheck("val _: Row[(name: String, age: Int)] = row")
            assert(row.name == "Alice")
            assert(row.age == 30)
        }

        "add to empty" in {
            val row = Row.empty.add("x", 1)
            typeCheck("val _: Row[(x: Int)] = row")
            assert(row.x == 1)
        }
    }

    "fields" in {
        val row = Row((name = "Alice", age = 30, active = true))
        assert(row.fields == List("name", "age", "active"))
    }

    "values" in {
        val row = Row((name = "Alice", age = 30))
        val v   = row.values
        typeCheck("val _: (String, Int) = v")
        assert(v == ("Alice", 30))
    }

    "update" - {

        "update single field" in {
            val row     = Row((name = "Alice", age = 30))
            val updated = row.update("age", 31)
            typeCheck("val _: Row[(name: String, age: Int)] = updated")
            assert(updated.name == "Alice")
            assert(updated.age == 31)
        }

        "update first field" in {
            val row     = Row((name = "Alice", age = 30))
            val updated = row.update("name", "Bob")
            typeCheck("val _: Row[(name: String, age: Int)] = updated")
            assert(updated.name == "Bob")
            assert(updated.age == 30)
        }
    }

    "renameTo" in {
        val row     = Row((name = "Alice", age = 30))
        val renamed = row.renameTo[(label: String, years: Int)]
        typeCheck("val _: Row[(label: String, years: Int)] = renamed")
        assert(renamed.label == "Alice")
        assert(renamed.years == 30)
    }

    "mapFields" in {
        val row    = Row((name = "Alice", age = 30))
        val mapped = row.mapFields([t] => (field: Record.Field[?, t], value: t) => Option(value))
        typeCheck("""val _: Row[(name: Option[String], age: Option[Int])] = mapped""")
        assert(mapped.name == Some("Alice"))
        assert(mapped.age == Some(30))
    }

    "structural operations" - {

        "size" in {
            assert(Row.empty.size == 0)
            assert(Row((a = 1)).size == 1)
            assert(Row((a = 1, b = 2, c = 3)).size == 3)
        }

        "head" in {
            val row = Row((first = "a", second = "b"))
            assert(row.head == "a")
        }

        "last" in {
            val row = Row((first = "a", second = "b"))
            assert(row.last == "b")
        }

        "tail" in {
            val row = Row((first = "a", second = "b", third = "c"))
            val t   = row.tail
            typeCheck("val _: (String, String) = t")
            assert(t.head == "b")
            assert(t.last == "c")
            assert(t.size == 2)
        }

        "init" in {
            val row = Row((first = "a", second = "b", third = "c"))
            val i   = row.init
            typeCheck("val _: (String, String) = i")
            assert(i.head == "a")
            assert(i.last == "b")
            assert(i.size == 2)
        }

        "take" in {
            val row = Row((a = 1, b = 2, c = 3))
            val t   = row.take(2)
            assert(t.size == 2)
            assert(t(0) == 1)
            assert(t(1) == 2)
        }

        "drop" in {
            val row = Row((a = 1, b = 2, c = 3))
            val d   = row.drop(1)
            assert(d.size == 2)
            assert(d(0) == 2)
            assert(d(1) == 3)
        }

        "splitAt" in {
            val row           = Row((a = 1, b = 2, c = 3, d = 4))
            val (left, right) = row.splitAt(2)
            assert(left.size == 2)
            assert(right.size == 2)
            assert(left(0) == 1)
            assert(left(1) == 2)
            assert(right(0) == 3)
            assert(right(1) == 4)
        }

        "reverse" in {
            val row = Row((a = 1, b = 2, c = 3))
            val r   = row.reverse
            assert(r(0) == 3)
            assert(r(1) == 2)
            assert(r(2) == 1)
        }
    }

    "concat" - {

        "two rows" in {
            val r1     = Row((name = "Alice"))
            val r2     = Row((age = 30))
            val merged = r1 ++ r2
            typeCheck("val _: Row[(name: String, age: Int)] = merged")
            assert(merged.name == "Alice")
            assert(merged.age == 30)
        }

        "with empty" in {
            val r1     = Row((name = "Alice"))
            val merged = r1 ++ Row.empty
            assert(merged.name == "Alice")
        }

        "repeated fields rejected" in {
            typeCheckFailure("""
                val r1 = Row((x = 1))
                val r2 = Row((x = "hello"))
                r1 ++ r2
            """)("Disjoint")
        }
    }

    "conversions" - {

        "toList" in {
            val row = Row((a = 1, b = 2, c = 3))
            assert(row.toList == List(1, 2, 3))
        }

        "toArray" in {
            val row = Row((a = 1, b = 2, c = 3))
            val arr = row.toArray
            assert(arr.length == 3)
            assert(arr(0) == 1)
            assert(arr(1) == 2)
            assert(arr(2) == 3)
        }

        "toIArray" in {
            val row = Row((a = 1, b = 2, c = 3))
            val arr = row.toIArray
            assert(arr.length == 3)
            assert(arr(0) == 1)
            assert(arr(1) == 2)
            assert(arr(2) == 3)
        }

        "toSeqMap" in {
            val row = Row((name = "Alice", age = 30))
            val map = row.toSeqMap
            assert(map("name") == "Alice")
            assert(map("age") == 30)
            assert(map.keys.toList == List("name", "age"))
        }

        "toMap" in {
            val row = Row((name = "Alice", age = 30))
            val map = row.toMap
            assert(map(Record.Field("name", Tag[String])) == "Alice")
            assert(map(Record.Field("age", Tag[Int])) == 30)
        }

        "toSpan" in {
            val row  = Row((a = 1, b = 2, c = 3))
            val span = row.toSpan
            assert(span.size == 3)
            assert(span(0) == 1)
            assert(span(1) == 2)
            assert(span(2) == 3)
        }

        "toRecord" in {
            val row    = Row((name = "Alice", age = 30))
            val record = row.toRecord
            typeCheck("""val _: Record["name" ~ String & "age" ~ Int] = record""")
            assert(record.name == "Alice")
            assert(record.age == 30)
        }
    }

    "Render" in {
        val row      = Row((name = "Alice", age = 30))
        val rendered = Render.asText(row).show
        assert(rendered == "(name = Alice, age = 30)")
    }

    "map" in {
        val row    = Row((a = 1, b = 2, c = 3))
        val mapped = row.map([t] => (v: t) => Option(v))
        assert(mapped(0) == Some(1))
        assert(mapped(1) == Some(2))
        assert(mapped(2) == Some(3))
    }

    "zip" in {
        val r1     = Row((a = 1, b = 2))
        val r2     = Row((a = "x", b = "y"))
        val zipped = r1.zip(r2)
        assert(zipped(0) == (1, "x"))
        assert(zipped(1) == (2, "y"))
    }
    "stage" in {
        import Record.Field
        import scala.compiletime.summonInline

        case class AsColumn[A](typ: String)
        object AsColumn:
            given AsColumn[Int]    = AsColumn("bigint")
            given AsColumn[String] = AsColumn("text")

        case class Column[A](name: String)(using AsColumn[A])

        object ColumnInline extends Record.StageAs[[n, v] =>> Column[v]]:
            inline def stage[Name <: String, Value](field: Field[Name, Value]): Column[Value] =
                Column[Value](field.name)(using summonInline[AsColumn[Value]])

        val columns = Row.stage[(name: String, age: Int)](ColumnInline)
        assert(columns.name == Column[String]("name"))
        assert(columns.age == Column[Int]("age"))
    }

    "equality and hashCode" - {
        "equal rows with same fields" in {
            val r1 = Row((name = "Alice", age = 30))
            val r2 = Row((name = "Alice", age = 30))
            assert(r1 == r2)
            assert(r1.hashCode == r2.hashCode)
        }

        "not equal with different values" in {
            val r1 = Row((name = "Alice", age = 30))
            val r2 = Row((name = "Alice", age = 31))
            assert(r1 != r2)
        }

        "single field equality" in {
            val r1 = Row((x = 42))
            val r2 = Row((x = 42))
            assert(r1 == r2)
        }

        "empty row equality" in {
            val r1 = Row.empty
            val r2 = Row.empty
            assert(r1 == r2)
        }
    }

    "fromProduct edge cases" - {
        "nested case class" in {
            case class Inner(x: Int, y: Int)
            case class Outer(inner: Inner, name: String)
            val row = Row.fromProduct(Outer(Inner(1, 2), "test"))
            assert(row.inner.x == 1)
            assert(row.inner.y == 2)
            assert(row.name == "test")
        }

        "case class with option fields" in {
            case class User(name: String, email: Option[String])
            val r1 = Row.fromProduct(User("Alice", Some("a@b.com")))
            val r2 = Row.fromProduct(User("Bob", None))
            assert(r1.email.contains("a@b.com"))
            assert(r2.email.isEmpty)
        }

        "case class with collection fields" in {
            case class Team(name: String, members: List[String])
            val row = Row.fromProduct(Team("Eng", List("Alice", "Bob")))
            assert(row.name == "Eng")
            assert(row.members == List("Alice", "Bob"))
        }

        "empty case class" in {
            case class Empty()
            val row = Row.fromProduct(Empty())
            assert(row.size == 0)
        }
    }

    "fromRecord edge cases" - {
        "single field" in {
            val record = "name" ~ "Alice"
            val row    = Row.fromRecord(record)
            assert(row.name == "Alice")
        }

        "three fields" in {
            val record = "name" ~ "Alice" & "age" ~ 30 & "active" ~ true
            val row    = Row.fromRecord(record)
            assert(row.name == "Alice")
            assert(row.age == 30)
            assert(row.active == true)
        }

        "complex value types" in {
            val record = "items" ~ List(1, 2, 3) & "data" ~ Map("a" -> 1)
            val row    = Row.fromRecord(record)
            assert(row.items == List(1, 2, 3))
            assert(row.data == Map("a" -> 1))
        }
    }

    "Render edge cases" - {
        "empty row" in {
            val row      = Row.empty
            val rendered = Render.asText(row).show
            assert(rendered == "()")
        }

        "single field" in {
            val row      = Row((name = "Alice"))
            val rendered = Render.asText(row).show
            assert(rendered == "(name = Alice)")
        }

        "three fields" in {
            val row      = Row((a = 1, b = "hello", c = true))
            val rendered = Render.asText(row).show
            assert(rendered == "(a = 1, b = hello, c = true)")
        }
    }

    "roundtrip conversions" - {
        "Row -> Record -> Row" in {
            val original = Row((name = "Alice", age = 30))
            val record   = original.toRecord
            val back     = Row.fromRecord(record)
            assert(back.name == "Alice")
            assert(back.age == 30)
            assert(original == back)
        }

        "Record -> Row -> Record" in {
            val original = "name" ~ "Alice" & "age" ~ 30
            val row      = Row.fromRecord(original)
            val back     = row.toRecord
            assert(back.name == "Alice")
            assert(back.age == 30)
            assert(original == back)
        }
    }

    "add chaining" - {
        "add multiple fields" in {
            val row = Row.empty.add("a", 1).add("b", "hello").add("c", true)
            typeCheck("""val _: Row[(a: Int, b: String, c: Boolean)] = row""")
            assert(row.a == 1)
            assert(row.b == "hello")
            assert(row.c == true)
        }
    }

    "update edge cases" - {
        "update in row with 3+ fields" in {
            val row     = Row((a = 1, b = 2, c = 3))
            val updated = row.update("b", 20)
            assert(updated.a == 1)
            assert(updated.b == 20)
            assert(updated.c == 3)
        }

        "update last field" in {
            val row     = Row((a = 1, b = 2, c = 3))
            val updated = row.update("c", 30)
            assert(updated.a == 1)
            assert(updated.b == 2)
            assert(updated.c == 30)
        }

        "rejects non-existent field name" in {
            typeCheckFailure("""
                val row = Row((name = "Alice", age = 30))
                row.update("city", "Paris")
            """)("Required: Nothing")
        }

        "rejects wrong value type" in {
            typeCheckFailure("""
                val row = Row((name = "Alice", age = 30))
                row.update("age", "not an int")
            """)("Required: Int")
        }
    }

    "renameTo compile errors" - {
        "rejects different number of fields" in {
            typeCheckFailure("""
                val row = Row((name = "Alice", age = 30))
                row.renameTo[(label: String)]
            """)("Cannot rename: value types of source and target rows do not match")
        }
    }

    "values and fields on empty row" - {
        "fields on empty" in {
            val row = Row.empty
            assert(row.fields == Nil)
        }

        "values on empty" in {
            val row = Row.empty
            val v   = row.values
            assert(v == EmptyTuple)
        }
    }

    "mapFields with field info" - {
        "passes correct field names" in {
            val row        = Row((name = "Alice", age = 30))
            var fieldNames = List.empty[String]
            row.mapFields([t] =>
                (field: Record.Field[?, t], value: t) =>
                    fieldNames = fieldNames :+ field.name
                    Option(value))
            assert(fieldNames == List("name", "age"))
        }
    }

    "concat with 3+ rows" - {
        "preserves order" in {
            val r1     = Row((a = 1))
            val r2     = Row((b = 2))
            val r3     = Row((c = 3))
            val merged = r1 ++ r2 ++ r3
            typeCheck("val _: Row[(a: Int, b: Int, c: Int)] = merged")
            assert(merged.a == 1)
            assert(merged.b == 2)
            assert(merged.c == 3)
        }
    }

    "toSeqMap ordering with 3+ fields" in {
        val row = Row((a = 1, b = 2, c = 3, d = 4))
        val map = row.toSeqMap
        assert(map.keys.toList == List("a", "b", "c", "d"))
    }
end RowTest
