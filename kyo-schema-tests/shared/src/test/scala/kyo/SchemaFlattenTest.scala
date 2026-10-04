package kyo

class SchemaFlattenTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    private val person = MTPersonAddr("Alice", 30, MTAddress("Main St", "Portland", "97201"))

    private def jsonKeys(wire: String)(using Frame, kyo.test.AssertScope): Set[String] =
        Json.decode[Structure.Value](wire) match
            case Result.Success(Structure.Value.Record(fields)) => fields.map(_._1).toSet
            case other                                          => fail(s"not a JSON object: $wire ($other)")

    private def assertDecodes[A](schema: Schema[A], wire: String, expected: A)(using Frame, kyo.test.AssertScope): Unit =
        val decoded = schema.decodeString[Json](wire)
        assert(decoded == Result.succeed(expected), s"$wire decoded to $decoded")

    private def roundTripString[C <: Codec, A](s: Schema[A], value: A)(using C, Frame, kyo.test.AssertScope): Unit =
        val wire    = s.encodeString[C](value)
        val decoded = s.decodeString[C](wire)
        assert(decoded == Result.succeed(value), s"string round-trip failed for $value via $wire: $decoded")
    end roundTripString

    private def roundTripBytes[C <: Codec, A](s: Schema[A], value: A)(using C, Frame, kyo.test.AssertScope): Unit =
        val wire = s.encode[C](value)
        assert(s.decode[C](wire) == Result.succeed(value), s"byte round-trip failed for $value")
    end roundTripBytes

    // A layout that cannot round-trip is the schema's configuration problem: the first decode raises it before reading the input.
    private def firstDecodeFailure[A](schema: Schema[A])(using Frame): Maybe[Throwable] =
        schema.decodeString[Json]("{}") match
            case Result.Panic(e) => Present(e)
            case _               => Absent

    "writes and reads the nested record's fields at the parent level" in {
        val schema = Schema[MTPersonAddr].flatten
        val flat   = """{"name":"Alice","age":30,"street":"Main St","city":"Portland","zip":"97201"}"""
        val wire   = schema.encodeString[Json](person)
        assert(wire == flat, wire)
        val decoded = schema.decodeString[Json](flat)
        assert(decoded == Result.succeed(person), decoded.toString)
    }

    "composes with a rename of a parent field" in {
        val schema = Schema[MTPersonAddr].flatten.rename("name", "full_name")
        val wire   = schema.encodeString[Json](person)
        assert(jsonKeys(wire) == Set("full_name", "age", "street", "city", "zip"), wire)
        assertDecodes(schema, wire, person)
    }

    "composes with a rename of the flattened field itself" in {
        val schema = Schema[MTPersonAddr].rename("address", "home").flatten
        val wire   = schema.encodeString[Json](person)
        assert(jsonKeys(wire) == Set("name", "age", "street", "city", "zip"), wire)
        assertDecodes(schema, wire, person)
    }

    "decodes the nested form written without flatten" in {
        val schema = Schema[MTPersonAddr].flatten
        val nested = Schema[MTPersonAddr].encodeString[Json](person)
        assert(nested == """{"name":"Alice","age":30,"address":{"street":"Main St","city":"Portland","zip":"97201"}}""", nested)
        assertDecodes(schema, nested, person)
    }

    "a failure inside a flattened record names its wire key at the parent level, not the flattened field" in {
        val schema  = Schema[MTPersonAddr].flatten
        val snake   = Schema[FLCamelPerson].flatten.renameAllFields(Schema.NameCase.SnakeCase)
        val zipped  = Schema[FLZipPerson].flatten(_.address)
        val results = Chunk(
            schema.decodeString[Json]("""{"name":"A","age":1,"street":"s","city":"c"}"""),
            schema.decodeString[Json]("""{"name":"A","age":1,"street":5,"city":"c","zip":"z"}"""),
            snake.decodeString[Json]("""{"full_name":"A","street_name":"s"}"""),
            snake.decodeString[Json]("""{"full_name":"A","street_name":5,"zip_code":"z"}"""),
            zipped.decodeString[Json]("""{"name":"A","street":"s"}"""),
            zipped.decodeString[Json]("""{"name":"A","street":"s","zip_code":5}""")
        )
        assert(
            results == Chunk(
                Result.fail(MissingFieldException(Nil, "zip")),
                Result.fail(TypeMismatchException(List("street"), "string", "number")),
                Result.fail(MissingFieldException(Nil, "zip_code")),
                Result.fail(TypeMismatchException(List("street_name"), "string", "number")),
                Result.fail(MissingFieldException(Nil, "zip_code")),
                Result.fail(TypeMismatchException(List("zip_code"), "string", "number"))
            ),
            results.map(_.failure.map(_.getMessage)).toString
        )
    }

    "two flattened fields that share a key are rejected at compile time" in {
        typeCheckFailure("kyo.Schema[kyo.FLOrder].flatten")(
            "Wire name 'street' is targeted by 2 fields: billing.street, shipping.street. Give each field a distinct wire name."
        )
    }

    "a flattened key equal to a kept parent key is rejected at compile time" in {
        typeCheckFailure("kyo.Schema[kyo.FLLabelled].flatten")(
            "Wire name 'name' is targeted by 2 fields: name, tag.name. Give each field a distinct wire name."
        )
    }

    "a flattened key whose wire name equals a kept parent key is rejected at the first decode" in {
        firstDecodeFailure(Schema[FLCodeParent].flatten) match
            case Present(e: FieldNameCollisionException) => assert(e == FieldNameCollisionException("id", Chunk("id", "child.code")))
            case other                                   => fail(s"expected a FieldNameCollisionException, got $other")
    }

    "maps a child's own @rename on write and read" in {
        val schema = Schema[FLZipPerson].flatten
        val value  = FLZipPerson("Alice", FLZipAddress("Main St", "97201"))
        val wire   = schema.encodeString[Json](value)
        assert(wire == """{"name":"Alice","street":"Main St","zip_code":"97201"}""", wire)
        assertDecodes(schema, wire, value)
        assertDecodes(schema.denyUnknownFields, wire, value)
    }

    "under the parent's renameAllFields, a flattened child's explicit @rename is kept and its other keys are cased" in {
        val value = FLExplicitPerson("Alice", FLExplicitAddress("Main St", "97201"))
        val flat  = Schema[FLExplicitPerson].renameAllFields(Schema.NameCase.SnakeCase).flatten.encodeString[Json](value)
        assert(flat == """{"name":"Alice","street_name":"Main St","zipCode":"97201"}""", flat)
    }

    "flatten(_.sum) checks collisions against the sum's variants as the sum derives them" - {

        "a sub-trait with its own given is one variant" in {
            val errors = scala.compiletime.testing.typeCheckErrors("kyo.Schema[kyo.FLGroupedParent].flatten(_.event)")
            assert(errors.isEmpty, errors.map(_.message).mkString("; "))
        }

        "a variant field renamed away from a parent key does not collide" in {
            val errors = scala.compiletime.testing.typeCheckErrors("kyo.Schema[kyo.FLRenamedAwayParent].flatten(_.event)")
            assert(errors.isEmpty, errors.map(_.message).mkString("; "))
        }

        "a variant field renamed onto a parent key collides" in {
            val errors = scala.compiletime.testing.typeCheckErrors("kyo.Schema[kyo.FLRenamedOntoParent].flatten(_.event)")
            assert(errors.exists(_.message.contains("'id'")), errors.map(_.message).mkString("; "))
        }
    }

    "maps a child's given with its own rename on write and read" in {
        val schema = Schema[FLGivenPerson].flatten
        val value  = FLGivenPerson("Alice", FLGivenAddress("Main St", "97201"))
        val wire   = schema.encodeString[Json](value)
        assert(wire == """{"name":"Alice","street":"Main St","postal":"97201"}""", wire)
        assertDecodes(schema, wire, value)
    }

    "a value of the wrong kind fails with the same message as without flatten" in {
        // The message text is the path and these two kinds; `getMessage` also renders the call site's frame, which differs per decode.
        // The path differs by design: the flattened input holds the key at the parent level.
        def mismatch(result: Result[DecodeException, MTPersonAddr]): (String, String) =
            result match
                case Result.Failure(e: TypeMismatchException) => (e.expected, e.actual)
                case other                                    => fail(s"expected TypeMismatchException, got $other")
        def path(result: Result[DecodeException, MTPersonAddr]): Seq[String] =
            result.failure.collect { case e: TypeMismatchException => e.path }.getOrElse(Seq.empty)
        val schema = Schema[MTPersonAddr].flatten
        val cases  = Chunk(
            ("street", "5"),
            ("street", "true"),
            ("street", "{}"),
            ("street", "[]"),
            ("age", "\"thirty\"")
        )
        cases.foreach { (key, wrong) =>
            val nested = Json.decode[MTPersonAddr](
                if key == "age" then s"""{"name":"Alice","age":$wrong,"address":{"street":"Main St","city":"Portland","zip":"97201"}}"""
                else s"""{"name":"Alice","age":30,"address":{"street":$wrong,"city":"Portland","zip":"97201"}}"""
            )
            val flat = schema.decodeString[Json](
                if key == "age" then s"""{"name":"Alice","age":$wrong,"street":"Main St","city":"Portland","zip":"97201"}"""
                else s"""{"name":"Alice","age":30,"street":$wrong,"city":"Portland","zip":"97201"}"""
            )
            assert(mismatch(flat) == mismatch(nested), s"$key: $wrong: ${mismatch(flat)} vs ${mismatch(nested)}")
            assert(path(flat) == Seq(key), s"$key: $wrong: ${path(flat)}")
        }
        succeed
    }

    "a flattened integer field refuses a fraction and a value outside its range instead of truncating" in {
        val schema = Schema[FLCountHolder].flatten
        assert(schema.decodeString[Json]("""{"name":"a","n":7}""") == Result.succeed(FLCountHolder("a", FLCount(7))))
        assert(schema.decodeString[Json]("""{"name":"a","n":7.0}""") == Result.succeed(FLCountHolder("a", FLCount(7))))
        schema.decodeString[Json]("""{"name":"a","n":1.5}""") match
            case Result.Failure(e: TypeMismatchException) => assert((e.expected, e.actual) == ("Int", "a number with a fraction"))
            case other                                    => fail(s"expected TypeMismatchException, got $other")
        schema.decodeString[Json]("""{"name":"a","n":5000000000}""") match
            case Result.Failure(e: RangeException) => assert((e.value, e.targetType) == (BigDecimal(5000000000L), "Int"))
            case other                             => fail(s"expected RangeException, got $other")
        schema.decodeString[Json]("""{"name":"a","n":1e30}""") match
            case Result.Failure(e: RangeException) => assert((e.value, e.targetType) == (BigDecimal("1e30"), "Int"))
            case other                             => fail(s"expected RangeException, got $other")
    }

    "a malformed Base64 value in a flattened field is a decode failure" in {
        val schema = Schema[FLBlobHolder].flatten
        schema.decodeString[Json]("""{"name":"a","data":"%%%"}""") match
            case Result.Failure(e: TypeMismatchException) =>
                assert(e.path.lastOption == Some("data"), e.getMessage)
                assert(e.actual == "'%%%'", e.getMessage)
            case other => fail(s"expected TypeMismatchException at 'data', got $other")
        end match
    }

    "round-trips on every self-describing codec" in {
        val schema = Schema[MTPersonAddr].flatten
        roundTripString[Json, MTPersonAddr](schema, person)
        roundTripString[Yaml, MTPersonAddr](schema, person)
        roundTripString[Ion, MTPersonAddr](schema, person)
        roundTripBytes[MsgPack, MTPersonAddr](schema, person)
        roundTripBytes[Bson, MTPersonAddr](schema, person)
        roundTripBytes[IonBinary, MTPersonAddr](schema, person)
    }

    "is refused by Protobuf on encode, which still decodes the nested form" in {
        val schema = Schema[MTPersonAddr].flatten
        val result = Result.catching[TransformUnsupportedException](schema.encode[Protobuf](person))
        assert(result == Result.fail(TransformUnsupportedException("Protobuf", "flatten")), result.toString)
        val nested = Schema[MTPersonAddr].encode[Protobuf](person)
        assert(schema.decode[Protobuf](nested) == Result.succeed(person))
    }

    "is refused by Protobuf when the flattened schema is a field of a transformed parent" in {
        val schema = summon[Schema[FLHolder]].renameAllFields(Schema.NameCase.SnakeCase)
        val value  = FLHolder("x", person)
        val result = Result.catching[TransformUnsupportedException](schema.encode[Protobuf](value))
        assert(result == Result.fail(TransformUnsupportedException("Protobuf", "flatten")), result.toString)
        val wire = schema.encodeString[Json](value)
        assert(
            wire == """{"holder_label":"x","person":{"name":"Alice","age":30,"street":"Main St","city":"Portland","zip":"97201"}}""",
            wire
        )
        assertDecodes(schema, wire, value)
    }

    "applies renameAllFields to the flattened keys, in either builder order" in {
        val value = FLCamelPerson("Alice", FLCamelAddress("Main St", "97201"))
        val keys  = Set("full_name", "street_name", "zip_code")
        Chunk(
            Schema[FLCamelPerson].flatten.renameAllFields(Schema.NameCase.SnakeCase),
            Schema[FLCamelPerson].renameAllFields(Schema.NameCase.SnakeCase).flatten
        ).foreach { schema =>
            val wire = schema.encodeString[Json](value)
            assert(jsonKeys(wire) == keys, wire)
            assertDecodes(schema, wire, value)
        }
    }

    "round-trips a child whose Maybe field is Absent" in {
        val schema = Schema[FLNoteParent].flatten
        val value  = FLNoteParent(1, FLNoteChild(Maybe.empty, "sku"))
        val wire   = schema.encodeString[Json](value)
        assert(jsonKeys(wire) == Set("id", "code"), wire)
        assertDecodes(schema, wire, value)
    }

    "round-trips a child that writes no keys" in {
        val schema = Schema[FLOptParent].flatten
        val value  = FLOptParent(1, FLOptChild(Maybe.empty))
        val wire   = schema.encodeString[Json](value)
        assert(wire == """{"id":1}""", wire)
        assertDecodes(schema, wire, value)
    }

    "round-trips a child field omitted by its own @omit" in {
        val schema = Schema[FLTagsParent].flatten
        val value  = FLTagsParent(1, FLTagsChild(Nil, "sku"))
        val wire   = schema.encodeString[Json](value)
        assert(wire == """{"id":1,"code":"sku"}""", wire)
        assertDecodes(schema, wire, value)
    }

    "round-trips under denyUnknownFields" in {
        val schema = Schema[MTPersonAddr].flatten.denyUnknownFields
        val wire   = schema.encodeString[Json](person)
        assertDecodes(schema, wire, person)
    }

    "a builder that names a flattened child field is rejected at the first decode" in {
        firstDecodeFailure(Schema[MTPersonAddr].flatten.rename("city", "town")) match
            case Present(e: TransformFailedException) =>
                assert(e == TransformFailedException(
                    "'city' is a field of the flattened field 'address'; configure it on the schema of the flattened field's type"
                ))
            case other => fail(s"expected a TransformFailedException, got $other")
    }

    "flatten(_.field) flattens that field only" - {

        "keeps the other record fields nested" in {
            val schema = Schema[FLOrder].flatten(_.billing)
            val value  = FLOrder(1, FLAddress("a", "b"), FLAddress("c", "d"))
            val wire   = schema.encodeString[Json](value)
            assert(wire == """{"id":1,"street":"a","city":"b","shipping":{"street":"c","city":"d"}}""", wire)
            assertDecodes(schema, wire, value)
        }

        "round-trips on Yaml" in {
            roundTripString[Yaml, FLOrder](Schema[FLOrder].flatten(_.billing), FLOrder(1, FLAddress("a", "b"), FLAddress("c", "d")))
        }

        "two flattened fields whose fields share names are a compile error" in {
            typeCheckFailure("kyo.Schema[kyo.FLOrder].flatten(_.billing).flatten(_.shipping)")("targeted by")
        }

        "a field that is neither a record nor a sum is a compile error" in {
            typeCheckFailure("kyo.Schema[kyo.FLOrder].flatten(_.id)")("not a case class or a sealed sum")
        }
    }

    "flatten(_.field) on a sum moves the variant's keys to the parent level" - {

        val entitySchema  = Schema[FLEntity].flatten(_.kind)
        val messageSchema = Schema[FLMessage].flatten(_.content)
        val mediaSchema   = Schema[FLMediaObject].flatten(_.ref)

        "a discriminated sum writes its tag and the variant's fields beside the parent's" in {
            val value = FLEntity(0, 4, FLKind.TextLink("https://x.test"))
            val wire  = entitySchema.encodeString[Json](value)
            assert(wire == """{"offset":0,"length":4,"type":"text_link","url":"https://x.test"}""", wire)
            assertDecodes(entitySchema, wire, value)
            assertDecodes(entitySchema, """{"type":"bold","offset":2,"length":1}""", FLEntity(2, 1, FLKind.Bold))
        }

        "a discriminated catch-all receives the whole parent object and writes it back" in {
            val wire    = """{"offset":0,"length":4,"type":"spoiler","depth":3}"""
            val decoded = entitySchema.decodeString[Json](wire)
            val whole   = Json.decode[Structure.Value](wire).getOrThrow
            assert(decoded == Result.succeed(FLEntity(0, 4, FLKind.Other("spoiler", whole))), decoded.toString)
            val rewritten = entitySchema.encodeString[Json](decoded.getOrThrow)
            assert(Json.decode[Structure.Value](rewritten).map(unordered) == Result.succeed(unordered(whole)), rewritten)
        }

        "an untagged sum writes the variant's fields beside the parent's" in {
            val value = FLMessage(1, 2L, FLContent.Photo("p1", Present("c")))
            val wire  = messageSchema.encodeString[Json](value)
            assert(wire == """{"id":1,"date":2,"photo":"p1","caption":"c"}""", wire)
            assertDecodes(messageSchema, wire, value)
            assertDecodes(messageSchema, """{"id":1,"date":2,"text":"hi"}""", FLMessage(1, 2L, FLContent.Text("hi")))
        }

        "an untagged catch-all receives the whole parent object, the parent's own keys included, and writes it back" in {
            val wire    = """{"id":1,"date":2,"poll":{"question":"q"}}"""
            val whole   = Json.decode[Structure.Value](wire).getOrThrow
            val decoded = messageSchema.decodeString[Json](wire)
            assert(decoded == Result.succeed(FLMessage(1, 2L, FLContent.Unknown(whole))), decoded.toString)
            val rewritten = messageSchema.encodeString[Json](decoded.getOrThrow)
            assert(Json.decode[Structure.Value](rewritten).map(unordered) == Result.succeed(unordered(whole)), rewritten)
        }

        "a catch-all's copy of a parent key does not override the parent's value" in {
            val stale     = Json.decode[Structure.Value]("""{"id":9,"date":9,"poll":{}}""").getOrThrow
            val rewritten = messageSchema.encodeString[Json](FLMessage(1, 2L, FLContent.Unknown(stale)))
            assert(
                Json.decode[Structure.Value](rewritten).map(unordered) ==
                    Json.decode[Structure.Value]("""{"id":1,"date":2,"poll":{}}""").map(unordered),
                rewritten
            )
        }

        "an id-or-link reference spreads beside the object's other fields" in {
            Chunk(FLMediaObject(FLRef.ById("m1"), Present("c")), FLMediaObject(FLRef.ByLink("https://x.test/a"), Absent)).foreach {
                value =>
                    val wire = mediaSchema.encodeString[Json](value)
                    assertDecodes(mediaSchema, wire, value)
            }
            assert(mediaSchema.encodeString[Json](FLMediaObject(FLRef.ById("m1"), Present("c"))) == """{"id":"m1","caption":"c"}""")
        }

        "a send body: call arguments beside a discriminated message" in {
            val schema = Schema[FLSend].flatten(_.message)
            val value  = FLSend("15551234", Present("wamid.1"), FLKind.Text(FLBody("hi")))
            val wire   = schema.encodeString[Json](value)
            assert(wire == """{"to":"15551234","context":"wamid.1","type":"text","text":{"body":"hi"}}""", wire)
            assertDecodes(schema, wire, value)
        }

        "the nested form still decodes" in {
            val value = FLEntity(0, 4, FLKind.TextLink("u"))
            assertDecodes(entitySchema, Schema[FLEntity].encodeString[Json](value), value)
        }

        "flatten without a field keeps a sum field nested" in {
            val wire = Schema[FLEntity].flatten.encodeString[Json](FLEntity(0, 4, FLKind.Bold))
            assert(wire == """{"offset":0,"length":4,"kind":{"type":"bold"}}""", wire)
        }

        "adjacent and wrapper-object sums round-trip flattened" in {
            val adjacent = Schema[FLAdjacentHolder].flatten(_.shape)
            val adjValue = FLAdjacentHolder("a", FLAdjacent.Circle(2))
            val adjWire  = adjacent.encodeString[Json](adjValue)
            assert(adjWire == """{"label":"a","kind":"Circle","data":{"radius":2}}""", adjWire)
            assertDecodes(adjacent, adjWire, adjValue)
            val wrapper = Schema[FLWrapperHolder].flatten(_.shape)
            val wValue  = FLWrapperHolder("a", FLWrapper.Square(3))
            val wWire   = wrapper.encodeString[Json](wValue)
            assert(wWire == """{"label":"a","Square":{"side":3}}""", wWire)
            assertDecodes(wrapper, wWire, wValue)
        }

        "a failing variant names the key at the parent level, not the flattened field" in {
            entitySchema.decodeString[Json]("""{"offset":0,"length":4,"type":"text_link"}""") match
                case Result.Failure(e: MissingFieldException) => assert(e.path.isEmpty && e.fieldName == "url", s"${e.path} ${e.fieldName}")
                case other                                    => fail(s"expected a MissingFieldException, got $other")
        }

        "round-trips on every self-describing codec that writes the sum's representation; Bson refuses untagged, Protobuf flatten" in {
            val entity  = FLEntity(0, 4, FLKind.TextLink("u"))
            val message = FLMessage(1, 2L, FLContent.Photo("p", Absent))
            roundTripString[Yaml, FLEntity](entitySchema, entity)
            roundTripString[Ion, FLEntity](entitySchema, entity)
            roundTripBytes[IonBinary, FLEntity](entitySchema, entity)
            roundTripBytes[MsgPack, FLEntity](entitySchema, entity)
            roundTripBytes[Bson, FLEntity](entitySchema, entity)
            roundTripString[Yaml, FLMessage](messageSchema, message)
            roundTripString[Ion, FLMessage](messageSchema, message)
            roundTripBytes[IonBinary, FLMessage](messageSchema, message)
            roundTripBytes[MsgPack, FLMessage](messageSchema, message)
            val bsonUntagged = Result.catching[RepresentationUnsupportedException](messageSchema.encode[Bson](message))
            assert(
                bsonUntagged.failure.exists(e => e.codec == "Bson" && e.representation == "Untagged"),
                bsonUntagged.toString
            )
            val result = Result.catching[TransformUnsupportedException](entitySchema.encode[Protobuf](entity))
            assert(result == Result.fail(TransformUnsupportedException("Protobuf", "flatten")), result.toString)
        }

        "a variant field named like a parent field is a compile error naming both" in {
            typeCheckFailure("kyo.Schema[kyo.FLClash].flatten(_.kind)")(
                "Wire name 'url' is targeted by 2 fields: url, kind.TextLink.url. Give each field a distinct wire name."
            )
        }

        "a parent field named like the discriminator is rejected at the first decode" in {
            firstDecodeFailure(Schema[FLTypedEntity].flatten(_.kind)) match
                case Present(e: FieldNameCollisionException) => assert(e == FieldNameCollisionException("type", Chunk("type", "kind.type")))
                case other                                   => fail(s"expected a FieldNameCollisionException, got $other")
        }

        "a renamed variant field that collides with a parent key is a compile error naming both" in {
            typeCheckFailure("kyo.Schema[kyo.FLRenamedClash].flatten(_.kind)")(
                "Wire name 'title' is targeted by 2 fields: title, kind.Named.name. Give each field a distinct wire name."
            )
        }

        "a variant laid out by its own given that collides with a parent key is refused on write, naming both" in {
            val schema = Schema[FLGivenClash].flatten(_.kind)
            val result =
                Result.catching[TransformFailedException](schema.encodeString[Json](FLGivenClash("a", FLGivenKind.Named("b"))))
            assert(
                result.failure.exists(_.getMessage.contains(
                    "flatten: the sum 'kind' wrote the key 'title', which the parent's field 'title' also writes"
                )),
                result.toString
            )
        }

        "a tag-only or tuple sum is refused at the first decode, since it writes no record" in {
            val tagOnly = firstDecodeFailure(Schema[FLPriorityHolder].flatten(_.priority))
            assert(
                tagOnly.exists(e =>
                    e.isInstanceOf[TransformFailedException] && e.getMessage.contains(
                        "flatten(_.priority): the sum is written as a bare name, not a record, so it has no keys to move to the parent level"
                    )
                ),
                tagOnly.toString
            )
            val tupled = firstDecodeFailure(Schema[FLTupleHolder].flatten(_.shape))
            assert(
                tupled.exists(e =>
                    e.isInstanceOf[TransformFailedException] && e.getMessage.contains(
                        "flatten(_.shape): the sum is written as an array, not a record, so it has no keys to move to the parent level"
                    )
                ),
                tupled.toString
            )
        }

        "two flattened sums in one record are rejected at the first decode" in {
            val result = firstDecodeFailure(Schema[FLTwoSums].flatten(_.first).flatten(_.second))
            assert(
                result.exists(e =>
                    e.isInstanceOf[TransformFailedException] && e.getMessage.contains(
                        "flatten: 'first' and 'second' are both sums; a record holds at most one flattened sum, since each reads the whole record"
                    )
                ),
                result.toString
            )
        }
    }

    "a variant whose own schema flattens an untagged sum" - {

        val image = FLMediaKind.Image(FLRef.ById("m1"), Present("c"))
        val link  = FLMediaKind.Image(FLRef.ByLink("https://x.test/a"))

        // Bson is left out: it refuses the untagged representation the flattened sum carries.
        def roundTripsEverywhere[A](value: A)(using Schema[A], Frame, kyo.test.AssertScope): Unit =
            roundTripString[Json, A](summon[Schema[A]], value)
            roundTripString[Yaml, A](summon[Schema[A]], value)
            roundTripString[Ion, A](summon[Schema[A]], value)
            roundTripBytes[MsgPack, A](summon[Schema[A]], value)
            roundTripBytes[IonBinary, A](summon[Schema[A]], value)
        end roundTripsEverywhere

        "decodes under a discriminator, with the tag beside the sum's keys" in {
            assert(Json.encode[FLMediaKind](image) == """{"type":"Image","id":"m1","caption":"c"}""")
            assertDecodes(Schema[FLMediaKind], """{"type":"Image","id":"m1","caption":"c"}""", image: FLMediaKind)
            assertDecodes(Schema[FLMediaKind], """{"link":"https://x.test/a","type":"Image"}""", link: FLMediaKind)
            roundTripsEverywhere[FLMediaKind](image)
            roundTripsEverywhere[FLMediaKind](FLMediaKind.Note("n"))
        }

        "decodes under an adjacent tag" in {
            val value: FLMediaAdjacent = FLMediaAdjacent.Image(FLRef.ById("m1"), Present("c"))
            assert(Json.encode(value) == """{"kind":"Image","data":{"id":"m1","caption":"c"}}""")
            assertDecodes(Schema[FLMediaAdjacent], """{"kind":"Image","data":{"id":"m1","caption":"c"}}""", value)
            roundTripsEverywhere(value)
        }

        "decodes under a wrapper object" in {
            val value: FLMediaWrapped = FLMediaWrapped.Image(FLRef.ByLink("https://x.test/a"), Absent)
            assert(Json.encode(value) == """{"Image":{"link":"https://x.test/a"}}""")
            assertDecodes(Schema[FLMediaWrapped], """{"Image":{"link":"https://x.test/a"}}""", value)
            roundTripsEverywhere(value)
        }

        "decodes under a tuple tag" in {
            val value: FLMediaTupled = FLMediaTupled.Image(FLRef.ById("m1"), Absent)
            assert(Json.encode(value) == """["Image",{"id":"m1"}]""")
            assertDecodes(Schema[FLMediaTupled], """["Image",{"id":"m1"}]""", value)
            roundTripsEverywhere(value)
        }
    }

    "flatten(_.field) on an optional record" - {

        val placeSchema = Schema[FLPlace].flatten(_.geo)
        val photoSchema = Schema[FLPhoto].flatten(_.caption)
        val there       = FLPlace("n", Present(FLGeo(1.5, 2.5)))
        val nowhere     = FLPlace("n", Absent)

        "Present writes the record's keys beside the parent's, and reads them back" in {
            val wire = placeSchema.encodeString[Json](there)
            assert(wire == """{"name":"n","lat":1.5,"lng":2.5}""", wire)
            assertDecodes(placeSchema, wire, there)
        }

        "Absent writes none of the record's keys, and an input with none of them reads as Absent" in {
            val wire = placeSchema.encodeString[Json](nowhere)
            assert(wire == """{"name":"n"}""", wire)
            assertDecodes(placeSchema, wire, nowhere)
        }

        "some of the record's keys without a required one is a decode failure at the missing key" in {
            assert(placeSchema.decodeString[Json]("""{"name":"n","lat":1.5}""") == Result.fail(MissingFieldException(Nil, "lng")))
            assert(placeSchema.decodeString[Json]("""{"name":"n","lng":2.5}""") == Result.fail(MissingFieldException(Nil, "lat")))
        }

        "a failure inside the record names its key at the parent level" in {
            placeSchema.decodeString[Json]("""{"name":"n","lat":"x","lng":2.5}""") match
                case Result.Failure(e: TypeMismatchException) => assert(e.path == List("lat"), e.getMessage)
                case other                                    => fail(s"expected TypeMismatchException at 'lat', got $other")
        }

        "a record key equal to the flattened field's own name: a caption and its entities beside a photo" in {
            val captioned = FLPhoto("p", Present(FLCaption("c", Chunk(1))))
            val wire      = photoSchema.encodeString[Json](captioned)
            assert(wire == """{"photo":"p","caption":"c","caption_entities":[1]}""", wire)
            assertDecodes(photoSchema, wire, captioned)
            assertDecodes(photoSchema, """{"photo":"p","caption":"c"}""", FLPhoto("p", Present(FLCaption("c", Chunk.empty))))
            assert(photoSchema.encodeString[Json](FLPhoto("p", Absent)) == """{"photo":"p"}""")
            assertDecodes(photoSchema, """{"photo":"p"}""", FLPhoto("p", Absent))
            assert(
                photoSchema.decodeString[Json]("""{"photo":"p","caption_entities":[1]}""") ==
                    Result.fail(MissingFieldException(Nil, "caption"))
            )
        }

        "round-trips Present and Absent on every self-describing codec" in {
            Chunk(there, nowhere).foreach { value =>
                roundTripString[Json, FLPlace](placeSchema, value)
                roundTripString[Yaml, FLPlace](placeSchema, value)
                roundTripString[Ion, FLPlace](placeSchema, value)
                roundTripBytes[MsgPack, FLPlace](placeSchema, value)
                roundTripBytes[Bson, FLPlace](placeSchema, value)
                roundTripBytes[IonBinary, FLPlace](placeSchema, value)
            }
        }

        "is refused by Protobuf on encode, as a flattened record is" in {
            val result = Result.catching[TransformUnsupportedException](placeSchema.encode[Protobuf](there))
            assert(result == Result.fail(TransformUnsupportedException("Protobuf", "flatten")), result.toString)
        }

        "an optional field that is not a record is a compile error naming why" in {
            typeCheckFailure("kyo.Schema[kyo.FLMaybeScalar].flatten(_.n)")("not a case class")
            typeCheckFailure("kyo.Schema[kyo.FLMaybeSum].flatten(_.kind)")("an optional sum")
        }

        "flatten without a field keeps an optional record nested" in {
            val wire = Schema[FLPlace].flatten.encodeString[Json](there)
            assert(wire == """{"name":"n","geo":{"lat":1.5,"lng":2.5}}""", wire)
        }
    }

    private def unordered(value: Structure.Value): Structure.Value =
        value match
            case Structure.Value.Record(fields)  => Structure.Value.Record(fields.map((k, v) => (k, unordered(v))).sortBy(_._1))
            case Structure.Value.Sequence(elems) => Structure.Value.Sequence(elems.map(unordered))
            case other                           => other

