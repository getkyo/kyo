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

    // // --- Large intersection types ---

    // "large intersection: 22 fields" in {
    //     type F = "f1" ~ Int & "f2" ~ Int & "f3" ~ Int & "f4" ~ Int & "f5" ~ Int &
    //         "f6" ~ Int & "f7" ~ Int & "f8" ~ Int & "f9" ~ Int & "f10" ~ Int &
    //         "f11" ~ Int & "f12" ~ Int & "f13" ~ Int & "f14" ~ Int & "f15" ~ Int &
    //         "f16" ~ Int & "f17" ~ Int & "f18" ~ Int & "f19" ~ Int & "f20" ~ Int &
    //         "f21" ~ Int & "f22" ~ Int
    //     val names = Fields.names[F]
    //     assert(names.size == 22)
    //     assert(names.contains("f1"))
    //     assert(names.contains("f22"))
    // }

    // "large intersection: 30 fields" in {
    //     type F = "f1" ~ Int & "f2" ~ Int & "f3" ~ Int & "f4" ~ Int & "f5" ~ Int &
    //         "f6" ~ Int & "f7" ~ Int & "f8" ~ Int & "f9" ~ Int & "f10" ~ Int &
    //         "f11" ~ Int & "f12" ~ Int & "f13" ~ Int & "f14" ~ Int & "f15" ~ Int &
    //         "f16" ~ Int & "f17" ~ Int & "f18" ~ Int & "f19" ~ Int & "f20" ~ Int &
    //         "f21" ~ Int & "f22" ~ Int & "f23" ~ Int & "f24" ~ Int & "f25" ~ Int &
    //         "f26" ~ Int & "f27" ~ Int & "f28" ~ Int & "f29" ~ Int & "f30" ~ Int
    //     val names = Fields.names[F]
    //     assert(names.size == 30)
    //     assert(names.contains("f1"))
    //     assert(names.contains("f30"))
    // }

    // "large intersection: field access" in {
    //     type F = "f1" ~ Int & "f2" ~ String & "f3" ~ Boolean & "f4" ~ Int & "f5" ~ Int &
    //         "f6" ~ Int & "f7" ~ Int & "f8" ~ Int & "f9" ~ Int & "f10" ~ Int &
    //         "f11" ~ Int & "f12" ~ Int & "f13" ~ Int & "f14" ~ Int & "f15" ~ Int &
    //         "f16" ~ Int & "f17" ~ Int & "f18" ~ Int & "f19" ~ Int & "f20" ~ Int &
    //         "f21" ~ Int & "f22" ~ Int & "f23" ~ Int & "f24" ~ Int & "f25" ~ Int
    //     val r = "f1" ~ 1 & "f2" ~ "hello" & "f3" ~ true & "f4" ~ 4 & "f5" ~ 5 &
    //         "f6" ~ 6 & "f7" ~ 7 & "f8" ~ 8 & "f9" ~ 9 & "f10" ~ 10 &
    //         "f11" ~ 11 & "f12" ~ 12 & "f13" ~ 13 & "f14" ~ 14 & "f15" ~ 15 &
    //         "f16" ~ 16 & "f17" ~ 17 & "f18" ~ 18 & "f19" ~ 19 & "f20" ~ 20 &
    //         "f21" ~ 21 & "f22" ~ 22 & "f23" ~ 23 & "f24" ~ 24 & "f25" ~ 25
    //     assert(r.f1 == 1)
    //     assert(r.f2 == "hello")
    //     assert(r.f3 == true)
    //     assert(r.f25 == 25)
    // }

    // "large intersection: SummonAll 30 fields" in {
    //     type F = "f1" ~ Int & "f2" ~ Int & "f3" ~ Int & "f4" ~ Int & "f5" ~ Int &
    //         "f6" ~ Int & "f7" ~ Int & "f8" ~ Int & "f9" ~ Int & "f10" ~ Int &
    //         "f11" ~ Int & "f12" ~ Int & "f13" ~ Int & "f14" ~ Int & "f15" ~ Int &
    //         "f16" ~ Int & "f17" ~ Int & "f18" ~ Int & "f19" ~ Int & "f20" ~ Int &
    //         "f21" ~ Int & "f22" ~ Int & "f23" ~ Int & "f24" ~ Int & "f25" ~ Int &
    //         "f26" ~ Int & "f27" ~ Int & "f28" ~ Int & "f29" ~ Int & "f30" ~ Int
    //     val renders = summon[Fields.SummonAll[F, Render]]
    //     assert(renders.contains("f1"))
    //     assert(renders.contains("f30"))
    // }

end FieldsTest
