package kyo

import Json.JsonSchema
import Record.*
import Schema.*
import kyo.internal.CodecMacro
import kyo.internal.JsonReader
import kyo.internal.JsonWriter
import kyo.internal.ProtobufReader
import kyo.internal.ProtobufWriter
import kyo.internal.StructureValueReader
import kyo.internal.StructureValueWriter

class SchemaStructureTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    "ordering" - {

        "ordering sorts by first field" in {
            val ord     = Schema[MTPerson].order
            val persons = List(MTPerson("Charlie", 25), MTPerson("Alice", 30), MTPerson("Bob", 28))
            val sorted  = persons.sorted(using ord)
            assert(sorted.map(_.name) == List("Alice", "Bob", "Charlie"))
        }

        "ordering sorts by second field" in {
            val ord     = Schema[MTPerson].order
            val persons = List(MTPerson("Alice", 30), MTPerson("Alice", 25), MTPerson("Alice", 28))
            val sorted  = persons.sorted(using ord)
            assert(sorted.map(_.age) == List(25, 28, 30))
        }

        "ordering with sorted collection" in {
            val items  = List(MTItem("Banana", 1.50), MTItem("Apple", 2.00), MTItem("Cherry", 0.75))
            val sorted = items.sorted(using Schema[MTItem].order)
            assert(sorted.map(_.name) == List("Apple", "Banana", "Cherry"))
        }

        "ordering consistent with equals" in {
            val ord = Schema[MTPerson].order
            val p1  = MTPerson("Alice", 30)
            val p2  = MTPerson("Alice", 30)
            assert(ord.compare(p1, p2) == 0)
        }

        "ordering single field" in {
            val ord    = Schema[MTWrapper].order
            val values = List(MTWrapper("z"), MTWrapper("a"), MTWrapper("m"))
            val sorted = values.sorted(using ord)
            assert(sorted.map(_.value) == List("a", "m", "z"))
        }