end SchemaFlattenTest

case class FLGeo(lat: Double, lng: Double) derives CanEqual, Schema
case class FLPlace(name: String, geo: Maybe[FLGeo] = Absent) derives CanEqual, Schema
case class FLCaption(@kyo.schema.rename("caption") text: String, @kyo.schema.rename("caption_entities") entities: Chunk[Int] = Chunk.empty)
    derives CanEqual, Schema
case class FLPhoto(photo: String, caption: Maybe[FLCaption] = Absent) derives CanEqual, Schema
case class FLMaybeScalar(n: Maybe[Int] = Absent) derives CanEqual, Schema
case class FLMaybeSum(kind: Maybe[FLKind] = Absent) derives CanEqual, Schema

case class FLBody(body: String) derives CanEqual, Schema

case class FLCount(n: Int) derives CanEqual, Schema
case class FLCountHolder(name: String, count: FLCount) derives CanEqual, Schema

case class FLBlob(data: Span[Byte]) derives Schema
case class FLBlobHolder(name: String, blob: FLBlob) derives Schema

@kyo.schema.discriminator("type")
sealed trait FLKind derives CanEqual, Schema
object FLKind:
    @kyo.schema.rename("text")
    final case class Text(text: FLBody) extends FLKind derives CanEqual
    @kyo.schema.rename("text_link")
    final case class TextLink(url: String) extends FLKind derives CanEqual
    @kyo.schema.rename("bold")
    case object Bold extends FLKind
    @kyo.schema.catchAll()
    final case class Other(name: String, raw: Structure.Value) extends FLKind derives CanEqual
