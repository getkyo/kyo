package kyo

import Record.*

class FocusTest extends kyo.test.Test[Any]:

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

    // 2-item order (used by EachTest-derived tests)
    val eachOrder2Items = MTEachOrder(1, Seq(eachItem1, eachItem2), "rush")
    // 3-item order (used by FocusModeTest-derived tests)
    val eachOrder = MTEachOrder(1, Seq(eachItem1, eachItem2, eachItem3), "rush")

    val eachOrderSingle = MTEachOrder(2, Seq(eachItem3), "standard")
    val eachWarehouse   = MTWarehouse("Main", Seq(eachOrder2Items, eachOrderSingle))

    // Composition test data
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

    // --- get/set/update ---

    "get/set/update" - {

        "get single field" in {
            val result = Schema[MTPerson].focus(_.name).get(person)
            assert(result == "Alice")
        }

        "set single field" in {
            val result = Schema[MTPerson].focus(_.name).set(person, "Bob")
            assert(result == MTPerson("Bob", 30))
        }

        "update single field" in {
            val result = Schema[MTPerson].focus(_.age).update(person)(_ + 1)
            assert(result == MTPerson("Alice", 31))
        }

        "get nested field" in {
            val result = Schema[MTPersonAddr].focus(_.address.city).get(personAddr)
            assert(result == "Portland")
        }

        "set nested field" in {
            val result = Schema[MTPersonAddr].focus(_.address.city).set(personAddr, "Seattle")
            assert(result == MTPersonAddr("Alice", 30, MTAddress("123 Main St", "Seattle", "97201")))
        }

        "update nested field" in {
            val result = Schema[MTPersonAddr].focus(_.address.zip).update(personAddr)(_ + "-0001")
            assert(result == MTPersonAddr("Alice", 30, MTAddress("123 Main St", "Portland", "97201-0001")))
        }

        "get deeply nested" in {
            val result = Schema[MTCompany].focus(_.hq.lead.address.city).get(company)
            assert(result == "Portland")
        }

        "set deeply nested" in {
            val result = Schema[MTCompany].focus(_.hq.lead.address.city).set(company, "Seattle")
            val expected = MTCompany(
                "Acme",
                MTTeam("Engineering", MTPersonAddr("Alice", 30, MTAddress("123 Main St", "Seattle", "97201")), List(personAddr))
            )
            assert(result == expected)
        }

        "get returns direct value" in {
            val result: String = Schema[MTPerson].focus(_.name).get(person)
            assert(result == "Alice")
        }

        "reusable meta" in {
            val city = Schema[MTPersonAddr].focus(_.address.city)
            val p1   = MTPersonAddr("Alice", 30, MTAddress("1st", "Portland", "97201"))
            val p2   = MTPersonAddr("Bob", 25, MTAddress("2nd", "Seattle", "98101"))
            assert(city.get(p1) == "Portland")
            assert(city.get(p2) == "Seattle")
        }

        "get on generic case class" in {
            val pair   = MTPair(42, "hello")
            val result = Schema[MTPair[Int, String]].focus(_.first).get(pair)
            assert(result == 42)
        }

        "set on generic case class" in {
            val pair   = MTPair(42, "hello")
            val result = Schema[MTPair[Int, String]].focus(_.first).set(pair, 99)
            assert(result == MTPair(99, "hello"))
        }

        // Focus lambda navigation tests

        "focus single field get" in {
            val result = Schema[MTPerson].focus(_.name).get(person)
            assert(result == "Alice")
        }

        "focus single field set" in {
            val result = Schema[MTPerson].focus(_.name).set(person, "Bob")
            assert(result == MTPerson("Bob", 30))
        }

        "focus nested field get" in {
            val result = Schema[MTPersonAddr].focus(_.address.city).get(personAddr)
            assert(result == "Portland")
        }

        "focus nested field set" in {
            val result = Schema[MTPersonAddr].focus(_.address.city).set(personAddr, "Seattle")
            assert(result == MTPersonAddr("Alice", 30, MTAddress("123 Main St", "Seattle", "97201")))
        }

        "focus path" in {
            assert(Schema[MTPersonAddr].focus(_.address.city).path == List("address", "city"))
        }

        "focus reusable" in {
            val city = Schema[MTPersonAddr].focus(_.address.city)
            val p1   = MTPersonAddr("Alice", 30, MTAddress("1st", "Portland", "97201"))
            val p2   = MTPersonAddr("Bob", 25, MTAddress("2nd", "Seattle", "98101"))
            assert(city.get(p1) == "Portland")
            assert(city.get(p2) == "Seattle")
        }

        "focus then check" in {
            val errors = Schema[MTPerson].check(_.name)(_.nonEmpty, "required").validate(person)
            assert(errors.isEmpty)

            val errors2 = Schema[MTPerson].check(_.name)(_.isEmpty, "must be empty").validate(person)
            assert(errors2.size == 1)
            assert(errors2.head.message == "must be empty")
        }

        "focus then validate multi" in {
            val nameMeta = Schema[MTPerson].check(_.name)(_.nonEmpty, "name required")
            val ageMeta  = Schema[MTPerson].check(_.age)(_ > 0, "age positive")
            val combined = nameMeta.mergeChecks(ageMeta)
            val errors   = combined.validate(person)
            assert(errors.isEmpty)

            val badPerson = MTPerson("", -1)
            val errors2   = combined.validate(badPerson)
            assert(errors2.size == 2)
        }

        "drop then focus" in {
            val meta   = Schema[MTUser].drop("ssn")
            val result = meta.focus(_.name).get(MTUser("Alice", 30, "alice@example.com", "123-45-6789"))
            assert(result == "Alice")
        }

        "focus then schema" in {
            assert(Schema[MTPerson].focus(_.name).tag =:= Tag[String])
        }

        "focus equivalent to direct" in {
            val result = Schema[MTPerson].focus(_.name).get(person)
            assert(result == "Alice")
        }

        "focus deeply nested get" in {
            val result = Schema[MTCompany].focus(_.hq.lead.address.city).get(company)
            assert(result == "Portland")
        }

        "focus deeply nested set preserves structure" in {
            val result = Schema[MTCompany].focus(_.hq.lead.address.city).set(company, "Seattle")
            assert(result.name == "Acme")
            assert(result.hq.name == "Engineering")
            assert(result.hq.lead.name == "Alice")
            assert(result.hq.lead.age == 30)
            assert(result.hq.lead.address.street == "123 Main St")
            assert(result.hq.lead.address.city == "Seattle")
            assert(result.hq.lead.address.zip == "97201")
        }

        "path deeply nested (from segments)" in {
            assert(Schema[MTCompany].focus(_.hq.lead.address.city).path == List("hq", "lead", "address", "city"))
        }

        "field named get via focus update" in {
            val evil   = MTEvil("hello", 42, "/tmp", true)
            val result = Schema[MTEvil].focus(_.get).update(evil)(_.toUpperCase)
            assert(result == MTEvil("HELLO", 42, "/tmp", true))
        }

        "field named path via focus get" in {
            val evil   = MTEvil("hello", 42, "/tmp", true)
            val result = Schema[MTEvil].focus(_.path).get(evil)
            assert(result == "/tmp")
        }

        // Type-level Focus navigation tests

        "nav single field" in {
            val nav                               = FocusTest.dummyFocus[MTPerson, "name" ~ String & "age" ~ Int]
            val result                            = nav.name
            val _: Focus.Select[MTPerson, String] = result
            succeed("type-resolution compile check: the type ascription above is the verification; no concrete runtime value to assert")
        }

        "nav nested field" in {
            val nav  = FocusTest.dummyFocus[MTPersonAddr, "name" ~ String & "age" ~ Int & "address" ~ MTAddress]
            val addr = nav.address
            val _: Focus.Select[MTPersonAddr, MTAddress] = addr
            val city                                     = addr.city
            val _: Focus.Select[MTPersonAddr, String]    = city
            succeed("type-resolution compile check: the type ascription above is the verification; no concrete runtime value to assert")
        }

        "nav deeply nested" in {
            val nav                                = FocusTest.dummyFocus[MTCompany, "name" ~ String & "hq" ~ MTTeam]
            val zip                                = nav.hq.lead.address.zip
            val _: Focus.Select[MTCompany, String] = zip
            succeed("type-resolution compile check: the type ascription above is the verification; no concrete runtime value to assert")
        }

        "nav sum variant" in {
            val nav                                  = FocusTest.dummyFocus[MTDrawing, "title" ~ String & "shape" ~ MTShape]
            val circle                               = nav.shape.MTCircle
            val _: Focus.Select[MTDrawing, MTCircle] = circle
            succeed("type-resolution compile check: the type ascription above is the verification; no concrete runtime value to assert")
        }

        "nav sum variant field" in {
            val nav                                = FocusTest.dummyFocus[MTDrawing, "title" ~ String & "shape" ~ MTShape]
            val radius                             = nav.shape.MTCircle.radius
            val _: Focus.Select[MTDrawing, Double] = radius
            succeed("type-resolution compile check: the type ascription above is the verification; no concrete runtime value to assert")
        }

        "nav generic case class" in {
            val nav                                          = FocusTest.dummyFocus[MTPair[Int, String], "first" ~ Int & "second" ~ String]
            val first                                        = nav.first
            val _: Focus.Select[MTPair[Int, String], Int]    = first
            val second                                       = nav.second
            val _: Focus.Select[MTPair[Int, String], String] = second
            succeed("type-resolution compile check: the type ascription above is the verification; no concrete runtime value to assert")
        }

        "nav option field" in {
            case class MTOptionalLocal(name: String, nick: Option[String]) derives CanEqual
            val nav  = FocusTest.dummyFocus[MTOptionalLocal, "name" ~ String & "nick" ~ Option[String]]
            val nick = nav.nick
            val _: Focus.Select[MTOptionalLocal, Option[String]] = nick
            succeed("type-resolution compile check: the type ascription above is the verification; no concrete runtime value to assert")
        }

        "nav list field" in {
            val nav     = FocusTest.dummyFocus[MTTeam, "name" ~ String & "lead" ~ MTPersonAddr & "members" ~ List[MTPersonAddr]]
            val members = nav.members
            val _: Focus.Select[MTTeam, List[MTPersonAddr]] = members
            succeed("type-resolution compile check: the type ascription above is the verification; no concrete runtime value to assert")
        }

        "nav field named get" in {
            val nav = FocusTest.dummyFocus[MTEvil, "get" ~ String & "set" ~ Int & "path" ~ String & "selectDynamic" ~ Boolean]
            val get = nav.get
            val _: Focus.Select[MTEvil, String] = get
            succeed("type-resolution compile check: the type ascription above is the verification; no concrete runtime value to assert")
        }

        "nav field named set" in {
            val nav = FocusTest.dummyFocus[MTEvil, "get" ~ String & "set" ~ Int & "path" ~ String & "selectDynamic" ~ Boolean]
            val set = nav.set
            val _: Focus.Select[MTEvil, Int] = set
            succeed("type-resolution compile check: the type ascription above is the verification; no concrete runtime value to assert")
        }

        "nav field named path" in {
            val nav  = FocusTest.dummyFocus[MTEvil, "get" ~ String & "set" ~ Int & "path" ~ String & "selectDynamic" ~ Boolean]
            val path = nav.path
            val _: Focus.Select[MTEvil, String] = path
            succeed("type-resolution compile check: the type ascription above is the verification; no concrete runtime value to assert")
        }

        "nav nonexistent field compile error" in {
            typeCheckFailure(
                "kyo.Focus.Select.create[kyo.MTPerson, kyo.Record.~[\"name\", String] & kyo.Record.~[\"age\", Int]]((_: kyo.MTPerson) => kyo.Maybe.empty, (_: kyo.MTPerson, _: kyo.Record.~[\"name\", String] & kyo.Record.~[\"age\", Int]) => null.asInstanceOf[kyo.MTPerson], Nil).nonexistent"
            )(
                "not found"
            )
        }

        // NavMacro internals via Focus.Select usage

        "lambda single field type check" in {
            val nav                                  = FocusTest.dummyFocus[MTPerson, "name" ~ String & "age" ~ Int]
            val name: Focus.Select[MTPerson, String] = nav.name
            val age: Focus.Select[MTPerson, Int]     = nav.age
            succeed("type-resolution compile check: the type ascription above is the verification; no concrete runtime value to assert")
        }

        "lambda nested path type check" in {
            val nav = FocusTest.dummyFocus[MTPersonAddr, "name" ~ String & "age" ~ Int & "address" ~ MTAddress]
            val street: Focus.Select[MTPersonAddr, String] = nav.address.street
            val city: Focus.Select[MTPersonAddr, String]   = nav.address.city
            val zip: Focus.Select[MTPersonAddr, String]    = nav.address.zip
            succeed("type-resolution compile check: the type ascription above is the verification; no concrete runtime value to assert")
        }

        "lambda product getter generation" - {
            "type resolves correctly" in {
                val nav                               = FocusTest.dummyFocus[MTPerson, "name" ~ String & "age" ~ Int]
                val _: Focus.Select[MTPerson, String] = nav.name
                succeed("type-resolution compile check: the type ascription above is the verification; no concrete runtime value to assert")
            }
        }

        "lambda product setter generation" - {
            "type resolves correctly" in {
                val nav                            = FocusTest.dummyFocus[MTPerson, "name" ~ String & "age" ~ Int]
                val _: Focus.Select[MTPerson, Int] = nav.age
                succeed("type-resolution compile check: the type ascription above is the verification; no concrete runtime value to assert")
            }
        }

        "lambda nested getter generation" - {
            "type resolves correctly" in {
                val nav = FocusTest.dummyFocus[MTPersonAddr, "name" ~ String & "age" ~ Int & "address" ~ MTAddress]
                val _: Focus.Select[MTPersonAddr, String] = nav.address.city
                succeed("type-resolution compile check: the type ascription above is the verification; no concrete runtime value to assert")
            }
        }

        "lambda nested setter generation" - {
            "type resolves correctly" in {
                val nav = FocusTest.dummyFocus[MTPersonAddr, "name" ~ String & "age" ~ Int & "address" ~ MTAddress]
                val _: Focus.Select[MTPersonAddr, String] = nav.address.zip
                succeed("type-resolution compile check: the type ascription above is the verification; no concrete runtime value to assert")
            }
        }

        "lambda partial getter for sum" - {
            "type resolves correctly" in {
                val nav                                  = FocusTest.dummyFocus[MTDrawing, "title" ~ String & "shape" ~ MTShape]
                val _: Focus.Select[MTDrawing, MTCircle] = nav.shape.MTCircle
                succeed("type-resolution compile check: the type ascription above is the verification; no concrete runtime value to assert")
            }
        }

        "lambda setter for sum" - {
            "type resolves correctly" in {
                val nav                                     = FocusTest.dummyFocus[MTDrawing, "title" ~ String & "shape" ~ MTShape]
                val _: Focus.Select[MTDrawing, MTRectangle] = nav.shape.MTRectangle
                succeed("type-resolution compile check: the type ascription above is the verification; no concrete runtime value to assert")
            }
        }

        "lambda through sum then product" - {
            "type resolves correctly" in {
                val nav                                = FocusTest.dummyFocus[MTDrawing, "title" ~ String & "shape" ~ MTShape]
                val _: Focus.Select[MTDrawing, Double] = nav.shape.MTCircle.radius
                succeed("type-resolution compile check: the type ascription above is the verification; no concrete runtime value to assert")
            }
        }

        "lambda with field name collision type check" in {
            val nav = FocusTest.dummyFocus[MTEvil, "get" ~ String & "set" ~ Int & "path" ~ String & "selectDynamic" ~ Boolean]
            val get = nav.get
            val _: Focus.Select[MTEvil, String]  = get
            val set                              = nav.set
            val _: Focus.Select[MTEvil, Int]     = set
            val path                             = nav.path
            val _: Focus.Select[MTEvil, String]  = path
            val sd                               = nav.selectDynamic("selectDynamic")
            val _: Focus.Select[MTEvil, Boolean] = sd
            succeed("type-resolution compile check: the type ascription above is the verification; no concrete runtime value to assert")
        }

        "focus on sum type returns Focus with Maybe automatically" in {
            val radiusFocus = Schema[MTDrawing].focus(_.shape.MTCircle.radius)
            assert(radiusFocus.get(rectDrw) == Maybe.empty)
            assert(radiusFocus.get(circleDrw) == Maybe(5.0))
        }

    }

    // --- sum variants ---

    "sum variants" - {

        "get matching variant" in {
            val result = Schema[MTDrawing].focus(_.shape.MTCircle).get(circleDrw)
            assert(result == Maybe(MTCircle(5.0)))
        }

        "get non-matching variant" in {
            val result = Schema[MTDrawing].focus(_.shape.MTCircle).get(rectDrw)
            assert(result == Maybe.empty)
        }

        "get variant field" in {
            val result = Schema[MTDrawing].focus(_.shape.MTCircle.radius).get(circleDrw)
            assert(result == Maybe(5.0))
        }

        "get variant field non-matching" in {
            val result = Schema[MTDrawing].focus(_.shape.MTCircle.radius).get(rectDrw)
            assert(result == Maybe.empty)
        }

        "set variant" in {
            val result = Schema[MTDrawing].focus(_.shape.MTCircle).set(circleDrw, Maybe(MTCircle(10.0)))
            assert(result == MTDrawing("Circle Art", MTCircle(10.0)))
        }

        "set variant field" in {
            val result = Schema[MTDrawing].focus(_.shape.MTCircle.radius).set(circleDrw, Maybe(10.0))
            assert(result == MTDrawing("Circle Art", MTCircle(10.0)))
        }

        "update variant field" in {
            val result = Schema[MTDrawing].focus(_.shape.MTCircle.radius).update(circleDrw)(_ * 2.0)
            assert(result == MTDrawing("Circle Art", MTCircle(10.0)))
        }

        "update non-matching variant" in {
            val result = Schema[MTDrawing].focus(_.shape.MTCircle.radius).update(rectDrw)(_ * 2.0)
            assert(result == rectDrw)
        }

    }

    // --- path ---

    "path" - {

        "path single field" in {
            assert(Schema[MTPerson].focus(_.name).path == List("name"))
        }

        "path nested" in {
            assert(Schema[MTPersonAddr].focus(_.address.city).path == List("address", "city"))
        }

        "path deeply nested" in {
            assert(Schema[MTCompany].focus(_.hq.lead.address.city).path == List("hq", "lead", "address", "city"))
        }

        "path sum variant" in {
            assert(Schema[MTDrawing].focus(_.shape.MTCircle).path == List("shape", "MTCircle"))
        }

        "path sum variant field" in {
            assert(Schema[MTDrawing].focus(_.shape.MTCircle.radius).path == List("shape", "MTCircle", "radius"))
        }

        // Path correctness through composition

        "path: focus(_.address).focus(_.city).path equals Seq(\"address\", \"city\")" in {
            val f = Schema[MTPersonAddr].focus(_.address).focus(_.city)
            assert(f.path == Seq("address", "city"))
        }

        "path: foreach(_.orders).foreach(_.items).focus(_.price).path equals Seq(\"orders\", \"items\", \"price\")" in {
            val f = Schema[MTWarehouse].foreach(_.orders).foreach(_.items).focus(_.price)
            assert(f.path == Seq("orders", "items", "price"))
        }

        "path: focus(_.shape.MTCircle.radius).path equals Seq(\"shape\", \"MTCircle\", \"radius\")" in {
            val f = Schema[MTDrawing].focus(_.shape.MTCircle.radius)
            assert(f.path == Seq("shape", "MTCircle", "radius"))
        }

    }

    // --- foreach ---

    "foreach" - {

        "get extracts all elements from a collection field" in {
            val each   = Schema[MTEachOrder].foreach(_.items)
            val result = each.get(eachOrder2Items)
            assert(result == Chunk.from(Seq(eachItem1, eachItem2)))
        }

        "set replaces all elements preserving other fields" in {
            val each     = Schema[MTEachOrder].foreach(_.items)
            val newItems = Chunk(MTEachItem("New", 5.0, Seq.empty))
            val result   = each.set(eachOrder2Items, newItems)
            assert(result.id == 1)
            assert(result.note == "rush")
            assert(result.items == newItems.toSeq)
        }

        "update transforms each element independently" in {
            val each   = Schema[MTEachOrder].foreach(_.items)
            val result = each.update(eachOrder2Items)(item => item.copy(price = item.price * 2))
            assert(result.items.map(_.price) == Seq(19.98, 39.98))
            assert(result.id == 1)
            assert(result.note == "rush")
        }

        "focus into element field get" in {
            val prices = Schema[MTEachOrder].foreach(_.items).focus(_.price)
            val result = prices.get(eachOrder2Items)
            assert(result == Chunk(9.99, 19.99))
        }

        "focus into element field update" in {
            val twoItemOrder = MTEachOrder(1, Seq(eachItem1, eachItem2), "rush")
            val prices       = Schema[MTEachOrder].foreach(_.items).focus(_.price)
            val result       = prices.update(twoItemOrder)(_ * 1.1)
            assert(result.items(0).price == 9.99 * 1.1)
            assert(result.items(1).price == 19.99 * 1.1)
            assert(result.items(0).name == "Widget")
            assert(result.items(1).name == "Gadget")
        }

        "empty collection returns empty for get" in {
            val emptyOrder = MTEachOrder(99, Seq.empty, "empty")
            val each       = Schema[MTEachOrder].foreach(_.items)
            val result     = each.get(emptyOrder)
            assert(result.isEmpty)
        }

        "nested foreach flattens nested collections" in {
            val twoItemOrder = MTEachOrder(1, Seq(eachItem1, eachItem2), "rush")
            val tags         = Schema[MTEachOrder].foreach(_.items).foreach(_.tags)
            val result       = tags.get(twoItemOrder)
            assert(result == Chunk("electronics", "sale", "electronics"))
        }

        "Schema.check on collection validates all elements pass" in {
            val schema = Schema[MTEachOrder].check(_.items.forall(_.price > 0), "price must be positive")
            val errors = schema.validate(eachOrder2Items)
            assert(errors.isEmpty)
        }

        "Schema.check on collection detects invalid elements" in {
            val schema = Schema[MTEachOrder].check(_.items.forall(_.price > 0), "price must be positive")
            val badOrder = MTEachOrder(
                1,
                Seq(
                    MTEachItem("Free", 0.0, Seq.empty),
                    MTEachItem("Negative", -5.0, Seq.empty)
                ),
                "bad"
            )
            val errors = schema.validate(badOrder)
            assert(errors.size == 1)
            assert(errors.head.message == "price must be positive")
        }

        "Schema.check detects failing collection element" in {
            val schema = Schema[MTEachOrder].check(_.items.forall(_.price > 0), "price must be positive")
            val badOrder = MTEachOrder(
                1,
                Seq(
                    MTEachItem("Good", 10.0, Seq.empty),
                    MTEachItem("Bad", -1.0, Seq.empty),
                    MTEachItem("Also Bad", -2.0, Seq.empty)
                ),
                "test"
            )
            val errors = schema.validate(badOrder)
            assert(errors.size == 1)
            assert(errors.head.message == "price must be positive")
        }

        "update on empty collection returns root unchanged" in {
            val emptyOrder = MTEachOrder(99, Seq.empty, "empty")
            val each       = Schema[MTEachOrder].foreach(_.items)
            val result     = each.update(emptyOrder)(item => item.copy(price = 0))
            assert(result == emptyOrder)
        }

        "works with List-typed fields" in {
            val each   = Schema[MTListOrder].foreach(_.items)
            val lo     = MTListOrder(1, List(eachItem1, eachItem2))
            val result = each.get(lo)
            assert(result == Chunk.from(Seq(eachItem1, eachItem2)))
        }

        "works with Vector-typed fields" in {
            val each   = Schema[MTVecOrder].foreach(_.items)
            val vo     = MTVecOrder(1, Vector(eachItem1, eachItem2))
            val result = each.get(vo)
            assert(result == Chunk.from(Seq(eachItem1, eachItem2)))
        }

        "compile error when foreach used on non-collection field" in {
            typeCheckFailure("Schema[kyo.MTEachOrder].foreach(_.note)")("constraint <: Seq")
        }

        "nested foreach get across warehouse orders and items" in {
            val items  = Schema[MTWarehouse].foreach(_.orders).foreach(_.items)
            val result = items.get(eachWarehouse)
            // eachWarehouse = eachOrder2Items(item1, item2) + eachOrderSingle(item3) = 3 items
            assert(result == Chunk(eachItem1, eachItem2, eachItem3))
        }

        "nested foreach update across warehouse orders and items" in {
            val items    = Schema[MTWarehouse].foreach(_.orders).foreach(_.items)
            val result   = items.update(eachWarehouse)(item => item.copy(price = item.price + 1))
            val allItems = Schema[MTWarehouse].foreach(_.orders).foreach(_.items).get(result)
            assert(allItems.map(_.price) == Chunk(10.99, 20.99, 30.99))
            assert(result.name == "Main")
            assert(result.orders.size == 2)
            assert(result.orders(0).id == 1)
            assert(result.orders(1).id == 2)
        }

        "nested foreach: Schema.check validates elements across two levels" in {
            val schema = Schema[MTWarehouse].check(_.orders.forall(_.items.forall(_.price < 25)), "price too high")
            val errors = schema.validate(eachWarehouse)
            assert(errors.size == 1)
            assert(errors(0).message == "price too high")
        }

        "triple nesting: foreach.foreach.focus gets deeply nested values" in {
            val prices = Schema[MTWarehouse].foreach(_.orders).foreach(_.items).focus(_.price)
            val result = prices.get(eachWarehouse)
            assert(result == Chunk(9.99, 19.99, 29.99))
        }

        "triple nesting: foreach.foreach.focus modifies deeply nested values" in {
            val prices    = Schema[MTWarehouse].foreach(_.orders).foreach(_.items).focus(_.price)
            val result    = prices.update(eachWarehouse)(_ * 2)
            val newPrices = Schema[MTWarehouse].foreach(_.orders).foreach(_.items).focus(_.price).get(result)
            assert(newPrices == Chunk(9.99 * 2, 19.99 * 2, 29.99 * 2))
        }

        "multiple independent foreach calls work independently" in {
            val itemsEach  = Schema[MTWarehouse].foreach(_.orders).foreach(_.items)
            val ordersEach = Schema[MTWarehouse].foreach(_.orders)

            val items  = itemsEach.get(eachWarehouse)
            val orders = ordersEach.get(eachWarehouse)

            assert(items.size == 3)
            assert(orders.size == 2)

            val modified = ordersEach.update(eachWarehouse)(o => o.copy(note = "modified"))
            assert(modified.orders.forall(_.note == "modified"))
            assert(itemsEach.get(modified) == Chunk(eachItem1, eachItem2, eachItem3))
        }

        "multiple Schema.checks on nested collection fields compose correctly" in {
            val validated = Schema[MTWarehouse]
                .check(_.orders.forall(_.items.forall(_.name.nonEmpty)), "item name required")
                .check(_.orders.forall(_.items.forall(_.price > 0)), "price must be positive")

            val badWarehouse = MTWarehouse(
                "Bad",
                Seq(
                    MTEachOrder(
                        1,
                        Seq(
                            MTEachItem("", -1.0, Seq.empty),  // name empty AND price negative
                            MTEachItem("OK", 10.0, Seq.empty) // passes
                        ),
                        "note1"
                    ),
                    MTEachOrder(
                        2,
                        Seq(
                            MTEachItem("Fine", 5.0, Seq.empty), // passes
                            MTEachItem("", 0.0, Seq.empty)      // name empty AND price zero
                        ),
                        "note2"
                    )
                )
            )

            val errors = validated.validate(badWarehouse)
            assert(errors.size == 2)
            assert(errors.exists(_.message == "item name required"))
            assert(errors.exists(_.message == "price must be positive"))
        }

        "nested foreach update deeply nested field" in {
            val names    = Schema[MTWarehouse].foreach(_.orders).foreach(_.items).focus(_.name)
            val result   = names.update(eachWarehouse)(_.toUpperCase)
            val newNames = Schema[MTWarehouse].foreach(_.orders).foreach(_.items).focus(_.name).get(result)
            assert(newNames == Chunk("WIDGET", "GADGET", "DOOHICKEY"))
        }

        "Schema.check validates all items with combined predicate" in {
            val schema = Schema[MTEachOrder]
                .check(o => o.items.forall(item => item.price > 0 && item.name.nonEmpty), "item must have name and positive price")
            assert(schema.validate(eachOrder2Items).isEmpty)

            val badOrder = MTEachOrder(
                1,
                Seq(
                    MTEachItem("Good", 10.0, Seq.empty),
                    MTEachItem("", -1.0, Seq.empty),
                    MTEachItem("Also Good", 5.0, Seq.empty)
                ),
                "test"
            )
            val errors = schema.validate(badOrder)
            assert(errors.size == 1)
            assert(errors(0).message == "item must have name and positive price")
        }

        "Schema.check on collection detects any failing element" in {
            val schema = Schema[MTEachOrder].check(_.items.forall(_.price > 0), "price must be positive")
            val badOrder = MTEachOrder(
                1,
                Seq(
                    MTEachItem("A", 10.0, Seq.empty),
                    MTEachItem("B", -1.0, Seq.empty),
                    MTEachItem("C", -2.0, Seq.empty)
                ),
                "test"
            )
            val errors = schema.validate(badOrder)
            assert(errors.size == 1)
            assert(errors.head.message == "price must be positive")
        }

        "multiple Schema.checks compose for collection element validation" in {
            val schema = Schema[MTEachOrder]
                .check(_.items.forall(_.tags.nonEmpty), "item must have tags")
                .check(_.items.forall(_.price > 0), "price must be positive")
            val badOrder = MTEachOrder(
                1,
                Seq(
                    MTEachItem("A", -1.0, Seq.empty) // fails both checks
                ),
                "test"
            )
            val errors = schema.validate(badOrder)
            assert(errors.size == 2)
            assert(errors.exists(_.message == "item must have tags"))
            assert(errors.exists(_.message == "price must be positive"))
        }

        "Schema.check on nested collections detects failures" in {
            val schema = Schema[MTWarehouse].check(_.orders.forall(_.items.forall(_.price < 25)), "price too high")
            val errors = schema.validate(eachWarehouse)
            assert(errors.size == 1)
            assert(errors(0).message == "price too high")
        }

        "foreach get returns Chunk and set accepts Chunk" in {
            val each  = Schema[MTEachOrder].foreach(_.items)
            val items = each.get(eachOrder2Items)
            assert(items == Chunk(eachItem1, eachItem2))
            val result = each.set(eachOrder2Items, items)
            assert(each.get(result) == items)
        }

        "foreach exposes path" in {
            val each = Schema[MTEachOrder].foreach(_.items)
            assert(each.path == List("items"))
        }

        "foreach exposes path metadata" in {
            val each = Schema[MTEachOrder].foreach(_.items)
            assert(each.path == List("items"))
        }

    }

end FocusTest

object FocusTest:
    private[kyo] def dummyFocus[A, F]: Focus.Select[A, F] =
        Focus.Select.create[A, F](
            (_: A) => Maybe.empty,
            (_: A, _: F) => null.asInstanceOf[A],
            Nil
        )
end FocusTest