        "ordering nested fields" in {
            val ord = Schema[MTPersonAddr].order
            val p1  = MTPersonAddr("Alice", 30, MTAddress("B St", "Springfield", "62701"))
            val p2  = MTPersonAddr("Alice", 30, MTAddress("A St", "Springfield", "62701"))
            // Same name and age, ordering falls through to address which is compared by its fields (street, city, zip), "A St" < "B St"
            val result = ord.compare(p1, p2)
            assert(result > 0) // "B St" > "A St"
        }
    }

    // =========================================================================
    // canEqual
    // =========================================================================

    "canEqual" - {

        "canEqual same values" in {
            val schema = Schema[MTPerson]
            import schema.canEqual
            val p1 = MTPerson("Alice", 30)
            val p2 = MTPerson("Alice", 30)
            assert(p1 == p2)
        }

        "canEqual different values" in {
            val schema = Schema[MTPerson]
            import schema.canEqual
            val p1 = MTPerson("Alice", 30)
            val p2 = MTPerson("Bob", 25)
            assert(p1 != p2)
        }

        "canEqual with given" in {
            val schema = Schema[MTItem]
            import schema.canEqual
            val i1 = MTItem("Widget", 9.99)
            val i2 = MTItem("Widget", 9.99)
            assert(i1 == i2)
        }

        "canEqual nested" in {
            val schema = Schema[MTPersonAddr]
            import schema.canEqual
            val p1 = MTPersonAddr("Alice", 30, MTAddress("Main St", "Springfield", "62701"))
            val p2 = MTPersonAddr("Alice", 30, MTAddress("Main St", "Springfield", "62701"))
            assert(p1 == p2)
        }
    }

    // =========================================================================
    // exceptions
    // =========================================================================

    "exceptions" - {

        "SchemaException hierarchy exists" in {
            val err = ValidationFailedException(Nil, "test")(using Frame.derive)
            assert(err.message == "test")
            assert(err.path == Nil)
            discard(summon[ValidationFailedException <:< SchemaException])
            discard(summon[ValidationFailedException <:< ValidationException])
        }

        "exception subtypes are distinct" in {
            given Frame = Frame.derive
            val exceptions: List[SchemaException] = List(
                MissingFieldException(Nil, "f"),
                TypeMismatchException(Nil, "A", "B"),
                UnknownVariantException(Nil, "V"),
                ValidationFailedException(Nil, "msg"),
                ParseException(Json(), "x", "Int"),
                TruncatedInputException(Protobuf(), "eof")
            )
            assert(exceptions.size == 6)
            assert(exceptions.map(_.getClass).distinct.size == 6)
        }

        "validate returns ValidationFailedException" in {
            val errors = Schema[MTPerson]
                .check(_.name)(_.nonEmpty, "name required")
                .validate(MTPerson("", 30))
            assert(errors.size == 1)
            val err: ValidationFailedException = errors.head
            discard(summon[ValidationFailedException <:< ValidationException])
            assert(err.message == "name required")
        }
    }

    // =========================================================================
    // derivation errors
    // =========================================================================

    "derivation errors" - {

        "plain class" in {
            typeCheckFailure("kyo.Schema.derived[kyo.MTOpaque]")(
                "not a case class or sealed trait"
            )
        }

        "open trait" in {
            typeCheckFailure("kyo.Schema.derived[kyo.MTOpenTrait]")(
                "not a case class or sealed trait"
            )
        }

        "primitive" in {
            typeCheckFailure("kyo.Schema.derived[Int]")(
                "not a case class or sealed trait"
            )
        }

        // Generic-derivation diagnostics: `Schema.derived` resolves each field via
        // `summonInline[Schema[ft]]`, so missing instances surface with the standard Scala 3
        // `No given instance of type` message. The macro deliberately does not add any
        // type-symbol-specific diagnostics on top, to honour the zero-specialization invariant.

        "field with non-derivable type" in {
            typeCheckFailure("kyo.Schema.derived[Tuple1[kyo.MTOpaque]]")(
                "No given instance of type"
            )
        }

        "field with non-derivable list element" in {
            typeCheckFailure(
                "case class T(items: List[kyo.MTOpaque]); kyo.Schema.derived[T]"
            )("No given instance of type")
        }

        "field with non-derivable option element" in {
            typeCheckFailure(
                "case class T(o: Option[kyo.MTOpaque]); kyo.Schema.derived[T]"
            )("No given instance of type")
        }

        "field with non-derivable map value" in {
            typeCheckFailure(
                "case class T(m: Map[String, kyo.MTOpaque]); kyo.Schema.derived[T]"
            )("No given instance of type")
        }

        "error names the missing Schema instance" in {
            typeCheckFailure("kyo.Schema.derived[Tuple1[kyo.MTOpaque]]")(
                "kyo.Schema"
            )
        }

        "error reports the unresolved field-element type" in {
            typeCheckFailure(
                "case class T(items: List[kyo.MTOpaque]); kyo.Schema.derived[T]"
            )("MTOpaque")
        }
    }

    // =========================================================================
    // integration
    // =========================================================================

    "integration" - {

        case class XCUser(id: Int, name: String, email: String, password: String, ssn: String) derives CanEqual
        case class XCUserDTO(id: Int, name: String, email: String) derives CanEqual
        case class XCItem(name: String, price: Double, quantity: Int) derives CanEqual
        case class XCOrder(id: Int, items: List[XCItem], customer: String) derives CanEqual
        case class XCNestedOrder(id: Int, orders: List[XCOrder]) derives CanEqual

        val alice = XCUser(1, "Alice", "alice@example.com", "secret123", "123-45-6789")
        val bob   = XCUser(2, "Bob", "bob@example.com", "hunter2", "987-65-4321")

        val item1 = XCItem("Widget", 9.99, 3)
        val item2 = XCItem("Gadget", 19.99, 1)
        val item3 = XCItem("Doohickey", 29.99, 5)

        val order = XCOrder(1, List(item1, item2), "Alice")

        // --- Metadata + JSON Schema ---

        "metadata: doc, field docs, deprecated all visible via schema" in {
            val s = Schema[XCUser]
                .doc("A registered user")
                .doc(_.name)("The user's full name")
                .doc(_.email)("Primary contact email")
                .deprecated(_.ssn)("Use taxId instead")
                .check(_.name)(_.nonEmpty, "name required")
                .check(_.email)(_.contains("@"), "invalid email")

            // Root doc accessible via schema
            assert(s.doc == Maybe("A registered user"))

            // Field docs accessible via focus
            assert(s.focus(_.name).doc == Maybe("The user's full name"))
            assert(s.focus(_.email).doc == Maybe("Primary contact email"))

            // Field deprecated accessible via focus
            assert(s.focus(_.ssn).deprecated == Maybe("Use taxId instead"))

            // Undocumented fields return empty
            assert(s.focus(_.id).doc == Maybe.empty)
            assert(s.focus(_.password).doc == Maybe.empty)
        }

        "metadata: examples accessible via schema" in {
            val s = Schema[XCUser]
                .doc("A registered user")
                .example(alice)
                .example(bob)

            assert(s.doc == Maybe("A registered user"))
            assert(s.examples == Chunk(alice, bob))
            assert(s.examples.size == 2)
            assert(s.examples(0).name == "Alice")
            assert(s.examples(1).name == "Bob")
        }

        "metadata + JSON Schema: JsonSchema.from includes structural info" in {
            val schema = JsonSchema.from[XCUser]

            schema match
                case obj: JsonSchema.Obj =>
                    assert(obj.properties.size == 5)
                    val propNames = obj.properties.map(_._1).toSet
                    assert(propNames == Set("id", "name", "email", "password", "ssn"))
                case other =>
                    fail(s"Expected Obj, got $other")
            end match
        }

        "metadata + reflect: reflect fields carry doc metadata from macro" in {
            val tpe = Structure.of[XCUser]
            tpe match
                case Structure.Type.Product(name, _, _, fields, _) =>
                    assert(name == "XCUser")
                    assert(fields.size == 5)
                    assert(fields.map(_.name) == Chunk("id", "name", "email", "password", "ssn"))
                    // Structure fields have correct types
                    fields.find(_.name == "name").get.fieldType match
                        case Structure.Type.Primitive(kind, _) => assert(kind == Structure.PrimitiveKind.String)
                        case other                             => fail(s"Expected Primitive for name, got $other")
                    fields.find(_.name == "id").get.fieldType match
                        case Structure.Type.Primitive(kind, _) => assert(kind == Structure.PrimitiveKind.Int)
                        case other                             => fail(s"Expected Primitive for id, got $other")
                case other =>
                    fail(s"Expected Product, got $other")
            end match
        }

        // --- Changeset + Convert ---

        "changeset + convert: compute changeset, apply, then forward via convert" in {
            val convert = Convert[XCUser, XCUserDTO](u => XCUserDTO(u.id, u.name, u.email))

            val updatedAlice = XCUser(1, "Alice Smith", "alice.smith@example.com", "secret123", "123-45-6789")
            val changeset    = Changeset(alice, updatedAlice)

            // Apply changeset to get the updated user
            val patched = changeset.applyTo(alice).getOrThrow
            assert(patched == updatedAlice)

            // Forward via convert to DTO
            val dto = convert(patched)
            assert(dto == XCUserDTO(1, "Alice Smith", "alice.smith@example.com"))
        }

        "changeset + convert: changeset on source, apply, forward to DTO preserves correct values" in {
            val convert = Convert[XCUser, XCUserDTO](u => XCUserDTO(u.id, u.name, u.email))

            // Change only name and email
            val newAlice  = alice.copy(name = "Alice Wonderland", email = "alice.wonder@example.com")
            val changeset = Changeset(alice, newAlice)

            // Apply changeset first
            val applied = changeset.applyTo(alice).getOrThrow
            assert(applied.name == "Alice Wonderland")
            assert(applied.email == "alice.wonder@example.com")
            assert(applied.id == 1) // unchanged

            // Convert to DTO
            val dto = convert(applied)
            assert(dto.id == 1)
            assert(dto.name == "Alice Wonderland")
            assert(dto.email == "alice.wonder@example.com")
        }

        "changeset + convert: multiple changesets composed with andThen, applied, then converted via convert" in {
            val convert = Convert[XCUser, XCUserDTO](u => XCUserDTO(u.id, u.name, u.email))

            // Step 1: change name
            val mid1       = alice.copy(name = "Alice B.")
            val changeset1 = Changeset(alice, mid1)

            // Step 2: change email
            val mid2       = mid1.copy(email = "alice.b@new.com")
            val changeset2 = Changeset(mid1, mid2)

            // Compose and apply
            val composed = changeset1.andThen(changeset2)
            val result   = composed.applyTo(alice).getOrThrow
            assert(result.name == "Alice B.")
            assert(result.email == "alice.b@new.com")
            assert(result.id == 1)

            // Forward through convert
            val dto = convert(result)
            assert(dto == XCUserDTO(1, "Alice B.", "alice.b@new.com"))
        }

        "changeset + convert: convert forward then changeset on original" in {
            val convert = Convert[XCUser, XCUserDTO](u => XCUserDTO(u.id, u.name, u.email))

            // Forward to DTO
            val dto = convert(alice)
            assert(dto == XCUserDTO(1, "Alice", "alice@example.com"))

            // Compute changeset on original user
            val updatedAlice = alice.copy(name = "Alice Updated")
            val changeset    = Changeset(alice, updatedAlice)
            assert(!changeset.isEmpty) // name changed
        }

        // --- Foreach + Validation (using Schema.check) ---

        "foreach + validation: Schema.check validates all elements" in {
            val orderSchema = Schema[XCOrder]
                .check(_.items.forall(_.price > 0), "price must be positive")
                .check(_.items.forall(_.quantity > 0), "quantity must be positive")

            // Valid order passes
            assert(orderSchema.validate(order).isEmpty)

            // Invalid order with bad items
            val badOrder = XCOrder(
                2,
                List(
                    XCItem("Free", 0.0, 1),
                    XCItem("Negative", -5.0, 0)
                ),
                "Bob"
            )
            val errors = orderSchema.validate(badOrder)
            assert(errors.size == 2)
            assert(errors.exists(_.message == "price must be positive"))
            assert(errors.exists(_.message == "quantity must be positive"))
        }

        "foreach + validation: multiple Schema.checks compose correctly" in {
            val schema = Schema[XCOrder]
                .check(_.items.forall(_.name.nonEmpty), "name required")
                .check(_.items.forall(_.price > 0), "price must be positive")
                .check(_.items.forall(_.quantity > 0), "quantity must be positive")

            val goodOrder = XCOrder(1, List(XCItem("Widget", 9.99, 3)), "Alice")
            assert(schema.validate(goodOrder).isEmpty)

            val badOrder = XCOrder(1, List(XCItem("", -1.0, 0)), "Alice")
            val errors   = schema.validate(badOrder)
            assert(errors.size == 3) // all three checks fail for the one item
        }

        "foreach + validation: empty collection validates without errors" in {
            val schema = Schema[XCOrder].check(_.items.forall(_.price > 0), "price must be positive")

            val emptyOrder = XCOrder(99, List.empty, "Nobody")
            assert(schema.validate(emptyOrder).isEmpty)
        }

        "foreach + validation: nested Schema.check validates across two levels" in {
            val nested = XCNestedOrder(
                1,
                List(
                    XCOrder(1, List(XCItem("Good", 10.0, 1), XCItem("Bad", -1.0, 0)), "Alice"),
                    XCOrder(2, List(XCItem("OK", 5.0, 2)), "Bob")
                )
            )

            val schema = Schema[XCNestedOrder]
                .check(_.orders.forall(_.items.forall(_.price > 0)), "price must be positive")

            val errors = schema.validate(nested)
            assert(errors.size == 1)
            assert(errors.head.message == "price must be positive")
        }

        // --- Lambda transforms + format ---

        "lambda transforms + convert[DTO]: drop + convert produces correct conversion" in {
            val transform = Schema[XCUser].drop(_.password).drop(_.ssn).convert[XCUserDTO]
            val dto       = transform(alice)
            assert(dto == XCUserDTO(1, "Alice", "alice@example.com"))
        }

        "lambda transforms + format: drop + convert + Json.encode produces valid JSON" in {
            val transform = Schema[XCUser].drop(_.password).drop(_.ssn).convert[XCUserDTO]
            val dto       = transform(alice)
            val json      = Json.encode(dto)

            assert(json.contains("\"id\""))
            assert(json.contains("\"name\""))
            assert(json.contains("\"Alice\""))
            assert(json.contains("\"email\""))
            assert(json.contains("\"alice@example.com\""))
            // Should NOT contain dropped fields
            assert(!json.contains("password"))
            assert(!json.contains("ssn"))
        }

        "lambda transforms: lambda drop + rename produces same result as string-based" in {
            val byString = Schema[XCUser].drop("password").drop("ssn")
            val byLambda = Schema[XCUser].drop(_.password).drop(_.ssn)

            val recString = byString.toRecord(alice)
            val recLambda = byLambda.toRecord(alice)

            assert(recString.dict("id") == recLambda.dict("id"))
            assert(recString.dict("name") == recLambda.dict("name"))
            assert(recString.dict("email") == recLambda.dict("email"))
        }

        "lambda transforms + format: transform chain + Json round-trip recovers DTO" in {
            val transform = Schema[XCUser].drop(_.password).drop(_.ssn).convert[XCUserDTO]
            val dto       = transform(alice)

            // Encode to JSON
            val json = Json.encode(dto)

            // Decode back
            val decoded = Json.decode[XCUserDTO](json)
            assert(decoded == Result.succeed(dto))
        }

        // --- Structure + Changeset + Path ---

        "reflect structure matches Structure.of for same type" in {
            val reflectedViaStandalone = Structure.of[XCUser]
            val reflectedViaSchema     = Structure.of[XCUser]

            // Both should produce Product with same fields
            (reflectedViaStandalone, reflectedViaSchema) match
                case (Structure.Type.Product(n1, _, _, f1, _), Structure.Type.Product(n2, _, _, f2, _)) =>
                    assert(n1 == n2)
                    assert(f1.map(_.name) == f2.map(_.name))
                    assert(f1.size == f2.size)
                case other =>
                    fail(s"Expected two Products, got $other")
            end match
        }

        "reflect + changeset: changeset operations can be applied via Structure.Path on Structure.Value" in {
            val schema = summon[Schema[XCUser]]
            val dynVal = Structure.encode(alice)(using schema)

            // Navigate to name field using Structure.Path
            val namePath   = Structure.Path.field("name")
            val nameResult = namePath.get(dynVal)
            assert(nameResult.isSuccess)
            val values = nameResult.getOrThrow
            assert(values.size == 1)
            values.head match
                case Structure.Value.Str(v) => assert(v == "Alice")
                case other                  => fail(s"Expected Str, got $other")

            // Set name to "Bob" via Path
            val newVal  = Structure.Value.primitive("Bob")
            val updated = namePath.set(dynVal, newVal)
            assert(updated.isSuccess)
            val v       = updated.getOrThrow
            val decoded = Structure.decode(v)(using schema)
            assert(decoded.getOrThrow.name == "Bob")
            assert(decoded.getOrThrow.id == 1)                      // unchanged
            assert(decoded.getOrThrow.email == "alice@example.com") // unchanged
        }

        "reflect: fold counts nodes and fieldPaths lists paths" in {
            val tpe = Structure.of[XCUser]

            // fold to count all nodes
            val nodeCount = Structure.Type.fold(tpe)(0)((count, _) => count + 1)
            // Product(XCUser) + 5 Primitive fields = 6 nodes
            assert(nodeCount == 6)

            // fieldPaths lists all paths
            val paths = Structure.Type.fieldPaths(tpe)
            assert(paths == Chunk(Chunk("id"), Chunk("name"), Chunk("email"), Chunk("password"), Chunk("ssn")))
        }

        "reflect: compatible verifies two structurally similar types match" in {
            // XCUser and XCUser should be compatible
            val tpe1 = Structure.of[XCUser]
            val tpe2 = Structure.of[XCUser]
            assert(Structure.Type.compatible(tpe1, tpe2))

            // XCUser and XCUserDTO are NOT compatible (different number of fields)
            val tpeDTO = Structure.of[XCUserDTO]
            assert(!Structure.Type.compatible(tpe1, tpeDTO))

            // Two structurally similar types ARE compatible
            // MTPerson(name: String, age: Int) and BMPoint2D(x: Int, y: Int) are NOT compatible (different field types)
            val tpePerson = Structure.of[MTPerson]
            val tpePoint  = Structure.of[BMPoint2D]
            assert(!Structure.Type.compatible(tpePerson, tpePoint))
        }

        // --- Full pipeline ---

        "full pipeline: doc + validate + changeset + apply + encode + decode + verify" in {
            val s = Schema[XCUser]
                .doc("A registered user")
                .doc(_.name)("The user's full name")
                .check(_.name)(_.nonEmpty, "name required")
                .check(_.email)(_.contains("@"), "invalid email")

            // Step 1: Validate the original value
            val errors = s.validate(alice)
            assert(errors.isEmpty)

            // Step 2: Compute changeset between alice and an updated version
            val updatedAlice = XCUser(1, "Alice Smith", "alice.smith@example.com", "newpw", "123-45-6789")
            val changeset    = Changeset(alice, updatedAlice)
            assert(!changeset.isEmpty)

            // Step 3: Apply changeset
            val patched = changeset.applyTo(alice).getOrThrow
            assert(patched == updatedAlice)

            // Step 4: Validate the patched value
            val patchedErrors = s.validate(patched)
            assert(patchedErrors.isEmpty)

            // Step 5: Encode to JSON
            val json = Json.encode(patched)
            assert(json.contains("\"Alice Smith\""))
            assert(json.contains("\"alice.smith@example.com\""))

            // Step 6: Decode back
            val decoded = Json.decode[XCUser](json).getOrThrow
            assert(decoded == updatedAlice)

            // Step 7: Verify metadata is still accessible
            assert(s.doc == Maybe("A registered user"))
            assert(s.focus(_.name).doc == Maybe("The user's full name"))
        }

        "full pipeline: reflect inspection + reflectValue + changeset + convert forward" in {
            // Step 1: Inspect type structure via Structure.of
            val tpe = Structure.of[XCUser]
            tpe match
                case Structure.Type.Product(_, _, _, fields, _) =>
                    assert(fields.size == 5)
                    assert(fields.head.name == "id")
                case _ => fail("Expected Product")
            end match

            // Step 2: Convert to Structure.Value
            val dynVal = Structure.encode(alice)
            dynVal match
                case Structure.Value.Record(fields) =>
                    assert(fields.size == 5)
                    val names = fields.map(_._1).toSeq
                    assert(names.contains("name"))
                    assert(names.contains("email"))
                case other => fail(s"Expected Record, got $other")
            end match

            // Step 3: Compute changeset
            val updatedAlice = alice.copy(name = "Alice Updated")
            val changeset    = Changeset(alice, updatedAlice)
            assert(!changeset.isEmpty)

            // Step 4: Apply changeset
            val patched = changeset.applyTo(alice).getOrThrow
            assert(patched.name == "Alice Updated")

            // Step 5: Forward through convert
            val convert = Convert[XCUser, XCUserDTO](u => XCUserDTO(u.id, u.name, u.email))
            val dto     = convert(patched)
            assert(dto == XCUserDTO(1, "Alice Updated", "alice@example.com"))
        }

        // --- Additional cross-cutting compositions ---

        "metadata survives through transform chain: doc + drop + rename" in {
            val s = Schema[XCUser]
                .doc("A user")
                .doc(_.name)("User name")
                .doc(_.email)("User email")
                .deprecated(_.ssn)("Use taxId")
                .drop(_.password)
                .drop(_.ssn)
                .rename(_.name, "userName")

            assert(s.doc == Maybe("A user"))
            // After rename: name -> userName, doc should follow
            assert(s.fieldDocs.get(Seq("userName")) == Some("User name"))
            assert(s.focus(_.email).doc == Maybe("User email"))
        }

        "validate + foreach compose: root checks and element checks together" in {
            val rootSchema = Schema[XCOrder]
                .check(_.customer)(_.nonEmpty, "customer required")

            val itemSchema = Schema[XCOrder]
                .check(_.items.forall(_.price > 0), "price must be positive")

            // Root check fails
            val badCustomerOrder = XCOrder(1, List(XCItem("Good", 10.0, 1)), "")
            val rootErrors       = rootSchema.validate(badCustomerOrder)
            assert(rootErrors.size == 1)
            assert(rootErrors.head.message == "customer required")

            // Item check fails
            val badItemOrder = XCOrder(1, List(XCItem("Bad", -1.0, 1)), "Alice")
            val itemErrors   = itemSchema.validate(badItemOrder)
            assert(itemErrors.size == 1)
            assert(itemErrors.head.message == "price must be positive")

            // Both schemas can be used together on the same value
            val bothBad       = XCOrder(1, List(XCItem("Bad", -1.0, 1)), "")
            val allRootErrors = rootSchema.validate(bothBad)
            val allItemErrors = itemSchema.validate(bothBad)
            assert(allRootErrors.size == 1)
            assert(allItemErrors.size == 1)
        }

        "changeset + Structure.Value: changeset operations correspond to Structure.Value fields" in {
            val updatedAlice = alice.copy(name = "Alice Changed", id = 42)
            val changeset    = Changeset(alice, updatedAlice)

            // Changeset operations should target the same fields visible in Structure.Value
            val dynVal     = Structure.encode(alice)
            val fieldPaths = Structure.Type.fieldPaths(Structure.of[XCUser])

            // All changeset operation field paths should be valid field paths from reflect
            changeset.operations.foreach { op =>
                val opPath = op.fieldPath
                assert(
                    fieldPaths.exists(fp => fp.startsWith(opPath) || opPath.startsWith(fp)),
                    s"Changeset op path $opPath not found in reflect field paths $fieldPaths"
                )
            }

            // Verify changeset correctly modifies the value
            val patched = changeset.applyTo(alice).getOrThrow
            assert(patched.name == "Alice Changed")
            assert(patched.id == 42)
        }

        "foreach + update + changeset: update all items then compare with changeset" in {
            val each    = Schema[XCOrder].foreach(_.items)
            val doubled = each.update(order)(item => item.copy(price = item.price * 2))

            // Verify modification worked
            assert(doubled.items(0).price == 9.99 * 2)
            assert(doubled.items(1).price == 19.99 * 2)

            // Compute changeset between original and modified
            val changeset = Changeset(order, doubled)
            assert(!changeset.isEmpty)

            // Apply changeset to original recovers modified version
            val recovered = changeset.applyTo(order).getOrThrow
            assert(recovered.items(0).price == 9.99 * 2)
            assert(recovered.items(1).price == 19.99 * 2)
            assert(recovered.customer == "Alice") // unchanged
        }

        // --- Recursive types ---

        "recursive types" - {

            "Changeset on recursive case class" in {
                val tree1 = TreeNode(1, List(TreeNode(2, scala.Nil)))
                val tree2 = TreeNode(1, List(TreeNode(3, scala.Nil)))
                val d     = Changeset(tree1, tree2)
                // Should detect the difference
                assert(!d.isEmpty)
            }

            "Changeset apply on recursive data" in {
                val tree1  = TreeNode(1, List(TreeNode(2, scala.Nil)))
                val tree2  = TreeNode(1, List(TreeNode(3, scala.Nil)))
                val d      = Changeset(tree1, tree2)
                val result = d.applyTo(tree1)
                assert(result == Result.succeed(tree2))
            }
        }
    }

    // =========================================================================
    // discriminator
    // =========================================================================

    "discriminator" - {

        "JSON encode with discriminator produces flat format" in {
            val schema          = Schema[MTShape].discriminator("type")
            val circle: MTShape = MTCircle(5.0)
            val json            = schema.encodeString[Json](circle)
            assert(json.contains("\"type\""))
            assert(json.contains("\"MTCircle\""))
            assert(json.contains("\"radius\""))
            assert(!json.contains("{\"MTCircle\":{")) // NOT wrapper format
        }

        "JSON decode with discriminator reads flat format" in {
            val schema = Schema[MTShape].discriminator("type")
            val result = schema.decodeString[Json]("""{"type":"MTCircle","radius":5.0}""")
            assert(result == Result.succeed(MTCircle(5.0)))
        }

        "JSON decode with discriminator field not first" in {
            val schema = Schema[MTShape].discriminator("type")
            val result = schema.decodeString[Json]("""{"radius":5.0,"type":"MTCircle"}""")
            assert(result == Result.succeed(MTCircle(5.0)))
        }

        "JSON round-trip with discriminator" in {
            val schema          = Schema[MTShape].discriminator("type")
            val circle: MTShape = MTCircle(5.0)
            val json            = schema.encodeString[Json](circle)
            val decoded         = schema.decodeString[Json](json)
            assert(decoded == Result.succeed(circle))
        }

        "JSON round-trip rectangle with discriminator" in {
            val schema        = Schema[MTShape].discriminator("type")
            val rect: MTShape = MTRectangle(3.0, 4.0)
            val json          = schema.encodeString[Json](rect)
            val decoded       = schema.decodeString[Json](json)
            assert(decoded == Result.succeed(rect))
        }

        "discriminator with different field name" in {
            val schema          = Schema[MTShape].discriminator("kind")
            val circle: MTShape = MTCircle(5.0)
            val json            = schema.encodeString[Json](circle)
            assert(json.contains("\"kind\""))
            assert(json.contains("\"MTCircle\""))
            assert(!json.contains("{\"MTCircle\":{"))
            val decoded = schema.decodeString[Json](json)
            assert(decoded == Result.succeed(circle))
        }

        "without discriminator uses wrapper format (backward compat)" in {
            val schema = Schema[MTShape]
            val json   = schema.encodeString[Json](MTCircle(5.0): MTShape)
            assert(json.contains("\"MTCircle\":{"))
        }

        "discriminator survives transform chain" in {
            val schema = Schema[MTShape].discriminator("kind")
            assert(schema.discriminatorField == Maybe("kind"))
        }

        "discriminator unknown variant carries actual discriminator value (regression)" in {
            // DiscriminatorReader must override lastFieldName() to return the captured discriminator field; the default would return stale state and produce misleading UnknownVariantException messages.
            val schema = Schema[MTShape].discriminator("type")
            val result = schema.decodeString[Json]("""{"type":"MTTriangle","a":1.0}""")
            assert(result.isFailure)
            result match
                case Result.Failure(e: UnknownVariantException) =>
                    assert(
                        e.variantName == "MTTriangle",
                        s"Expected variantName == \"MTTriangle\", got: \"${e.variantName}\""
                    )
                    assert(e.getMessage.contains("MTTriangle"))
                case other =>
                    fail(s"Expected Result.Failure(UnknownVariantException), got: $other")
            end match
        }
    }

    // =========================================================================
    // composition
    // =========================================================================

    "composition" - {

        val user    = MTUser("Alice", 30, "alice@example.com", "123-45-6789")
        val user2   = MTUser("Bob", 25, "bob@example.com", "987-65-4321")
        val person  = MTPerson("Alice", 30)
        val person2 = MTPerson("Bob", 25)

        // --- discriminator + transforms ---

        "discriminator + drop excludes dropped field but discriminator still works" in {
            val schema          = Schema[MTShape].discriminator("type")
            val circle: MTShape = MTCircle(5.0)
            val json            = schema.encodeString[Json](circle)
            assert(json.contains("\"type\""))
            assert(json.contains("\"MTCircle\""))
            assert(json.contains("\"radius\""))
            val decoded = schema.decodeString[Json](json)
            assert(decoded == Result.succeed(circle))
        }

        "discriminator round-trip rectangle with flat format" in {
            val schema        = Schema[MTShape].discriminator("kind")
            val rect: MTShape = MTRectangle(3.0, 4.0)
            val json          = schema.encodeString[Json](rect)
            assert(json.contains("\"kind\""))
            assert(json.contains("\"MTRectangle\""))
            assert(!json.contains("{\"MTRectangle\":{"))
            val decoded = schema.decodeString[Json](json)
            assert(decoded == Result.succeed(rect))
        }

        "discriminator + check encode validates before producing output" in {
            val schema = Schema[MTPerson]
                .check(_.name)(_.nonEmpty, "name required")
            val errors = schema.validate(MTPerson("", 30))
            assert(errors.size == 1)
            assert(errors.head.message == "name required")
            // Valid value encodes fine
            val json = schema.encodeString[Json](person)
            assert(json.contains("\"Alice\""))
        }

        "discriminator decode with fields in unexpected order" in {
            val schema = Schema[MTShape].discriminator("type")
            val result = schema.decodeString[Json]("""{"radius":5.0,"type":"MTCircle"}""")
            assert(result == Result.succeed(MTCircle(5.0)))
        }

        "discriminator decode with extra unknown fields" in {
            val schema = Schema[MTShape].discriminator("type")
            val result = schema.decodeString[Json]("""{"type":"MTCircle","radius":5.0,"extra":"ignored"}""")
            assert(result == Result.succeed(MTCircle(5.0)))
        }

        "discriminator decode with unknown variant returns error" in {
            val schema = Schema[MTShape].discriminator("type")
            val result = schema.decodeString[Json]("""{"type":"MTTriangle","a":1.0}""")
            assert(result.isFailure)
        }

        // --- check + transform + check ---

        "check then drop then check - both checks survive" in {
            val schema = Schema[MTUser]
                .check(_.name)(_.nonEmpty, "name required")
                .drop("ssn")
                .check(_.age)(_ > 0, "age positive")
            val errors = schema.validate(MTUser("", -1, "e@x", "ssn"))
            assert(errors.size == 2)
            assert(errors.exists(_.message == "name required"))
            assert(errors.exists(_.message == "age positive"))
        }

        "check then rename then check" in {
            val schema = Schema[MTUser]
                .check(_.name)(_.nonEmpty, "name required")
                .rename("name", "userName")
                .check(_.age)(_ >= 18, "must be adult")
            val errors = schema.validate(MTUser("", 10, "e@x", "ssn"))
            assert(errors.size == 2)
            assert(errors.exists(_.message == "name required"))
            assert(errors.exists(_.message == "must be adult"))
        }

        "check then add then check on added field" in {
            val schema = Schema[MTPerson]
                .check(_.name)(_.nonEmpty, "name required")
                .add("greeting")(p => s"Hello ${p.name}")
            val errors = schema.validate(MTPerson("", 30))
            assert(errors.exists(_.message == "name required"))
        }

        "validate after chained transforms returns all errors" in {
            val schema = Schema[MTUser]
                .check(_.name)(_.nonEmpty, "name required")
                .check(_.age)(_ >= 0, "age non-negative")
                .drop("ssn")
                .check(_.email)(_.contains("@"), "invalid email")
            val errors = schema.validate(MTUser("", -1, "no-at", "ssn"))
            assert(errors.size == 3)
            assert(errors.exists(_.message == "name required"))
            assert(errors.exists(_.message == "age non-negative"))
            assert(errors.exists(_.message == "invalid email"))
        }

        // --- schema + focus + encode ---

        "drop then focus on remaining field" in {
            val schema = Schema[MTUser].drop("ssn")
            val name   = schema.focus(_.name).get(user)
            assert(name == "Alice")
        }

        "rename then focus uses original field name" in {
            val schema = Schema[MTUser].rename("name", "userName")
            // focus still uses original accessor _.name since the underlying type is MTUser
            val record = schema.toRecord(user)
            assert(record.userName == "Alice")
        }

        "drop then encode excludes dropped field" in {
            val schema = Schema[MTUser].drop("ssn")
            val json   = schema.encodeString[Json](user)
            assert(!json.contains("ssn"))
            assert(json.contains("\"name\""))
            assert(json.contains("\"age\""))
            assert(json.contains("\"email\""))
        }

        "drop then encode then decode round-trip" in {
            val schema  = Schema[MTUser].drop("ssn")
            val json    = schema.encodeString[Json](user)
            val decoded = schema.decodeString[Json](json).getOrThrow
            assert(decoded.name == user.name)
            assert(decoded.age == user.age)
            assert(decoded.email == user.email)
        }

        // --- changeset + transforms ---

        "changeset on values with drop - dropped field not in changeset" in {
            val schema           = Schema[MTUser].drop("ssn")
            given Schema[MTUser] = schema
            val changeset        = Changeset(user, user2)
            val fieldPaths       = changeset.operations.map(_.fieldPath)
            assert(!fieldPaths.exists(_.contains("ssn")))
            assert(fieldPaths.exists(_.contains("name")))
        }

        "changeset on values with rename - renamed field in ops" in {
            val schema           = Schema[MTUser].rename("name", "userName")
            given Schema[MTUser] = schema
            val changeset        = Changeset(user, user2)
            val fieldPaths       = changeset.operations.flatMap(_.fieldPath)
            assert(fieldPaths.contains("userName"))
            assert(!fieldPaths.contains("name"))
        }

        "changeset applyTo reconstructs value with base schema" in {
            val changeset = Changeset(user, user2)
            val result    = changeset.applyTo(user).getOrThrow
            assert(result.name == user2.name)
            assert(result.age == user2.age)
            assert(result.email == user2.email)
            assert(result.ssn == user2.ssn)
        }

        "empty changeset after transforms on identical values" in {
            val schema           = Schema[MTUser].drop("ssn").rename("name", "userName")
            given Schema[MTUser] = schema
            val changeset        = Changeset(user, user)
            assert(changeset.isEmpty)
        }

        // --- compare + transforms ---

        "compare detects field changes" in {
            val d = Compare(user, user2)
            assert(d.changed(_.name))
            assert(d.changed(_.age))
            assert(d.changed(_.email))
        }

        "compare unchanged fields" in {
            val user3 = MTUser("Alice", 30, "alice@example.com", "different-ssn")
            val d     = Compare(user, user3)
            assert(!d.changed(_.name))
            assert(!d.changed(_.age))
            assert(!d.changed(_.email))
            assert(d.changed(_.ssn))
        }

        "compare left and right values" in {
            val d = Compare(user, user2)
            assert(d.left(_.name) == Maybe("Alice"))
            assert(d.right(_.name) == Maybe("Bob"))
            assert(d.left(_.age) == Maybe(30))
            assert(d.right(_.age) == Maybe(25))
        }

        "compare on sealed trait variants" in {
            val circle: MTShape = MTCircle(5.0)
            val rect: MTShape   = MTRectangle(3.0, 4.0)
            val d               = Compare(circle, rect)
            assert(d.changed)
            assert(d.left(_.MTCircle) == Maybe(MTCircle(5.0)))
            assert(d.left(_.MTRectangle) == Maybe.empty)
            assert(d.right(_.MTRectangle) == Maybe(MTRectangle(3.0, 4.0)))
            assert(d.right(_.MTCircle) == Maybe.empty)
        }

        // --- structure + transforms ---

        "Structure.of matches expected product structure" in {
            val tpe = Structure.of[MTUser]
            tpe match
                case Structure.Type.Product(name, _, _, fields, _) =>
                    assert(name == "MTUser")
                    assert(fields.map(_.name) == Chunk("name", "age", "email", "ssn"))
                case _ => fail("Expected Product")
            end match
        }

        "Structure.Value round-trip on schema with drop" in {
            val schema = Schema[MTUser].drop("ssn")
            val dv     = Structure.encode(user)(using schema, summon[Frame])
            dv match
                case Structure.Value.Record(fields) =>
                    val fieldNames = fields.map(_._1)
                    assert(!fieldNames.contains("ssn"))
                    assert(fieldNames.contains("name"))
                    assert(fieldNames.contains("age"))
                    assert(fieldNames.contains("email"))
                case _ => fail("Expected Record")
            end match
        }

        "Structure.Value round-trip on schema with rename" in {
            val schema = Schema[MTUser].rename("name", "userName")
            val dv     = Structure.encode(user)(using schema, summon[Frame])
            dv match
                case Structure.Value.Record(fields) =>
                    val fieldNames = fields.map(_._1)
                    assert(fieldNames.contains("userName"))
                    assert(!fieldNames.contains("name"))
                case _ => fail("Expected Record")
            end match
        }

        "Structure.Value encode then decode round-trip without transforms" in {
            val schema  = Schema[MTUser]
            val dv      = Structure.encode(user)(using schema, summon[Frame])
            val decoded = Structure.decode(dv)(using schema, summon[Frame]).getOrThrow
            assert(decoded.name == user.name)
            assert(decoded.age == user.age)
            assert(decoded.email == user.email)
            assert(decoded.ssn == user.ssn)
        }

    }

    "structure default" - {
        "Schema[String].structure equals Structure.of[String]" in {
            val s = summon[Schema[String]]
            assert(Structure.Type.compatible(s.structure, Structure.of[String]))
        }
        "Schema[Int].structure equals Structure.of[Int]" in {
            val s = summon[Schema[Int]]
            assert(Structure.Type.compatible(s.structure, Structure.of[Int]))
        }
        "Schema[List[String]].structure equals Structure.of[List[String]]" in {
            val s = summon[Schema[List[String]]]
            assert(Structure.Type.compatible(s.structure, Structure.of[List[String]]))
        }
        "Schema[Map[String, Int]].structure equals Structure.of[Map[String, Int]]" in {
            val s = summon[Schema[Map[String, Int]]]
            assert(Structure.Type.compatible(s.structure, Structure.of[Map[String, Int]]))
        }
    }

    "primitive structure" - {
        "stringSchema" in {
            val s = summon[Schema[String]]
            assert(s.structure == Structure.Type.Primitive(Structure.PrimitiveKind.String, Tag[String].asInstanceOf[Tag[Any]]))
        }
        "booleanSchema" in {
            val s = summon[Schema[Boolean]]
            assert(s.structure == Structure.Type.Primitive(Structure.PrimitiveKind.Boolean, Tag[Boolean].asInstanceOf[Tag[Any]]))
        }
        "intSchema" in {
            val s = summon[Schema[Int]]
            assert(s.structure == Structure.Type.Primitive(Structure.PrimitiveKind.Int, Tag[Int].asInstanceOf[Tag[Any]]))
        }
        "longSchema" in {
            val s = summon[Schema[Long]]
            assert(s.structure == Structure.Type.Primitive(Structure.PrimitiveKind.Long, Tag[Long].asInstanceOf[Tag[Any]]))
        }
        "floatSchema" in {
            val s = summon[Schema[Float]]
            assert(s.structure == Structure.Type.Primitive(Structure.PrimitiveKind.Float, Tag[Float].asInstanceOf[Tag[Any]]))
        }
        "doubleSchema" in {
            val s = summon[Schema[Double]]
            assert(s.structure == Structure.Type.Primitive(Structure.PrimitiveKind.Double, Tag[Double].asInstanceOf[Tag[Any]]))
        }
        "shortSchema" in {
            val s = summon[Schema[Short]]
            assert(s.structure == Structure.Type.Primitive(Structure.PrimitiveKind.Short, Tag[Short].asInstanceOf[Tag[Any]]))
        }
        "byteSchema" in {
            val s = summon[Schema[Byte]]
            assert(s.structure == Structure.Type.Primitive(Structure.PrimitiveKind.Byte, Tag[Byte].asInstanceOf[Tag[Any]]))
        }
        "charSchema" in {
            val s = summon[Schema[Char]]
            assert(s.structure == Structure.Type.Primitive(Structure.PrimitiveKind.Char, Tag[Char].asInstanceOf[Tag[Any]]))
        }
        "bigDecimalSchema" in {
            val s = summon[Schema[BigDecimal]]
            assert(s.structure == Structure.Type.Primitive(Structure.PrimitiveKind.BigDecimal, Tag[BigDecimal].asInstanceOf[Tag[Any]]))
        }
        "bigIntSchema" in {
            val s = summon[Schema[BigInt]]
            assert(s.structure == Structure.Type.Primitive(Structure.PrimitiveKind.BigInt, Tag[BigInt].asInstanceOf[Tag[Any]]))
        }
        "instantSchema" in {
            val s = summon[Schema[java.time.Instant]]
            assert(s.structure == Structure.Type.Primitive(Structure.PrimitiveKind.Instant, Tag[java.time.Instant].asInstanceOf[Tag[Any]]))
        }
        "durationSchema" in {
            val s = summon[Schema[java.time.Duration]]
            assert(s.structure == Structure.Type.Primitive(
                Structure.PrimitiveKind.Duration,
                Tag[java.time.Duration].asInstanceOf[Tag[Any]]
            ))
        }
        "spanByteSchema" in {
            val s = summon[Schema[Span[Byte]]]
            assert(s.structure == Structure.Type.Primitive(Structure.PrimitiveKind.Bytes, Tag[Span[Byte]].asInstanceOf[Tag[Any]]))
        }
        "frameSchema" in {
            val s = summon[Schema[Frame]]
            assert(s.structure == Structure.Type.Primitive(Structure.PrimitiveKind.String, Tag[Frame].asInstanceOf[Tag[Any]]))
        }
        "tagSchema" in {
            val s = summon[Schema[Tag[String]]]
            assert(s.structure == Structure.Type.Primitive(Structure.PrimitiveKind.String, Tag[Tag[String]].asInstanceOf[Tag[Any]]))
        }
        "localDateSchema" in {
            val s = summon[Schema[java.time.LocalDate]]
            assert(s.structure == Structure.Type.Primitive(Structure.PrimitiveKind.String, Tag[java.time.LocalDate].asInstanceOf[Tag[Any]]))
        }
        "localTimeSchema" in {
            val s = summon[Schema[java.time.LocalTime]]
            assert(s.structure == Structure.Type.Primitive(Structure.PrimitiveKind.String, Tag[java.time.LocalTime].asInstanceOf[Tag[Any]]))
        }
        "localDateTimeSchema" in {
            val s = summon[Schema[java.time.LocalDateTime]]
            s.structure match
                case Structure.Type.Primitive(Structure.PrimitiveKind.String, _) => succeed
                case other                                                       => fail(s"Expected Primitive(String, _) but got $other")
        }
        "uuidSchema" in {
            val s = summon[Schema[java.util.UUID]]
            assert(s.structure == Structure.Type.Primitive(Structure.PrimitiveKind.String, Tag[java.util.UUID].asInstanceOf[Tag[Any]]))
        }
        "unitSchema" in {
            val s = summon[Schema[Unit]]
            assert(s.structure == Structure.Type.Primitive(Structure.PrimitiveKind.Unit, Tag[Unit].asInstanceOf[Tag[Any]]))
        }
    }

    "primitive structure identity" - {
        "Schema[String].structure returns same reference on repeated calls" in {
            val s = summon[Schema[String]]
            assert(s.structure eq s.structure)
        }
    }

    "container structure" - {
        "listSchema produces Collection structure with name List and inner reference eq" in {
            val schema = summon[Schema[List[Int]]]
            schema.structure match
                case Structure.Type.Collection(name, _, elementType) =>
                    assert(name == "List")
                    assert(elementType eq summon[Schema[Int]].structure)
                case other => fail(s"Expected Collection but got $other")
            end match
        }
        "vectorSchema produces Collection structure with name Vector and inner reference eq" in {
            val schema = summon[Schema[Vector[String]]]
            schema.structure match
                case Structure.Type.Collection(name, _, elementType) =>
                    assert(name == "Vector")
                    assert(elementType eq summon[Schema[String]].structure)
                case other => fail(s"Expected Collection but got $other")
            end match
        }
        "setSchema produces Collection structure with name Set and inner reference eq" in {
            val schema = summon[Schema[Set[Boolean]]]
            schema.structure match
                case Structure.Type.Collection(name, _, elementType) =>
                    assert(name == "Set")
                    assert(elementType eq summon[Schema[Boolean]].structure)
                case other => fail(s"Expected Collection but got $other")
            end match
        }
        "chunkSchema produces Collection structure with name Chunk and inner reference eq" in {
            val schema = summon[Schema[Chunk[Long]]]
            schema.structure match
                case Structure.Type.Collection(name, _, elementType) =>
                    assert(name == "Chunk")
                    assert(elementType eq summon[Schema[Long]].structure)
                case other => fail(s"Expected Collection but got $other")
            end match
        }
        "seqSchema produces Collection structure with name Seq and inner reference eq" in {
            val schema = summon[Schema[Seq[Double]]]
            schema.structure match
                case Structure.Type.Collection(name, _, elementType) =>
                    assert(name == "Seq")
                    assert(elementType eq summon[Schema[Double]].structure)
                case other => fail(s"Expected Collection but got $other")
            end match
        }
        "spanSchema produces Collection structure with name Span and inner reference eq" in {
            val schema = summon[Schema[Span[Int]]]
            schema.structure match
                case Structure.Type.Collection(name, _, elementType) =>
                    assert(name == "Span")
                    assert(elementType eq summon[Schema[Int]].structure)
                case other => fail(s"Expected Collection but got $other")
            end match
        }
        "maybeSchema produces Optional structure with name Maybe and inner reference eq" in {
            val schema = summon[Schema[Maybe[String]]]
            schema.structure match
                case Structure.Type.Optional(name, _, innerType) =>
                    assert(name == "Maybe")
                    assert(innerType eq summon[Schema[String]].structure)
                case other => fail(s"Expected Optional but got $other")
            end match
        }
        "optionSchema produces Optional structure with name Option and inner reference eq" in {
            val schema = summon[Schema[Option[Int]]]
            schema.structure match
                case Structure.Type.Optional(name, _, innerType) =>
                    assert(name == "Option")
                    assert(innerType eq summon[Schema[Int]].structure)
                case other => fail(s"Expected Optional but got $other")
            end match
        }
        "Schema[List[Int]].structure returns same reference on repeated calls" in {
            val schema = summon[Schema[List[Int]]]
            assert(schema.structure eq schema.structure)
        }
        "Schema[Maybe[String]].structure returns same reference on repeated calls" in {
            val schema = summon[Schema[Maybe[String]]]
            assert(schema.structure eq schema.structure)
        }
    }

    "container Frame propagation" - {
        "summon Schema[List[Int]] succeeds with Frame in scope and produces Collection structure" in {
            val schema = summon[Schema[List[Int]]]
            assert(schema.structure.isInstanceOf[Structure.Type.Collection])
        }
    }

    "Result/Either structure" - {
        "resultSchema produces Sum structure with name Result" in {
            val schema = summon[Schema[Result[String, Int]]]
            schema.structure match
                case s: Structure.Type.Sum => assert(s.name == "Result")
                case other                 => fail(s"Expected Sum but got $other")
            end match
        }
        "resultSchema Sum has 3 variants: success, failure, panic" in {
            val schema = summon[Schema[Result[String, Int]]]
            schema.structure match
                case s: Structure.Type.Sum =>
                    assert(s.variants.size == 3)
                    assert(s.variants(0).name == "success")
                    assert(s.variants(1).name == "failure")
                    assert(s.variants(2).name == "panic")
                case other => fail(s"Expected Sum but got $other")
            end match
        }
        "resultSchema success variant structure is reference-eq to Schema[Int].structure" in {
            val schema = summon[Schema[Result[String, Int]]]
            schema.structure match
                case s: Structure.Type.Sum =>
                    assert(s.variants(0).variantType eq summon[Schema[Int]].structure)
                case other => fail(s"Expected Sum but got $other")
            end match
        }
        "resultSchema failure variant structure is reference-eq to Schema[String].structure" in {
            val schema = summon[Schema[Result[String, Int]]]
            schema.structure match
                case s: Structure.Type.Sum =>
                    assert(s.variants(1).variantType eq summon[Schema[String]].structure)
                case other => fail(s"Expected Sum but got $other")
            end match
        }
        "resultSchema panic variant structure is Primitive(String)" in {
            val schema = summon[Schema[Result[String, Int]]]
            schema.structure match
                case s: Structure.Type.Sum =>
                    s.variants(2).variantType match
                        case Structure.Type.Primitive(Structure.PrimitiveKind.String, _) => succeed
                        case other => fail(s"Expected Primitive(String) but got $other")
                    end match
                case other => fail(s"Expected Sum but got $other")
            end match
        }
        "resultSchema typeParams has size 2" in {
            val schema = summon[Schema[Result[String, Int]]]
            schema.structure match
                case s: Structure.Type.Sum => assert(s.typeParams.size == 2)
                case other                 => fail(s"Expected Sum but got $other")
            end match
        }
        "resultSchema typeParams(0) is reference-eq to Schema[String].structure (E)" in {
            val schema = summon[Schema[Result[String, Int]]]
            schema.structure match
                case s: Structure.Type.Sum =>
                    assert(s.typeParams(0) eq summon[Schema[String]].structure)
                case other => fail(s"Expected Sum but got $other")
            end match
        }
        "resultSchema typeParams(1) is reference-eq to Schema[Int].structure (A)" in {
            val schema = summon[Schema[Result[String, Int]]]
            schema.structure match
                case s: Structure.Type.Sum =>
                    assert(s.typeParams(1) eq summon[Schema[Int]].structure)
                case other => fail(s"Expected Sum but got $other")
            end match
        }
        "eitherSchema produces Sum structure with name Either" in {
            val schema = summon[Schema[Either[Int, String]]]
            schema.structure match
                case s: Structure.Type.Sum => assert(s.name == "Either")
                case other                 => fail(s"Expected Sum but got $other")
            end match
        }
        "eitherSchema Sum has 2 variants: Left and Right" in {
            val schema = summon[Schema[Either[Int, String]]]
            schema.structure match
                case s: Structure.Type.Sum =>
                    assert(s.variants.size == 2)
                    assert(s.variants(0).name == "Left")
                    assert(s.variants(1).name == "Right")
                case other => fail(s"Expected Sum but got $other")
            end match
        }
        "eitherSchema Left variant structure is reference-eq to Schema[Int].structure" in {
            val schema = summon[Schema[Either[Int, String]]]
            schema.structure match
                case s: Structure.Type.Sum =>
                    assert(s.variants(0).variantType eq summon[Schema[Int]].structure)
                case other => fail(s"Expected Sum but got $other")
            end match
        }
        "eitherSchema Right variant structure is reference-eq to Schema[String].structure" in {
            val schema = summon[Schema[Either[Int, String]]]
            schema.structure match
                case s: Structure.Type.Sum =>
                    assert(s.variants(1).variantType eq summon[Schema[String]].structure)
                case other => fail(s"Expected Sum but got $other")
            end match
        }
        "eitherSchema typeParams has size 2" in {
            val schema = summon[Schema[Either[Int, String]]]
            schema.structure match
                case s: Structure.Type.Sum => assert(s.typeParams.size == 2)
                case other                 => fail(s"Expected Sum but got $other")
            end match
        }
        "eitherSchema enumValues is empty" in {
            val schema = summon[Schema[Either[Int, String]]]
            schema.structure match
                case s: Structure.Type.Sum => assert(s.enumValues.isEmpty)
                case other                 => fail(s"Expected Sum but got $other")
            end match
        }
    }

    "Map/Dict structure" - {
        "stringMapSchema produces Mapping structure with name Map" in {
            val schema = summon[Schema[Map[String, Int]]]
            schema.structure match
                case m: Structure.Type.Mapping => assert(m.name == "Map")
                case other                     => fail(s"Expected Mapping but got $other")
            end match
        }
        "stringMapSchema keyType is Primitive(String)" in {
            val schema = summon[Schema[Map[String, Int]]]
            schema.structure match
                case m: Structure.Type.Mapping =>
                    m.keyType match
                        case Structure.Type.Primitive(Structure.PrimitiveKind.String, _) => succeed
                        case other => fail(s"Expected Primitive(String) but got $other")
                    end match
                case other => fail(s"Expected Mapping but got $other")
            end match
        }
        "stringMapSchema valueType is reference-eq to Schema[Int].structure" in {
            val schema = summon[Schema[Map[String, Int]]]
            schema.structure match
                case m: Structure.Type.Mapping =>
                    assert(m.valueType eq summon[Schema[Int]].structure)
                case other => fail(s"Expected Mapping but got $other")
            end match
        }
        "stringDictSchema produces Mapping structure with name Dict and Primitive(String) key" in {
            val schema = summon[Schema[Dict[String, Boolean]]]
            schema.structure match
                case m: Structure.Type.Mapping =>
                    assert(m.name == "Dict")
                    m.keyType match
                        case Structure.Type.Primitive(Structure.PrimitiveKind.String, _) => succeed
                        case other => fail(s"Expected Primitive(String) but got $other")
                    end match
                case other => fail(s"Expected Mapping but got $other")
            end match
        }
        "stringDictSchema valueType is reference-eq to Schema[Boolean].structure" in {
            val schema = summon[Schema[Dict[String, Boolean]]]
            schema.structure match
                case m: Structure.Type.Mapping =>
                    assert(m.valueType eq summon[Schema[Boolean]].structure)
                case other => fail(s"Expected Mapping but got $other")
            end match
        }
        "dictSchema produces Mapping structure with name Dict" in {
            val schema = summon[Schema[Dict[Int, String]]]
            schema.structure match
                case m: Structure.Type.Mapping => assert(m.name == "Dict")
                case other                     => fail(s"Expected Mapping but got $other")
            end match
        }
        "dictSchema keyType is reference-eq to Schema[Int].structure (non-String key)" in {
            val schema = summon[Schema[Dict[Int, String]]]
            schema.structure match
                case m: Structure.Type.Mapping =>
                    assert(m.keyType eq summon[Schema[Int]].structure)
                case other => fail(s"Expected Mapping but got $other")
            end match
        }
        "dictSchema valueType is reference-eq to Schema[String].structure" in {
            val schema = summon[Schema[Dict[Int, String]]]
            schema.structure match
                case m: Structure.Type.Mapping =>
                    assert(m.valueType eq summon[Schema[String]].structure)
                case other => fail(s"Expected Mapping but got $other")
            end match
        }
        "stringDictSchema and dictSchema with String key use different givens" in {
            // stringDictSchema: keyType is Primitive(String)
            // dictSchema: keyType is Schema[String].structure (also Primitive(String) but distinct path)
            val stringDictS = summon[Schema[Dict[String, Int]]]
            stringDictS.structure match
                case m: Structure.Type.Mapping =>
                    m.keyType match
                        case Structure.Type.Primitive(Structure.PrimitiveKind.String, _) => succeed
                        case other => fail(s"Expected Primitive(String) but got $other")
                    end match
                case other => fail(s"Expected Mapping but got $other")
            end match
        }
        "stringOrderedMapSchema produces Mapping structure with name OrderedMap and Primitive(String) key" in {
            val schema = summon[Schema[OrderedMap[String, Boolean]]]
            schema.structure match
                case m: Structure.Type.Mapping =>
                    assert(m.name == "OrderedMap")
                    m.keyType match
                        case Structure.Type.Primitive(Structure.PrimitiveKind.String, _) => succeed
                        case other => fail(s"Expected Primitive(String) but got $other")
                    end match
                case other => fail(s"Expected Mapping but got $other")
            end match
        }
        "orderedMapSchema produces Mapping structure with name OrderedMap" in {
            val schema = summon[Schema[OrderedMap[Int, String]]]
            schema.structure match
                case m: Structure.Type.Mapping =>
                    assert(m.name == "OrderedMap")
                    assert(m.keyType eq summon[Schema[Int]].structure)
                    assert(m.valueType eq summon[Schema[String]].structure)
                case other => fail(s"Expected Mapping but got $other")
            end match
        }
        "stringOrderedMapSchema and orderedMapSchema with String key use different givens" in {
            // stringOrderedMapSchema: keyType is Primitive(String)
            // orderedMapSchema: keyType is Schema[String].structure (also Primitive(String) but distinct path)
            val stringOrderedMapS = summon[Schema[OrderedMap[String, Int]]]
            stringOrderedMapS.structure match
                case m: Structure.Type.Mapping =>
                    m.keyType match
                        case Structure.Type.Primitive(Structure.PrimitiveKind.String, _) => succeed
                        case other => fail(s"Expected Primitive(String) but got $other")
                    end match
                case other => fail(s"Expected Mapping but got $other")
            end match
            val orderedMapS = summon[Schema[OrderedMap[Int, Int]]]
            orderedMapS.structure match
                case m: Structure.Type.Mapping => assert(m.keyType eq summon[Schema[Int]].structure)
                case other                     => fail(s"Expected Mapping but got $other")
            end match
        }
        "Schema[Map[String,Int]].structure returns same reference on repeated calls" in {
            val schema = summon[Schema[Map[String, Int]]]
            assert(schema.structure eq schema.structure)
        }
    }

    // =========================================================================
    // valueSchema identity wire shape
    // =========================================================================

    "valueSchema identity wire shape (top-level)" - {

        "Str encodes as bare JSON string" in {
            val v    = Structure.Value.Str("hello")
            val json = Json.encode(v)
            assert(json == "\"hello\"")
        }

        "Str round-trips through JSON" in {
            val v       = Structure.Value.Str("hello")
            val encoded = Json.encode(v)
            val decoded = Json.decode[Structure.Value](encoded).getOrThrow
            assert(decoded == v)
        }

        "Integer encodes as bare JSON number" in {
            val v    = Structure.Value.Integer(42L)
            val json = Json.encode(v)
            assert(json == "42")
        }

        "Integer round-trips through JSON" in {
            val v       = Structure.Value.Integer(42L)
            val encoded = Json.encode(v)
            val decoded = Json.decode[Structure.Value](encoded).getOrThrow
            assert(decoded == v)
        }

        "Decimal encodes as bare JSON number" in {
            val v    = Structure.Value.Decimal(3.14)
            val json = Json.encode(v)
            assert(json.startsWith("3.14"))
        }

        "Decimal round-trips through JSON" in {
            val v       = Structure.Value.Decimal(3.14)
            val encoded = Json.encode(v)
            val decoded = Json.decode[Structure.Value](encoded).getOrThrow
            assert(decoded == v)
        }

        "Bool encodes as bare JSON boolean" in {
            val v    = Structure.Value.Bool(true)
            val json = Json.encode(v)
            assert(json == "true")
        }

        "Bool round-trips through JSON" in {
            val v       = Structure.Value.Bool(true)
            val encoded = Json.encode(v)
            val decoded = Json.decode[Structure.Value](encoded).getOrThrow
            assert(decoded == v)
        }

        "Null encodes as bare JSON null" in {
            val v    = Structure.Value.Null
            val json = Json.encode(v)
            assert(json == "null")
        }

        "Null round-trips through JSON" in {
            val v       = Structure.Value.Null
            val encoded = Json.encode(v)
            val decoded = Json.decode[Structure.Value](encoded).getOrThrow
            assert(decoded == v)
        }

        "Record encodes as JSON object (not tagged-union)" in {
            val v    = Structure.Value.Record(Chunk("path" -> Structure.Value.Str(".")))
            val json = Json.encode(v)
            assert(json.contains("\"path\""))
            assert(json.contains("\".\""))
            assert(!json.contains("\"Record\""))
        }

        "Record round-trips through JSON" in {
            val v       = Structure.Value.Record(Chunk("path" -> Structure.Value.Str(".")))
            val encoded = Json.encode(v)
            val decoded = Json.decode[Structure.Value](encoded).getOrThrow
            assert(decoded == v)
        }

        "Sequence encodes as JSON array (not tagged-union)" in {
            val v = Structure.Value.Sequence(Chunk(Structure.Value.Integer(1L), Structure.Value.Integer(2L), Structure.Value.Integer(3L)))
            val json = Json.encode(v)
            assert(json == "[1,2,3]")
            assert(!json.contains("\"Sequence\""))
        }

        "Sequence round-trips through JSON" in {
            val v = Structure.Value.Sequence(Chunk(Structure.Value.Integer(1L), Structure.Value.Integer(2L), Structure.Value.Integer(3L)))
            val encoded = Json.encode(v)
            val decoded = Json.decode[Structure.Value](encoded).getOrThrow
            assert(decoded == v)
        }

        "Schema[Structure.Value].structure is Type.Open" in {
            val schema = summon[Schema[Structure.Value]]
            schema.structure match
                case _: Structure.Type.Open => succeed
                case other                  => fail(s"Expected Type.Open but got $other")
            end match
        }

    }

    // =========================================================================
    // jsonSchemaSchema Draft 2020-12
    // =========================================================================

    "jsonSchemaSchema Draft 2020-12 (top-level)" - {

        "Obj(empty) encodes as JSON Schema object type" in {
            val v    = JsonSchema.Obj(List.empty, List.empty)
            val json = Json.encode(v)
            assert(json.contains("\"type\""))
            assert(json.contains("\"object\""))
            assert(!json.contains("\"Obj\""))
        }

        "Obj round-trips through JSON" in {
            val v       = JsonSchema.Obj(List.empty, List.empty)
            val encoded = Json.encode(v)
            val decoded = Json.decode[JsonSchema](encoded).getOrThrow
            assert(decoded == v)
        }

        "Str encodes as JSON Schema string type" in {
            val v    = JsonSchema.Str()
            val json = Json.encode(v)
            assert(json.contains("\"string\""))
            assert(!json.contains("\"Str\""))
        }

        "Str round-trips through JSON" in {
            val v       = JsonSchema.Str()
            val encoded = Json.encode(v)
            val decoded = Json.decode[JsonSchema](encoded).getOrThrow
            assert(decoded == v)
        }

        "Integer encodes as JSON Schema integer type" in {
            val v    = JsonSchema.Integer()
            val json = Json.encode(v)
            assert(json.contains("\"integer\""))
            assert(!json.contains("\"Integer\""))
        }

        "Integer round-trips through JSON" in {
            val v       = JsonSchema.Integer()
            val encoded = Json.encode(v)
            val decoded = Json.decode[JsonSchema](encoded).getOrThrow
            assert(decoded == v)
        }

        "Num encodes as JSON Schema number type" in {
            val v    = JsonSchema.Num()
            val json = Json.encode(v)
            assert(json.contains("\"number\""))
            assert(!json.contains("\"Num\""))
        }

        "Num round-trips through JSON" in {
            val v       = JsonSchema.Num()
            val encoded = Json.encode(v)
            val decoded = Json.decode[JsonSchema](encoded).getOrThrow
            assert(decoded == v)
        }

        "Bool encodes as JSON Schema boolean type" in {
            val v    = JsonSchema.Bool()
            val json = Json.encode(v)
            assert(json.contains("\"boolean\""))
            assert(!json.contains("\"Bool\""))
        }

        "Bool round-trips through JSON" in {
            val v       = JsonSchema.Bool()
            val encoded = Json.encode(v)
            val decoded = Json.decode[JsonSchema](encoded).getOrThrow
            assert(decoded == v)
        }

        "Null encodes as JSON Schema null type" in {
            val v    = JsonSchema.Null()
            val json = Json.encode(v)
            assert(json.contains("\"null\""))
            assert(!json.contains("\"Null\""))
        }

        "Null round-trips through JSON" in {
            val v       = JsonSchema.Null()
            val encoded = Json.encode(v)
            val decoded = Json.decode[JsonSchema](encoded).getOrThrow
            assert(decoded == v)
        }

        "Arr encodes as JSON Schema array type" in {
            val v    = JsonSchema.Arr(JsonSchema.Str())
            val json = Json.encode(v)
            assert(json.contains("\"array\""))
            assert(!json.contains("\"Arr\""))
        }

        "Arr round-trips through JSON" in {
            val v       = JsonSchema.Arr(JsonSchema.Str())
            val encoded = Json.encode(v)
            val decoded = Json.decode[JsonSchema](encoded).getOrThrow
            assert(decoded == v)
        }

        "Nullable encodes as oneOf with null (not tagged-union)" in {
            val v    = JsonSchema.Nullable(JsonSchema.Str())
            val json = Json.encode(v)
            assert(json.contains("\"oneOf\""))
            assert(!json.contains("\"Nullable\""))
        }

        "Nullable round-trips through JSON" in {
            val v       = JsonSchema.Nullable(JsonSchema.Str())
            val encoded = Json.encode(v)
            val decoded = Json.decode[JsonSchema](encoded).getOrThrow
            assert(decoded == v)
        }

        "OneOf encodes as JSON Schema oneOf (not tagged-union)" in {
            val v    = JsonSchema.OneOf(List("variant" -> JsonSchema.Obj(List.empty, List.empty)))
            val json = Json.encode(v)
            assert(json.contains("\"oneOf\""))
            assert(!json.contains("\"OneOf\""))
        }

        "OneOf round-trips through JSON" in {
            val v       = JsonSchema.OneOf(List("variant" -> JsonSchema.Obj(List.empty, List.empty)))
            val encoded = Json.encode(v)
            val decoded = Json.decode[JsonSchema](encoded).getOrThrow
            assert(decoded == v)
        }

        "Schema[JsonSchema].structure is Type.Open" in {
            val schema = summon[Schema[JsonSchema]]
            schema.structure match
                case _: Structure.Type.Open => succeed
                case other                  => fail(s"Expected Type.Open but got $other")
            end match
        }

    }

    // =========================================================================
    // structure variant direct check
    // =========================================================================

    "Schema[Structure.Value] and Schema[JsonSchema] structure variant" - {

        "Schema[Structure.Value].structure is Type.Open (not Primitive, Product, or Sum)" in {
            val schema = summon[Schema[Structure.Value]]
            schema.structure match
                case _: Structure.Type.Open      => succeed
                case _: Structure.Type.Primitive => fail("Expected Type.Open but got Primitive")
                case _: Structure.Type.Product   => fail("Expected Type.Open but got Product")
                case _: Structure.Type.Sum       => fail("Expected Type.Open but got Sum")
                case other                       => fail(s"Expected Type.Open but got $other")
            end match
        }

        "Schema[Structure.Value].structure tag is compatible with Tag[Structure.Value]" in {
            val schema = summon[Schema[Structure.Value]]
            schema.structure match
                case open: Structure.Type.Open =>
                    assert(open.tag =:= Tag[Structure.Value].asInstanceOf[Tag[Any]])
                case other => fail(s"Expected Type.Open but got $other")
            end match
        }

        "Schema[JsonSchema].structure is Type.Open (not Primitive, Product, or Sum)" in {
            val schema = summon[Schema[JsonSchema]]
            schema.structure match
                case _: Structure.Type.Open      => succeed
                case _: Structure.Type.Primitive => fail("Expected Type.Open but got Primitive")
                case _: Structure.Type.Product   => fail("Expected Type.Open but got Product")
                case _: Structure.Type.Sum       => fail("Expected Type.Open but got Sum")
                case other                       => fail(s"Expected Type.Open but got $other")
            end match
        }

        "Schema[JsonSchema].structure tag is compatible with Tag[JsonSchema]" in {
            val schema = summon[Schema[JsonSchema]]
            schema.structure match
                case open: Structure.Type.Open =>
                    assert(open.tag =:= Tag[JsonSchema].asInstanceOf[Tag[Any]])
                case other => fail(s"Expected Type.Open but got $other")
            end match
        }

        "Structure.Type.compatible returns true for same schema structure" in {
            val schema = summon[Schema[Structure.Value]]
            assert(Structure.Type.compatible(schema.structure, schema.structure))
        }

        "Structure.Type.compatible returns false for Structure.Value vs JsonSchema structures" in {
            val valueS = summon[Schema[Structure.Value]]
            val jsonS  = summon[Schema[JsonSchema]]
            assert(!Structure.Type.compatible(valueS.structure, jsonS.structure))
        }

    }

    // =========================================================================
    // transform structure propagation
    // =========================================================================

    "transform structure propagation" - {

        "longSchema.transform[Duration].structure is the same reference as longSchema.structure" in {
            val source  = Schema.longSchema
            val derived = source.transform[kyo.Duration](kyo.Duration.fromNanos)(_.toNanos)
            assert(derived.structure.eq(source.structure))
        }

        "longSchema.transform[Duration].structure tag equals Tag[Long], not Tag[Duration]" in {
            val source  = Schema.longSchema
            val derived = source.transform[kyo.Duration](kyo.Duration.fromNanos)(_.toNanos)
            derived.structure match
                case p: Structure.Type.Primitive =>
                    assert(p.tag =:= Tag[Long].asInstanceOf[Tag[Any]])
                case other => fail(s"Expected Primitive structure but got $other")
            end match
        }

        "instantSchema.transform[kyo.Instant].structure is the same reference as instantSchema.structure" in {
            val source  = Schema.instantSchema
            val derived = source.transform[kyo.Instant](kyo.Instant.fromJava)(_.toJava)
            assert(derived.structure.eq(source.structure))
        }

        "instantSchema.transform[kyo.Instant].structure is Type.Primitive" in {
            val source  = Schema.instantSchema
            val derived = source.transform[kyo.Instant](kyo.Instant.fromJava)(_.toJava)
            derived.structure match
                case _: Structure.Type.Primitive => succeed
                case other                       => fail(s"Expected Primitive structure but got $other")
            end match
        }

        "kyoInstantSchema.structure is the same reference as instantSchema.structure" in {
            assert(Schema.kyoInstantSchema.structure.eq(Schema.instantSchema.structure))
        }

        "kyoDurationSchema.structure is the same reference as longSchema.structure" in {
            assert(Schema.kyoDurationSchema.structure.eq(Schema.longSchema.structure))
        }

    }

    // =========================================================================
    // transform Structure.compatible
    // =========================================================================

    "transform Structure.compatible" - {

        "Structure.Type.compatible(longSchema.structure, kyoDurationSchema.structure) returns true" in {
            assert(Structure.Type.compatible(Schema.longSchema.structure, Schema.kyoDurationSchema.structure))
        }

        "Structure.Type.compatible(instantSchema.structure, kyoInstantSchema.structure) returns true" in {
            assert(Structure.Type.compatible(Schema.instantSchema.structure, Schema.kyoInstantSchema.structure))
        }

    }

    // =========================================================================
    // Suite: derived structure emission
    // =========================================================================

    // Test data types for derived structure tests
    case class P08Person(name: String, age: Int) derives Schema
    sealed trait P08Shape derives Schema
    object P08Shape:
        case class Circle(r: Double) extends P08Shape derives Schema
        case class Square(s: Double) extends P08Shape derives Schema
        case object Origin           extends P08Shape
    end P08Shape
    class P08NoSchemaMarker
    case class P08Wrapper(inner: P08NoSchemaMarker)

    "derived case-class structure" - {

        "variant is Product" in {
            val s = summon[Schema[P08Person]]
            assert(s.structure.isInstanceOf[Structure.Type.Product])
        }

        "name is Person" in {
            summon[Schema[P08Person]].structure match
                case p: Structure.Type.Product => assert(p.name == "P08Person")
                case other                     => fail(s"Expected Product but got $other")
        }

        "fields.size is 2" in {
            summon[Schema[P08Person]].structure match
                case p: Structure.Type.Product => assert(p.fields.size == 2)
                case other                     => fail(s"Expected Product but got $other")
        }

        "fields(0).name is name" in {
            summon[Schema[P08Person]].structure match
                case p: Structure.Type.Product => assert(p.fields(0).name == "name")
                case other                     => fail(s"Expected Product but got $other")
        }

        "fields(0).fieldType eq Schema[String].structure" in {
            summon[Schema[P08Person]].structure match
                case p: Structure.Type.Product =>
                    assert(
                        p.fields(0).fieldType eq summon[Schema[String]].structure,
                        s"expected reference-eq to Schema[String].structure but got ${p.fields(0).fieldType}"
                    )
                case other => fail(s"Expected Product but got $other")
        }

        "fields(1).name is age" in {
            summon[Schema[P08Person]].structure match
                case p: Structure.Type.Product => assert(p.fields(1).name == "age")
                case other                     => fail(s"Expected Product but got $other")
        }

        "fields(1).fieldType eq Schema[Int].structure" in {
            summon[Schema[P08Person]].structure match
                case p: Structure.Type.Product =>
                    assert(
                        p.fields(1).fieldType eq summon[Schema[Int]].structure,
                        s"expected reference-eq to Schema[Int].structure but got ${p.fields(1).fieldType}"
                    )
                case other => fail(s"Expected Product but got $other")
        }

    }

    "derived sealed-trait structure" - {

        "variant is Sum" in {
            assert(summon[Schema[P08Shape]].structure.isInstanceOf[Structure.Type.Sum])
        }

        "variants.size is 3" in {
            summon[Schema[P08Shape]].structure match
                case s: Structure.Type.Sum => assert(s.variants.size == 3)
                case other                 => fail(s"Expected Sum but got $other")
        }

        "variant Circle.variantType compatible with Schema[Circle].structure" in {
            summon[Schema[P08Shape]].structure match
                case s: Structure.Type.Sum =>
                    val circleVariant = s.variants.find(_.name == "Circle").getOrElse(fail("no Circle variant"))
                    assert(
                        Structure.Type.compatible(circleVariant.variantType, summon[Schema[P08Shape.Circle]].structure),
                        s"Circle variantType=${circleVariant.variantType} incompatible with Schema[Circle].structure"
                    )
                case other => fail(s"Expected Sum but got $other")
        }

        "variant Square.variantType compatible with Schema[Square].structure" in {
            summon[Schema[P08Shape]].structure match
                case s: Structure.Type.Sum =>
                    val sqVariant = s.variants.find(_.name == "Square").getOrElse(fail("no Square variant"))
                    assert(
                        Structure.Type.compatible(sqVariant.variantType, summon[Schema[P08Shape.Square]].structure),
                        s"Square variantType=${sqVariant.variantType} incompatible with Schema[Square].structure"
                    )
                case other => fail(s"Expected Sum but got $other")
        }

    }

    "missing Schema produces precise error" - {

        "derives Schema on case class with a field type lacking Schema fails at compile time" in {
            typeCheckFailure("kyo.Schema.derived[SchemaStructureTest.this.P08Wrapper]")(
                "P08NoSchemaMarker"
            )
        }

    }

    "internal builder preserves structure" - {

        "check clone does not change structure" in {
            // MTPerson is a top-level type where Focus navigation works correctly.
            val base   = Schema[MTPerson]
            val cloned = base.check(_.name)(_.nonEmpty, "required")
            assert(
                cloned.structure eq base.structure,
                s"check clone changed structure: was ${base.structure}, got ${cloned.structure}"
            )
        }

    }

    "structural referential transparency for derived types" - {

        "Schema[P08Person].structure eq Schema[P08Person].structure" in {
            val s = summon[Schema[P08Person]]
            assert(s.structure eq s.structure)
        }

    }

    "Schema.init structure default" - {

        "defaults structure to Open when omitted from Schema.init" in {
            val s = Schema.init[String](
                writeFn = (v, w) => w.string(v),
                readFn = _.string()
            )
            assert(s.structure.isInstanceOf[Structure.Type.Open])
        }

    }

    "localDateTimeSchema structure tag" - {

        "localDateTimeSchema has Primitive(String, _) structure" in {
            val s = summon[Schema[java.time.LocalDateTime]]
            assert(s.structure.isInstanceOf[Structure.Type.Primitive])
            val p = s.structure.asInstanceOf[Structure.Type.Primitive]
            assert(p.kind == Structure.PrimitiveKind.String)
        }

    }

    "macro consumers Schema-driven" - {

        // Verifies that ExpandMacro classifies primitive, container, and optional fields uniformly
        // through MacroSchemaClassifier, producing the same JSON round-trip shape as a case class
        // with only primitive fields.
        case class MacroClassifierRegressionFixture(
            id: Int,
            name: String,
            tags: List[String],
            maybeAge: Maybe[Int]
        ) derives CanEqual

        "JSON round-trip of a mixed primitive/container/optional case class" in {
            val schema = summon[Schema[MacroClassifierRegressionFixture]]
            val value  = MacroClassifierRegressionFixture(42, "Alice", List("admin", "user"), Maybe(30))
            val w      = JsonWriter()
            schema.writeTo(value, w)
            val reader = JsonReader(w.resultString)
            val result = schema.readFrom(reader)
            assert(result == value)
        }

    }

    "Schema derivation (SchemaDerivedMacro)" - {

        "Self-recursive case class derives Schema without StackOverflow" in {
            case class Tree(children: List[Tree]) derives Schema
            val s  = summon[Schema[Tree]]
            val st = s.structure
            assert(st.isInstanceOf[Structure.Type.Product])
            val prod = st.asInstanceOf[Structure.Type.Product]
            assert(prod.name == "Tree")
            val fieldType = prod.fields(0).fieldType
            assert(fieldType.isInstanceOf[Structure.Type.Collection])
            val coll = fieldType.asInstanceOf[Structure.Type.Collection]
            // The element type is a Product(Tree,...) with the correct name.
            // derives Schema uses a cycle-safe stub for the recursive element type
            // (buildCaseClassStructureExpr uses deriveTypeFallback for recursive fields).
            assert(coll.elementType.isInstanceOf[Structure.Type.Product])
            assert(coll.elementType.asInstanceOf[Structure.Type.Product].name == "Tree")
        }

        "Maybe and Option fields are optional and primitive defaults use their Value variant" in {
            case class Q(
                a: Int = 42,
                b: Maybe[String] = Maybe.empty,
                c: Option[Boolean] = None,
                d: BigDecimal = BigDecimal(1)
            ) derives Schema
            val s      = summon[Schema[Q]]
            val fields = s.structure.asInstanceOf[Structure.Type.Product].fields
            assert(fields(0).optional == false)
            assert(fields(0).default == Maybe(Structure.Value.Integer(42L)))
            assert(fields(1).optional == true)
            assert(fields(2).optional == true)
            assert(fields(3).optional == false)
            assert(fields(3).default == Maybe(Structure.Value.BigNum(BigDecimal(1))))
        }

        "Derivation rejects case class with a private case-field" in {
            val src      = "case class Bad(private val x: Int) derives kyo.Schema"
            val compiles = scala.compiletime.testing.typeChecks(src)
            assert(!compiles)
            val errs = scala.compiletime.testing.typeCheckErrors(src)
            assert(errs.nonEmpty)
            assert(errs.head.message.contains("private") && errs.head.message.contains("x"))
        }

    }

end SchemaStructureTest