end FLKind
case class FLEntity(offset: Int, length: Int, kind: FLKind) derives CanEqual, Schema
case class FLSend(to: String, context: Maybe[String] = Absent, message: FLKind) derives CanEqual, Schema
case class FLClash(url: String, kind: FLKind) derives CanEqual, Schema
case class FLTypedEntity(`type`: String, kind: FLKind) derives CanEqual, Schema

@kyo.schema.untagged
sealed trait FLContent derives CanEqual, Schema
object FLContent:
    final case class Text(text: String)                                    extends FLContent derives CanEqual
    final case class Photo(photo: String, caption: Maybe[String] = Absent) extends FLContent derives CanEqual
    @kyo.schema.catchAll()
    final case class Unknown(raw: Structure.Value) extends FLContent derives CanEqual
end FLContent
case class FLMessage(id: Int, date: Long, content: FLContent) derives CanEqual, Schema

@kyo.schema.untagged
sealed trait FLRef derives CanEqual, Schema
object FLRef:
    final case class ById(id: String)     extends FLRef derives CanEqual
    final case class ByLink(link: String) extends FLRef derives CanEqual
end FLRef
case class FLMediaObject(ref: FLRef, caption: Maybe[String] = Absent) derives CanEqual, Schema

@kyo.schema.discriminator("type")
sealed trait FLMediaKind derives CanEqual
object FLMediaKind:
    final case class Image(ref: FLRef, caption: Maybe[String] = Absent) extends FLMediaKind derives CanEqual
    object Image:
        given Schema[Image] = Schema[Image].flatten(_.ref)
    final case class Note(text: String) extends FLMediaKind derives CanEqual, Schema
    given Schema[FLMediaKind] = Schema.derived[FLMediaKind]
