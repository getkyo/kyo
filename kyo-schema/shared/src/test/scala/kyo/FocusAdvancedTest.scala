package kyo

import Record.*

class FocusAdvancedTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    val person     = MTPerson("Alice", 30)
    val address    = MTAddress("123 Main St", "Portland", "97201")
    val personAddr = MTPersonAddr("Alice", 30, address)
    val team       = MTTeam("Engineering", personAddr, List(personAddr))
    val company    = MTCompany("Acme", team)

    val circle    = MTCircle(5.0)
    val rectangle = MTRectangle(3.0, 4.0)
    val circleDrw = MTDrawing("Circle Art", circle)
    val rectDrw   = MTDrawing("Rect Art", rectangle)

    val order = MTOrder(1, List(MTItem("Widget", 9.99), MTItem("Gadget", 19.99)))

    val eachItem1 = MTEachItem("Widget", 9.99, Seq("electronics", "sale"))
    val eachItem2 = MTEachItem("Gadget", 19.99, Seq("electronics"))
    val eachItem3 = MTEachItem("Doohickey", 29.99, Seq("tools", "new"))

    val eachOrder2Items = MTEachOrder(1, Seq(eachItem1, eachItem2), "rush")
    val eachOrder       = MTEachOrder(1, Seq(eachItem1, eachItem2, eachItem3), "rush")

    val eachOrderSingle = MTEachOrder(2, Seq(eachItem3), "standard")
    val eachWarehouse   = MTWarehouse("Main", Seq(eachOrder2Items, eachOrderSingle))

    val compAddress1 = MTAddress("100 Elm St", "Portland", "97201")
    val compAddress2 = MTAddress("200 Oak Ave", "Seattle", "98101")
    val compAddress3 = MTAddress("300 Pine Rd", "Boston", "02101")

    val compLead    = MTPersonAddr("Alice", 40, compAddress1)
    val compMember1 = MTPersonAddr("Bob", 30, compAddress2)
    val compMember2 = MTPersonAddr("Carol", 25, compAddress3)

    val compTeam    = MTTeam("Engineering", compLead, List(compMember1, compMember2))
    val compCompany = MTCompany("Acme", compTeam)
    val compDept    = MTDepartment("R&D", compTeam)

    val compGallery = MTGallery("Modern Art", Seq(circleDrw, rectDrw, MTDrawing("Big Circle", MTCircle(10.0))))

    val compItem1     = MTEachItem("Widget", 9.99, Seq("electronics"))
    val compItem2     = MTEachItem("Gadget", 19.99, Seq("electronics"))
    val compItem3     = MTEachItem("Doohickey", 29.99, Seq("tools"))
    val compOrder1    = MTEachOrder(1, Seq(compItem1, compItem2), "rush")
    val compOrder2    = MTEachOrder(2, Seq(compItem3), "normal")
    val compWarehouse = MTWarehouse("Main", Seq(compOrder1, compOrder2))

    "composition" - {

        "two-step focus: schema.focus(_.address).focus(_.city).get returns city" in {
            val f = Schema[MTPersonAddr].focus(_.address).focus(_.city)
            assert(f.get(personAddr) == "Portland")
        }

        "two-step focus: schema.focus(_.address).focus(_.city).set updates city" in {
            val f      = Schema[MTPersonAddr].focus(_.address).focus(_.city)
            val result = f.set(personAddr, "Seattle")
            assert(result == MTPersonAddr("Alice", 30, MTAddress("123 Main St", "Seattle", "97201")))
        }

        "four-step focus: schema.focus(_.hq).focus(_.lead).focus(_.address).focus(_.city).get returns correct value" in {
            val f = Schema[MTCompany].focus(_.hq).focus(_.lead).focus(_.address).focus(_.city)
            assert(f.get(company) == "Portland")
        }

        "stored focus reuse: same focus instance works for get, set, update" in {
            val f = Schema[MTPersonAddr].focus(_.address).focus(_.city)
            assert(f.get(personAddr) == "Portland")
            val updated = f.set(personAddr, "Seattle")
            assert(f.get(updated) == "Seattle")
            val modified = f.update(personAddr)(_.toUpperCase)
            assert(f.get(modified) == "PORTLAND")
        }

        "focus then foreach: focus(_.hq).foreach(_.members).get returns all members" in {
            val f      = Schema[MTCompany].focus(_.hq).foreach(_.members)
            val result = f.get(compCompany)
            assert(result == Chunk(compMember1, compMember2))
        }

        "focus then foreach then focus: focus(_.hq).foreach(_.members).focus(_.name).get returns member names" in {
            val f      = Schema[MTCompany].focus(_.hq).foreach(_.members).focus(_.name)
            val result = f.get(compCompany)
            assert(result == Chunk("Bob", "Carol"))
        }

        "focus then foreach then focus: focus(_.team).foreach(_.members).focus(_.address.city).get returns Chunk of cities" in {
            val f      = Schema[MTDepartment].focus(_.team).foreach(_.members).focus(_.address).focus(_.city)
            val result = f.get(compDept)
            assert(result == Chunk("Seattle", "Boston"))
        }

        "sum type: focus(_.shape).focus(_.MTCircle) returns Maybe(circle) for circle drawing" in {
            val f      = Schema[MTDrawing].focus(_.shape).focus(_.MTCircle)
            val result = f.get(circleDrw)
            assert(result == Maybe(circle))
        }

        "sum type: single-path vs chained path produce same result" in {
            val f1 = Schema[MTDrawing].focus(_.shape.MTCircle.radius)
            val f2 = Schema[MTDrawing].focus(_.shape).focus(_.MTCircle).focus(_.radius)
            assert(f1.get(circleDrw) == f2.get(circleDrw))
            assert(f1.get(circleDrw) == Maybe(5.0))
        }

        "sum type: update on non-matching variant is a no-op" in {
            val f      = Schema[MTDrawing].focus(_.shape.MTCircle.radius)
            val result = f.update(rectDrw)(_ * 2)
            assert(result == rectDrw)
        }

        "foreach over drawings then focus through sum: get returns Chunk of circle radii only" in {
            val f      = Schema[MTGallery].foreach(_.drawings).focus(_.shape.MTCircle.radius)
            val result = f.get(compGallery)
            assert(result == Chunk(5.0, 10.0))
        }

        "foreach over drawings then focus through sum: Rectangles produce nothing (Maybe flattening)" in {
            val f      = Schema[MTGallery].foreach(_.drawings).focus(_.shape.MTCircle.radius)
            val result = f.get(compGallery)
            assert(result.size == 2) // only 2 circles, rectangle excluded
        }

        "foreach over drawings then focus through sum: update doubles radii on circles-only gallery" in {
            val circlesOnlyGallery = MTGallery("Circles", Seq(circleDrw, MTDrawing("Big Circle", MTCircle(10.0))))
            val f                  = Schema[MTGallery].foreach(_.drawings).focus(_.shape.MTCircle.radius)
            val result             = f.update(circlesOnlyGallery)(_ * 2)
            val drawings           = result.drawings
            assert(drawings(0).shape == MTCircle(10.0))
            assert(drawings(1).shape == MTCircle(20.0))
        }

        "double foreach update: foreach(_.orders).foreach(_.items).update applies to all items" in {
            val f        = Schema[MTWarehouse].foreach(_.orders).foreach(_.items)
            val result   = f.update(compWarehouse)(item => item.copy(price = item.price * 1.1))
            val allItems = result.orders.flatMap(_.items)
            assert(math.abs(allItems(0).price - 9.99 * 1.1) < 0.001)
            assert(math.abs(allItems(1).price - 19.99 * 1.1) < 0.001)
            assert(math.abs(allItems(2).price - 29.99 * 1.1) < 0.001)
        }

        "double foreach then focus update: foreach(_.orders).foreach(_.items).focus(_.price).update gives same result" in {
            val f1      = Schema[MTWarehouse].foreach(_.orders).foreach(_.items)
            val f2      = Schema[MTWarehouse].foreach(_.orders).foreach(_.items).focus(_.price)
            val result1 = f1.update(compWarehouse)(item => item.copy(price = item.price * 1.1))
            val result2 = f2.update(compWarehouse)(_ * 1.1)
            val items1  = result1.orders.flatMap(_.items).map(_.price)
            val items2  = result2.orders.flatMap(_.items).map(_.price)
            assert(items1.zip(items2).forall((a, b) => math.abs(a - b) < 0.001))
        }

        // --- deep focus chains ---

        "three-level deep focus chain: company.hq.lead.name" in {
            val f = Schema[MTCompany].focus(_.hq).focus(_.lead).focus(_.name)
            assert(f.get(compCompany) == "Alice")
            val updated = f.set(compCompany, "Zara")
            assert(f.get(updated) == "Zara")
        }

        "nested foreach: foreach(_.orders).foreach(_.items) get returns all items" in {
            val f      = Schema[MTWarehouse].foreach(_.orders).foreach(_.items)
            val result = f.get(compWarehouse)
            assert(result == Chunk(compItem1, compItem2, compItem3))
        }

        "product to variant to product: focus(_.shape).focus(_.MTCircle).focus(_.radius)" in {
            val f      = Schema[MTDrawing].focus(_.shape).focus(_.MTCircle).focus(_.radius)
            val result = f.get(circleDrw)
            assert(result == Maybe(5.0))
            val result2 = f.get(rectDrw)
            assert(result2 == Maybe.empty)
        }

        "multiple foreach with focus between: foreach(_.orders).focus(_.note).get returns Chunk of notes" in {
            val f      = Schema[MTWarehouse].foreach(_.orders).focus(_.note)
            val result = f.get(compWarehouse)
            assert(result == Chunk("rush", "normal"))
        }

        "path correctness through deep chains" in {
            val f1 = Schema[MTCompany].focus(_.hq).focus(_.lead).focus(_.address).focus(_.city)
            assert(f1.path == Seq("hq", "lead", "address", "city"))
            val f2 = Schema[MTWarehouse].foreach(_.orders).foreach(_.items).focus(_.name)
            assert(f2.path == Seq("orders", "items", "name"))
        }

        "foreach then variant: foreach(_.drawings).focus(_.shape).focus(_.MTCircle).focus(_.radius)" in {
            val f      = Schema[MTGallery].foreach(_.drawings).focus(_.shape).focus(_.MTCircle).focus(_.radius)
            val result = f.get(compGallery)
            assert(result == Chunk(5.0, 10.0))
        }

    }

    // --- maybe mode ---

    "maybe mode" - {

        "getOrElse returns radius for Circle drawing" in {
            val radius = Schema[MTDrawing].focus(_.shape.MTCircle.radius)
            val result = radius.getOrElse(circleDrw)(0.0)
            assert(result == 5.0)
        }

        "getOrElse returns default for Rectangle drawing" in {
            val radius = Schema[MTDrawing].focus(_.shape.MTCircle.radius)
            val result = radius.getOrElse(rectDrw)(0.0)
            assert(result == 0.0)
        }

        "isDefined returns true for Circle drawing" in {
            val radius = Schema[MTDrawing].focus(_.shape.MTCircle.radius)
            val result = radius.isDefined(circleDrw)
            assert(result)
        }

        "isDefined returns false for Rectangle drawing" in {
            val radius = Schema[MTDrawing].focus(_.shape.MTCircle.radius)
            val result = radius.isDefined(rectDrw)
            assert(!result)
        }

    }

    // --- edge cases ---

    "edge cases" - {

        "identity get" in {
            val m    = Schema[MTPerson]
            val name = m.focus(_.name)
            val age  = m.focus(_.age)
            assert(name.get(person) == "Alice")
            assert(age.get(person) == 30)
        }

        "identity set" in {
            val m        = Schema[MTPerson]
            val nameMeta = m.focus(_.name)
            val result   = nameMeta.set(person, "Bob")
            assert(result == MTPerson("Bob", 30))
        }

        "container field get" in {
            val result = Schema[MTOrder].focus(_.items).get(order)
            assert(result == List(MTItem("Widget", 9.99), MTItem("Gadget", 19.99)))
        }

        "container field set" in {
            val newItems = List(MTItem("Doohickey", 29.99))
            val result   = Schema[MTOrder].focus(_.items).set(order, newItems)
            assert(result == MTOrder(1, newItems))
        }

        "compose sequentially" in {
            val m    = Schema[MTPerson]
            val name = m.focus(_.name)
            val age  = m.focus(_.age)
            assert(name.get(person) == "Alice")
            assert(age.get(person) == 30)
            val updated = name.set(person, "Bob")
            assert(age.get(updated) == 30)
        }

        "multiple gets same meta" in {
            val city = Schema[MTPersonAddr].focus(_.address.city)
            val p1   = MTPersonAddr("A", 1, MTAddress("s1", "Portland", "z1"))
            val p2   = MTPersonAddr("B", 2, MTAddress("s2", "Seattle", "z2"))
            val p3   = MTPersonAddr("C", 3, MTAddress("s3", "Austin", "z3"))
            assert(city.get(p1) == "Portland")
            assert(city.get(p2) == "Seattle")
            assert(city.get(p3) == "Austin")
        }

        "generic pair second field" in {
            val pair   = MTPair(42, "hello")
            val result = Schema[MTPair[Int, String]].focus(_.second).get(pair)
            assert(result == "hello")
        }

        "deeply nested set preserves other fields" in {
            val result = Schema[MTCompany].focus(_.hq.lead.address.city).set(company, "Seattle")
            assert(result.name == "Acme")
            assert(result.hq.name == "Engineering")
            assert(result.hq.lead.name == "Alice")
            assert(result.hq.lead.age == 30)
            assert(result.hq.lead.address.street == "123 Main St")
            assert(result.hq.lead.address.city == "Seattle")
            assert(result.hq.lead.address.zip == "97201")
            assert(result.hq.members == List(personAddr))
        }

        "get option field" in {
            val opt    = MTOptional("Alice", Some("Ali"))
            val result = Schema[MTOptional].focus(_.nickname).get(opt)
            assert(result == Some("Ali"))
        }

        "set option field some" in {
            val opt    = MTOptional("Alice", Some("Ali"))
            val result = Schema[MTOptional].focus(_.nickname).set(opt, None)
            assert(result == MTOptional("Alice", None))
        }

        "set option field none to some" in {
            val opt    = MTOptional("Alice", None)
            val result = Schema[MTOptional].focus(_.nickname).set(opt, Some("Ali"))
            assert(result == MTOptional("Alice", Some("Ali")))
        }

        "get evil field named selectDynamic" in {
            val evil   = MTEvil("hello", 42, "/tmp", true)
            val result = Schema[MTEvil].focus(_.selectDynamic("selectDynamic")).get(evil)
            assert(result == true)
        }

        "set evil field named selectDynamic" in {
            val evil   = MTEvil("hello", 42, "/tmp", true)
            val result = Schema[MTEvil].focus(_.selectDynamic("selectDynamic")).set(evil, false)
            assert(result == MTEvil("hello", 42, "/tmp", false))
        }

        "update evil field via selectDynamic" in {
            val evil   = MTEvil("hello", 42, "/tmp", true)
            val result = Schema[MTEvil].focus(_.selectDynamic("selectDynamic")).update(evil)(!_)
            assert(result == MTEvil("hello", 42, "/tmp", false))
        }

        "evil field get via focus" in {
            val evil   = MTEvil("hello", 42, "/tmp", true)
            val result = Schema[MTEvil].focus(_.get).get(evil)
            assert(result == "hello")
        }

        "evil field set via focus" in {
            val evil   = MTEvil("hello", 42, "/tmp", true)
            val result = Schema[MTEvil].focus(_.set).set(evil, 99)
            assert(result == MTEvil("hello", 99, "/tmp", true))
        }

        "evil field path via focus" in {
            val evil = MTEvil("hello", 42, "/tmp", true)
            assert(Schema[MTEvil].focus(_.path).path == List("path"))
        }

        "evil field selectDynamic via focus" in {
            val evil   = MTEvil("hello", 42, "/tmp", true)
            val result = Schema[MTEvil].focus(_.selectDynamic("selectDynamic")).get(evil)
            assert(result == true)
        }

        "evil field schema navigation" in {
            val m = Schema[MTEvil]
            assert(m.focus(_.get).tag =:= Tag[String])
            assert(m.focus(_.set).tag =:= Tag[Int])
            assert(m.focus(_.path).tag =:= Tag[String])
            val sdField = m.fieldDescriptors.find(_.name == "selectDynamic")
            assert(sdField.isDefined)
        }

        "all evil fields round-trip via result" in {
            val evil   = MTEvil("hello", 42, "/tmp", true)
            val record = Schema[MTEvil].toRecord(evil)
            assert(record.dict("get") == "hello")
            assert(record.dict("set") == 42)
            assert(record.dict("path") == "/tmp")
            assert(record.dict("selectDynamic") == true)
        }

        "optional detects Option field" in {
            val f = Schema[MTOptional].focus(_.nickname)
            assert(f.optional == true)
        }

        "optional detects non-optional field" in {
            val f = Schema[MTOptional].focus(_.name)
            assert(f.optional == false)
        }

        "doc on nested field does not overwrite root field with same name" in {
            case class Inner(name: String, value: Int)
            case class Outer(name: String, inner: Inner)
            given Schema[Inner] = Schema.derived[Inner]
            given Schema[Outer] = Schema.derived[Outer]

            val schema = Schema[Outer]
                .doc(_.name)("outer name doc")
                .doc(_.inner.name)("inner name doc")

            val rootDoc = schema.focus(_.name).doc
            assert(rootDoc == Maybe("outer name doc"), s"root name doc was: $rootDoc")

            val nestedDoc = schema.focus(_.inner.name).doc
            assert(nestedDoc == Maybe("inner name doc"), s"nested name doc was: $nestedDoc")
        }

        "deprecated on nested field does not overwrite root field with same name" in {
            case class Inner(name: String, value: Int)
            case class Outer(name: String, inner: Inner)
            given Schema[Inner] = Schema.derived[Inner]
            given Schema[Outer] = Schema.derived[Outer]

            val schema = Schema[Outer]
                .deprecated(_.name)("root deprecated")
                .deprecated(_.inner.name)("inner deprecated")

            val rootDep = schema.focus(_.name).deprecated
            assert(rootDep == Maybe("root deprecated"), s"root deprecated was: $rootDep")

            val nestedDep = schema.focus(_.inner.name).deprecated
            assert(nestedDep == Maybe("inner deprecated"), s"nested deprecated was: $nestedDep")
        }

        "constraints on nested field do not collide with root field" in {
            case class Inner(score: Int)
            case class Outer(score: Int, inner: Inner)
            given Schema[Inner] = Schema.derived[Inner]
            given Schema[Outer] = Schema.derived[Outer]

            val schema = Schema[Outer]
                .checkMin(_.score)(0.0)
                .checkMax(_.score)(100.0)
                .checkMin(_.inner.score)(10.0)
                .checkMax(_.inner.score)(50.0)

            val rootConstraints   = schema.focus(_.score).constraints
            val nestedConstraints = schema.focus(_.inner.score).constraints

            assert(rootConstraints.size == 2, s"root constraints: $rootConstraints")
            assert(nestedConstraints.size == 2, s"nested constraints: $nestedConstraints")
            assert(
                rootConstraints != nestedConstraints,
                s"root and nested constraints should differ: root=$rootConstraints, nested=$nestedConstraints"
            )
        }

        "fieldId on nested field does not overwrite root field with same name" in {
            case class Inner(id: Int, value: String)
            case class Outer(id: Int, inner: Inner)
            given Schema[Inner] = Schema.derived[Inner]
            given Schema[Outer] = Schema.derived[Outer]

            val schema = Schema[Outer]
                .fieldId(_.id)(100)
                .fieldId(_.inner.id)(200)

            val rootId   = schema.focus(_.id).fieldId
            val nestedId = schema.focus(_.inner.id).fieldId

            assert(rootId == 100, s"root fieldId was: $rootId")
            assert(nestedId == 200, s"nested fieldId was: $nestedId")
        }

    }

    // --- metadata ---

    "metadata" - {

        "focus metadata: tag" in {
            val f = Schema[MTPerson].focus(_.name)
            assert(f.tag =:= Tag[String])
        }

        "focus metadata: description" in {
            val s = Schema[MTPerson].doc(_.name)("Full name")
            val f = s.focus(_.name)
            assert(f.doc == Maybe("Full name"))
        }

        "focus metadata: description empty when not set" in {
            val f = Schema[MTPerson].focus(_.name)
            assert(f.doc == Maybe.empty)
        }

        "focus metadata: root description still works" in {
            val s = Schema[MTPerson].doc("A person")
            assert(s.doc == Maybe("A person"))
        }

        "focus metadata: default" in {
            val f = Schema[MTConfig].focus(_.port)
            assert(f.default == Maybe(8080))
        }

        "focus metadata: default empty for non-default field" in {
            val f = Schema[MTConfig].focus(_.host)
            assert(f.default == Maybe.empty)
        }

        "focus metadata: optional" in {
            val f = Schema[MTOptional].focus(_.nickname)
            assert(f.optional == true)
            assert(Schema[MTOptional].focus(_.name).optional == false)
        }

        "focus metadata: constraints" in {
            val s = Schema[MTPerson].checkMin(_.age)(0).checkMax(_.age)(150)
            val f = s.focus(_.age)
            assert(f.constraints.size == 2)
        }

        "focus metadata: constraints empty for unmatched field" in {
            val s = Schema[MTPerson].checkMin(_.age)(0)
            val f = s.focus(_.name)
            assert(f.constraints.size == 0)
        }

        "focus metadata: path returns List" in {
            val f = Schema[MTPersonAddr].focus(_.address.city)
            assert(f.path == List("address", "city"))
        }

        "partial focus metadata: tag" in {
            val f = Schema[MTDrawing].focus(_.shape.MTCircle)
            assert(f.tag =:= Tag[MTCircle])
        }

        "partial focus metadata: path" in {
            val f = Schema[MTDrawing].focus(_.shape.MTCircle.radius)
            assert(f.path == List("shape", "MTCircle", "radius"))
        }

    }

    // --- transformed fields ---

    "transformed fields" - {

        "focus on renamed field get" in {
            val m = Schema[MTPerson].rename("name", "userName")
            val f = m.focus(_.userName)
            assert(f.get(person) == "Alice")
        }

        "focus on renamed field set" in {
            val m      = Schema[MTPerson].rename("name", "userName")
            val f      = m.focus(_.userName)
            val result = f.set(person, "Bob")
            assert(result == MTPerson("Bob", 30))
        }

        "focus on renamed field path" in {
            val m = Schema[MTPerson].rename("name", "userName")
            val f = m.focus(_.userName)
            assert(f.path == List("userName"))
        }

        "focus on non-renamed field after rename" in {
            val m = Schema[MTPerson].rename("name", "userName")
            val f = m.focus(_.age)
            assert(f.get(person) == 30)
        }

        "focus on added computed field get" in {
            val m = Schema[MTPerson].add("greeting")((p: MTPerson) => s"Hello ${p.name}")
            val f = m.focus(_.greeting)
            assert(f.get(person) == "Hello Alice")
        }

        "focus on added computed field path" in {
            val m = Schema[MTPerson].add("greeting")((p: MTPerson) => s"Hello ${p.name}")
            val f = m.focus(_.greeting)
            assert(f.path == List("greeting"))
        }

        "focus on added computed field set is no-op" in {
            val m      = Schema[MTPerson].add("greeting")((p: MTPerson) => s"Hello ${p.name}")
            val f      = m.focus(_.greeting)
            val result = f.set(person, "Goodbye")
            assert(result == person)
        }

        "chained rename then focus" in {
            val m = Schema[MTPerson].rename("name", "userName").rename("age", "userAge")
            val f = m.focus(_.userName)
            assert(f.get(person) == "Alice")
        }

        "chained rename then focus other field" in {
            val m = Schema[MTPerson].rename("name", "userName").rename("age", "userAge")
            val f = m.focus(_.userAge)
            assert(f.get(person) == 30)
        }

        "focus on original name after rename should not compile" in {
            typeCheckFailure(
                """kyo.Schema[kyo.MTPerson].rename("name", "userName").focus(_.name)"""
            )("not found")
        }

        "focus on renamed field update" in {
            val m      = Schema[MTPerson].rename("name", "userName")
            val f      = m.focus(_.userName)
            val result = f.update(person)(_.toUpperCase)
            assert(result == MTPerson("ALICE", 30))
        }

        "check on renamed field" in {
            val m      = Schema[MTPerson].rename("name", "userName")
            val errors = m.check(_.userName)(_.nonEmpty, "name required").validate(person)
            assert(errors.isEmpty)
        }

        "check on renamed field fails correctly" in {
            val m      = Schema[MTPerson].rename("name", "userName")
            val errors = m.check(_.userName)(_.isEmpty, "must be empty").validate(person)
            assert(errors.size == 1)
        }

    }

    // --- compile errors ---

    "compile errors" - {

        "nonexistent field compile error" in {
            typeCheckFailure("Schema[kyo.MTPerson].focus(_.nonexistent)")("not found")
        }

        "nested on primitive compile error" in {
            typeCheckFailure("Schema[kyo.MTPerson].focus(_.name.something)")("not found")
        }

        "leaf after primitive compile error" in {
            typeCheckFailure("Schema[kyo.MTPerson].focus(_.age.anything)")("not found")
        }

        "wrong variant name compile error" in {
            typeCheckFailure("Schema[kyo.MTDrawing].focus(_.shape.NonExistent)")("not found")
        }

        "set type mismatch compile error" in {
            typeCheckFailure("Schema[kyo.MTPerson].focus(_.age).set(kyo.MTPerson(\"a\", 1), \"string\")")("Required: Int")
        }

        "update type mismatch compile error" in {
            typeCheckFailure("Schema[kyo.MTPerson].focus(_.name).update(kyo.MTPerson(\"a\", 1))((x: Int) => x + 1)")(
                "Required: String => String"
            )
        }

        "getOrElse on Id-mode Focus fails to compile" in {
            typeCheckFailure(
                "Schema[kyo.MTPerson].focus(_.name).getOrElse(kyo.MTPerson(\"Alice\", 30))(\"\")"
            )("Maybe-mode Focus")
        }

        "getOrElse on Chunk-mode Focus fails to compile" in {
            typeCheckFailure(
                "Schema[kyo.MTEachOrder].foreach(_.items).getOrElse(kyo.MTEachOrder(1, Seq.empty, \"\"))(kyo.MTEachItem(\"\", 0.0, Seq.empty))"
            )("Maybe-mode Focus")
        }

    }

end FocusAdvancedTest
