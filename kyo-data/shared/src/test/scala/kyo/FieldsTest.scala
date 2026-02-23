package kyo

import Record.*
import scala.language.implicitConversions

case class Person(name: String, age: Int)
case class Point(x: Int, y: Int)
case class Wrapper[A](value: A, label: String)

class FieldsTest extends Test:

    // --- Intersection type tests (existing behavior) ---

    "intersection: Fields.names" in {
        val names = Fields.names["name" ~ String & "age" ~ Int]
        assert(names == Set("name", "age"))
    }

    "intersection: Fields.fields" in {
        val fs = Fields.fields["name" ~ String & "age" ~ Int]
        assert(fs.size == 2)
        assert(fs.map(_.name).toSet == Set("name", "age"))
    }

    "intersection: Have resolves value type" in {
        val have = summon[Fields.Have["name" ~ String & "age" ~ Int, "name"]]
        assert(true)
    }

    // --- Case class support ---

    "case class: Fields.names" in {
        val names = Fields.names[Person]
        assert(names == Set("name", "age"))
    }

    "case class: Fields.fields" in {
        val fs = Fields.fields[Person]
        assert(fs.size == 2)
        assert(fs.map(_.name).toSet == Set("name", "age"))
    }

    "case class: Have resolves value type" in {
        val have                                                   = summon[Fields.Have[Person, "name"]]
        val _: Fields.Have[Person, "name"] { type Value = String } = have
        succeed
    }

    "case class: Have resolves all fields" in {
        summon[Fields.Have[Person, "name"]]
        summon[Fields.Have[Person, "age"]]
        succeed
    }

    "case class: Have rejects missing field" in {
        typeCheckFailure("""summon[Fields.Have[Person, "missing"]]""")("Fields.Have")
    }

    "case class: Fields.fields has correct tags" in {
        val fs        = Fields.fields[Person]
        val nameField = fs.find(_.name == "name").get
        assert(nameField.tag =:= Tag[String])
        val ageField = fs.find(_.name == "age").get
        assert(ageField.tag =:= Tag[Int])
    }

    "case class: generic case class" in {
        val names = Fields.names[Wrapper[Int]]
        assert(names == Set("value", "label"))
    }

    "case class: generic Have resolves parameterized type" in {
        val have                                                       = summon[Fields.Have[Wrapper[Int], "value"]]
        val _: Fields.Have[Wrapper[Int], "value"] { type Value = Int } = have
        succeed
    }

    "case class: Comparable" in {
        val _ = summon[Fields.Comparable[Person]]
        succeed
    }

    "case class: Point fields" in {
        val fs = Fields.fields[Point]
        assert(fs.size == 2)
        assert(fs.map(_.name).toSet == Set("x", "y"))
    }

    "case class: SummonAll" in {
        val renders = summon[Fields.SummonAll[Person, Render]]
        assert(renders.contains("name"))
        assert(renders.contains("age"))
    }

end FieldsTest