end FLMediaKind

@kyo.schema.adjacent("kind", "data")
sealed trait FLMediaAdjacent derives CanEqual
object FLMediaAdjacent:
    final case class Image(ref: FLRef, caption: Maybe[String] = Absent) extends FLMediaAdjacent derives CanEqual
    object Image:
        given Schema[Image] = Schema[Image].flatten(_.ref)
    given Schema[FLMediaAdjacent] = Schema.derived[FLMediaAdjacent]
end FLMediaAdjacent

sealed trait FLMediaWrapped derives CanEqual
object FLMediaWrapped:
    final case class Image(ref: FLRef, caption: Maybe[String] = Absent) extends FLMediaWrapped derives CanEqual
    object Image:
        given Schema[Image] = Schema[Image].flatten(_.ref)
    given Schema[FLMediaWrapped] = Schema.derived[FLMediaWrapped]
end FLMediaWrapped

sealed trait FLMediaTupled derives CanEqual
object FLMediaTupled:
    final case class Image(ref: FLRef, caption: Maybe[String] = Absent) extends FLMediaTupled derives CanEqual
    object Image:
        given Schema[Image] = Schema[Image].flatten(_.ref)
    given Schema[FLMediaTupled] = Schema.derived[FLMediaTupled].tupleTagged
end FLMediaTupled

@kyo.schema.discriminator("type")
sealed trait FLRenamedKind derives CanEqual, Schema
object FLRenamedKind:
    final case class Named(@kyo.schema.rename("title") name: String) extends FLRenamedKind derives CanEqual
case class FLRenamedClash(title: String, kind: FLRenamedKind) derives CanEqual, Schema
@kyo.schema.discriminator("kind")
sealed trait FLGivenKind derives CanEqual, Schema
object FLGivenKind:
    final case class Named(name: String) extends FLGivenKind derives CanEqual
    object Named:
        given Schema[Named] = Schema[Named].rename("name", "title")
end FLGivenKind
case class FLGivenClash(title: String, kind: FLGivenKind) derives CanEqual, Schema

@kyo.schema.tagOnly()
sealed trait FLPriority derives CanEqual, Schema
object FLPriority:
    case object Low  extends FLPriority
    case object High extends FLPriority
case class FLPriorityHolder(label: String, priority: FLPriority) derives CanEqual, Schema

@kyo.schema.adjacent("kind", "data")
sealed trait FLAdjacent derives CanEqual, Schema
object FLAdjacent:
    final case class Circle(radius: Int) extends FLAdjacent derives CanEqual
    final case class Square(side: Int)   extends FLAdjacent derives CanEqual
case class FLAdjacentHolder(label: String, shape: FLAdjacent) derives CanEqual, Schema

sealed trait FLWrapper derives CanEqual, Schema
object FLWrapper:
    final case class Circle(radius: Int) extends FLWrapper derives CanEqual
    final case class Square(side: Int)   extends FLWrapper derives CanEqual
case class FLWrapperHolder(label: String, shape: FLWrapper) derives CanEqual, Schema

sealed trait FLTupled derives CanEqual
object FLTupled:
    final case class Circle(radius: Int) extends FLTupled derives CanEqual
    given Schema[FLTupled] = Schema.derived[FLTupled].tupleTagged
case class FLTupleHolder(label: String, shape: FLTupled) derives CanEqual

case class FLTwoSums(first: FLKind, second: FLContent) derives CanEqual, Schema

case class FLAddress(street: String, city: String) derives CanEqual, Schema
case class FLOrder(id: Int, billing: FLAddress, shipping: FLAddress) derives CanEqual, Schema
case class FLTag(name: String) derives CanEqual, Schema
case class FLLabelled(name: String, tag: FLTag) derives CanEqual, Schema
case class FLCodeChild(@kyo.schema.rename("id") code: String) derives CanEqual, Schema
case class FLCodeParent(id: Int, child: FLCodeChild) derives CanEqual, Schema
case class FLZipAddress(street: String, @kyo.schema.rename("zip_code") zip: String) derives CanEqual, Schema
case class FLZipPerson(name: String, address: FLZipAddress) derives CanEqual, Schema
case class FLExplicitAddress(streetName: String, @kyo.schema.rename("zipCode") zip: String) derives CanEqual, Schema
case class FLExplicitPerson(name: String, address: FLExplicitAddress) derives CanEqual, Schema

// A sub-trait with its own given is one variant of the root: its cases' fields sit under the given's content key, not beside the root's.
@kyo.schema.discriminator("kind")
sealed trait FLGroupedEvent derives CanEqual, Schema
sealed trait FLGroup extends FLGroupedEvent derives CanEqual
object FLGroup:
    given Schema[FLGroup] = Schema[FLGroup].adjacent("t", "c")
case class FLGroupCase(id: String) extends FLGroup derives CanEqual
case class FLGroupOther(x: Int)    extends FLGroupedEvent derives CanEqual
case class FLGroupedParent(id: String, event: FLGroupedEvent) derives CanEqual, Schema

@kyo.schema.discriminator("kind")
sealed trait FLRenamedAwayEvent derives CanEqual, Schema
case class FLRenamedAwayCase(@kyo.schema.rename("ref") id: String) extends FLRenamedAwayEvent derives CanEqual
case class FLRenamedAwayParent(id: String, event: FLRenamedAwayEvent) derives CanEqual, Schema

@kyo.schema.discriminator("kind")
sealed trait FLRenamedOntoEvent derives CanEqual, Schema
case class FLRenamedOntoCase(@kyo.schema.rename("id") ref: String) extends FLRenamedOntoEvent derives CanEqual
case class FLRenamedOntoParent(id: String, event: FLRenamedOntoEvent) derives CanEqual, Schema
case class FLGivenAddress(street: String, zip: String) derives CanEqual
object FLGivenAddress:
    given Schema[FLGivenAddress] = Schema[FLGivenAddress].rename("zip", "postal")
case class FLGivenPerson(name: String, address: FLGivenAddress) derives CanEqual, Schema
case class FLHolder(holderLabel: String, person: MTPersonAddr) derives CanEqual
object FLHolder:
    given Schema[MTPersonAddr] = Schema[MTPersonAddr].flatten
    given Schema[FLHolder]     = Schema.derived[FLHolder]
case class FLCamelAddress(streetName: String, zipCode: String) derives CanEqual, Schema
case class FLCamelPerson(fullName: String, homeAddress: FLCamelAddress) derives CanEqual, Schema
case class FLNoteChild(note: Maybe[String], code: String) derives CanEqual, Schema
case class FLNoteParent(id: Int, child: FLNoteChild) derives CanEqual, Schema
case class FLOptChild(note: Maybe[String]) derives CanEqual, Schema
case class FLOptParent(id: Int, extra: FLOptChild) derives CanEqual, Schema
case class FLTagsChild(@kyo.schema.omit(kyo.schema.omit.WhenEmpty) tags: List[String], code: String) derives CanEqual, Schema
case class FLTagsParent(id: Int, child: FLTagsChild) derives CanEqual, Schema
