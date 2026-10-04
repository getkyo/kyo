package kyo

class SchemaOmitTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    // =========================================================================
    // omit builders
    // =========================================================================

    "omit builders are total and produce Schema[Cart]" in {
        val schema         = Schema[Cart]
        val withNone       = schema.omitNone
        val withEmpty      = schema.omitEmptyCollections
        val withItemsEmpty = Schema[Cart].omit(_.items).whenEmpty
        val withNoteNone   = Schema[Cart].omit(_.note).whenNone
        assert(withNone.isInstanceOf[Schema[Cart]])
        assert(withEmpty.isInstanceOf[Schema[Cart]])
        assert(withItemsEmpty.isInstanceOf[Schema[Cart]])
        assert(withNoteNone.isInstanceOf[Schema[Cart]])
        assert(withItemsEmpty.omitPolicies == Chunk("items" -> Schema.OmitPolicy.WhenEmpty))
        assert(withNoteNone.omitPolicies == Chunk("note" -> Schema.OmitPolicy.WhenNone))
    }

    "unconfigured schema is byte-identical and self-describing codecs require missing collections" in {
        val schema  = Schema[Cart]
        val value   = Cart(Chunk("a", "b"), Maybe("hello"))
        val encoded = schema.encodeString[Json](value)
        assert(encoded == """{"items":["a","b"],"note":"hello"}""")
        assert(!schema.denyUnknownFieldsEnabled)
        assert(schema.fieldDefaults.isEmpty)
        assert(schema.fieldTransforms.isEmpty)

        val missingItems = schema.decodeString[Json]("""{"note":"hi"}""")
        assert(missingItems.failure.exists(_.isInstanceOf[MissingFieldException]), s"expected missing items failure, got: $missingItems")

        val missingTags = Schema[CartWithMap].decodeString[Json]("""{"name":"cart"}""")
        assert(missingTags.failure.exists(_.isInstanceOf[MissingFieldException]), s"expected missing tags failure, got: $missingTags")
    }

    "omit policy survives a subsequent copyWith-routed builder" in {
        val schema1 = Schema[Cart].omitEmptyCollections.discriminator("type")
        assert(schema1.omitEmptyCollectionsAll)

        val schema2 = Schema[Cart].omitEmptyCollections
        assert(schema2.omitEmptyCollectionsAll)

        val schema3 = schema2.omitNone
        assert(schema3.omitEmptyCollectionsAll)
        assert(schema3.omitNoneAll)
    }

    "configured empty/absent fields are absent from the encoded object" in {
        val schema = Schema[OmitAllPolicyCase].omitEmptyCollections.omitNone
        val value  = OmitAllPolicyCase(Chunk.empty, Map.empty, None, "x")
        val out    = schema.encodeString[Json](value)
        assert(!out.contains("\"items\""), s"items key must be absent: $out")
        assert(!out.contains("\"tags\""), s"tags key must be absent: $out")
        assert(!out.contains("\"note\""), s"note key must be absent: $out")
        assert(!out.contains("[]"), s"empty array must not appear: $out")
        assert(!out.contains("{}"), s"empty object must not appear: $out")
        assert(!out.contains("null"), s"null must not appear: $out")
        assert(out.contains("\"name\""), s"populated name key must be present: $out")
        assert(out.contains("\"x\""), s"name value must be present: $out")
    }

    "per-field policy shadows the schema-wide flag" in {
        val schema = Schema[OmitShadowCase].omitEmptyCollections.omit(_.b).whenNone
        val value  = OmitShadowCase(List.empty, List.empty)
        val out    = schema.encodeString[Json](value)
        assert(!out.contains("\"a\""), s"a must be omitted by schema-wide WhenEmpty: $out")
        assert(out.contains("\"b\""), s"b must be present (per-field WhenNone shadows WhenEmpty): $out")
        assert(out.contains("[]"), s"b must appear as an empty array: $out")
    }

    "when omits a field when the predicate returns true" in {
        val schema = Schema[PredicateOmitCase].omit(_.count).when {
            case Structure.Value.Integer(n) => n == 0
            case _                          => false
        }
        val omitted  = schema.encodeString[Json](PredicateOmitCase("x", 0))
        val retained = schema.encodeString[Json](PredicateOmitCase("x", 5))
        assert(!omitted.contains("\"count\""), s"count must be absent when predicate true: $omitted")
        assert(retained.contains("\"count\""), s"count must be present when predicate false: $retained")
        assert(retained.contains("5"), s"count value must appear: $retained")
    }

    "false predicate preserves the field on encode" in {
        val schema = Schema[PredicateOmitCase].omit(_.count).when(_ => false)
        val out    = schema.encodeString[Json](PredicateOmitCase("x", 0))
        assert(out.contains("\"count\""), s"count must be present when predicate always false: $out")
        assert(out.contains("0"), s"zero value must appear: $out")
    }

    "whenDefault omits a field whose value equals the compile-time default" in {
        val schema   = Schema[PredicateOmitCase].omit(_.count).whenDefault
        val omitted  = schema.encodeString[Json](PredicateOmitCase("x", 0))
        val retained = schema.encodeString[Json](PredicateOmitCase("x", 42))
        assert(!omitted.contains("\"count\""), s"count must be absent when equal to default 0: $omitted")
        assert(retained.contains("\"count\""), s"count must be present when not equal to default: $retained")
        assert(retained.contains("42"), s"non-default value must appear: $retained")
    }

    "whenDefault does not omit a field with no compile-time default" in {
        val schema = Schema[PredicateOmitCase].omit(_.label).whenDefault
        val out    = schema.encodeString[Json](PredicateOmitCase("x", 1))
        assert(out.contains("\"label\""), s"label must be present (no compile-time default): $out")
        assert(out.contains("\"x\""), s"label value must appear: $out")
    }

    "whenDefault omits a product field equal to its compile-time default via field-schema materialization" in {
        val schema   = Schema[WhenDefaultOuter].omit(_.inner).whenDefault
        val omitted  = schema.encodeString[Json](WhenDefaultOuter("x", WhenDefaultInner(1, 2)))
        val retained = schema.encodeString[Json](WhenDefaultOuter("x", WhenDefaultInner(1, 99)))
        assert(!omitted.contains("\"inner\""), s"inner must be absent when equal to default Inner(1,2): $omitted")
        assert(retained.contains("\"inner\""), s"inner must be present when not equal to default: $retained")
        assert(retained.contains("99"), s"non-default value must appear: $retained")
    }

    "per-field when replaces an earlier whenDefault for the same field" in {
        val schema = Schema[PredicateOmitCase]
            .omit(_.count).whenDefault
            .omit(_.count).when(_ => true)
        val out = schema.encodeString[Json](PredicateOmitCase("x", 42))
        assert(!out.contains("\"count\""), s"later when(true) must replace whenDefault: $out")
        assert(schema.omitPolicies.count(_._1 == "count") == 1, "only one policy for count after replacement")
    }

    "field order is declaration order minus omitted fields" in {
        val schema = Schema[PredicateOmitCase].omit(_.count).when {
            case Structure.Value.Integer(n) => n == 0
            case _                          => false
        }
        val out = schema.encodeString[Json](PredicateOmitCase("hello", 0))
        assert(!out.contains("\"count\""), s"omitted field must be absent: $out")
        assert(out.contains("\"label\""), s"remaining field must be present: $out")
        val labelIdx = out.indexOf("\"label\"")
        assert(labelIdx >= 0, s"label not found: $out")
    }

    "when and WhenDefault decode stubs return false (decode unchanged)" in {
        val whenSchema        = Schema[PredicateOmitCase].omit(_.count).when(_ => true)
        val whenDefaultSchema = Schema[PredicateOmitCase].omit(_.count).whenDefault
        val json              = """{"label":"x","count":7}"""
        assert(whenSchema.decodeString[Json](json) == Result.Success(PredicateOmitCase("x", 7)))
        assert(whenDefaultSchema.decodeString[Json](json) == Result.Success(PredicateOmitCase("x", 7)))
    }

    "omit round-trips a populated collection through the standard derived Schema, no custom codec" in {
        val schema = Schema[Cart].omitEmptyCollections
        val value  = Cart(Chunk("a", "b"), Maybe("hello"))
        val out    = schema.encodeString[Json](value)
        val back   = schema.decodeString[Json](out)
        assert(back == Result.succeed(value), s"round-trip failed: $back (encoded: $out)")
        assert(out.contains("\"items\""), s"populated items must appear: $out")
    }

    // =========================================================================
    // omit type-awareness: product fields must never be omitted
    // =========================================================================

    "empty Map field is omitted under omitEmptyCollections but a sibling empty product field is NOT omitted" in {
        // At one schema level: tags (empty Map) is omitted; nested (empty product) is retained.
        // Omit is a property of the schema it is configured on; it does not propagate into the
        // nested product's own derived schema, so nested still renders with its inner empty map.
        val schema = Schema[MapAndProductSibling].omitEmptyCollections
        val value  = MapAndProductSibling(Map.empty, EmptyMapProduct(Map.empty))
        val out    = schema.encodeString[Json](value)
        assert(out == "{\"nested\":{\"theMap\":{}}}", s"expected only nested product retained, empty Map omitted: $out")
        assert(!out.contains("\"tags\""), s"empty Map field must be omitted: $out")
        assert(out.contains("\"nested\""), s"empty product field must be present: $out")
    }

    "nested product whose own collection fields render empty is NOT itself dropped from the parent" in {
        val schema = Schema[OuterWithNestedProduct].omitEmptyCollections
        val value  = OuterWithNestedProduct(InnerWithEmptyCollection(Chunk.empty), "present")
        val out    = schema.encodeString[Json](value)
        assert(out == "{\"inner\":{\"items\":[]},\"label\":\"present\"}", s"inner product must be retained: $out")
        assert(out.contains("\"inner\""), s"inner product field must be present even though it renders as empty object: $out")
        assert(out.contains("\"label\""), s"label field must be present: $out")
    }

    "nested product with empty collection round-trips correctly under omitEmptyCollections" in {
        val schema = Schema[OuterWithNestedProduct].omitEmptyCollections
        val value  = OuterWithNestedProduct(InnerWithEmptyCollection(Chunk.empty), "present")
        val out    = schema.encodeString[Json](value)
        val back   = schema.decodeString[Json](out)
        assert(back == Result.succeed(value), s"round-trip failed: $back (encoded: $out)")
    }

    "empty Map field round-trips correctly under omitEmptyCollections (positive regression guard)" in {
        val schema = Schema[CartWithMap].omitEmptyCollections
        val value  = CartWithMap(Map.empty, "x")
        val out    = schema.encodeString[Json](value)
        assert(!out.contains("\"tags\""), s"empty Map must be omitted: $out")
        val back = schema.decodeString[Json](out)
        assert(back == Result.succeed(value), s"round-trip failed: $back (encoded: $out)")
    }

    "empty List field round-trips correctly under omitEmptyCollections (positive regression guard)" in {
        val schema = Schema[CartWithList].omitEmptyCollections
        val value  = CartWithList(List.empty, "x")
        val out    = schema.encodeString[Json](value)
        assert(!out.contains("\"items\""), s"empty List must be omitted: $out")
        val back = schema.decodeString[Json](out)
        assert(back == Result.succeed(value), s"round-trip failed: $back (encoded: $out)")
    }

    // =========================================================================
    // omitEmptyCollections / .omit(_.f).whenEmpty: OrderedDict and Dict fields.
    // Both are opaque types whose Tag erases identically regardless of key/value
    // type, unlike Map, so the omit gate and the decode-side synthetic zero must
    // discriminate them by their declared structure instead of by Tag.
    // Covers both wire shapes: a String key (object wire form) and a non-String
    // key (array-of-{key,value} wire form).
    // =========================================================================

    // OrderedDict and Dict provide no CanEqual instance for `==` (their opaque, dual-representation
    // shape has no meaningful universal equals; see OrderedDict.is / Dict.is), so a round-trip is
    // asserted field-by-field: the scalars via `==`, the map field via `.is`, the established
    // idiom for comparing these two types (matching OrderedDictTest.scala / DictTest.scala).

    "empty OrderedDict[String, V] field is omitted under omitEmptyCollections and round-trips" in {
        val schema = Schema[MTOrderedDictRecord].omitEmptyCollections
        val value  = MTOrderedDictRecord("alice", OrderedDict.empty[String, Int], 7)
        val out    = schema.encodeString[Json](value)
        assert(out == """{"name":"alice","count":7}""", s"empty String-key OrderedDict must be omitted: $out")
        assert(!out.contains("Ljava.lang.Object"), s"must never encode the backing array's identity hash: $out")
        val back = schema.decodeString[Json](out).getOrThrow
        assert(back.name == value.name && back.count == value.count, s"round-trip failed: $back (encoded: $out)")
        assert(back.settings.is(value.settings), s"round-trip failed: $back (encoded: $out)")
    }

    "empty OrderedDict[Int, V] field (non-String key) is omitted under omitEmptyCollections and round-trips" in {
        val schema = Schema[MTOrderedDictLevelsRecord].omitEmptyCollections
        val value  = MTOrderedDictLevelsRecord("alice", OrderedDict.empty[Int, String], 7)
        val out    = schema.encodeString[Json](value)
        assert(out == """{"name":"alice","count":7}""", s"empty non-String-key OrderedDict must be omitted: $out")
        assert(!out.contains("Ljava.lang.Object"), s"must never encode the backing array's identity hash: $out")
        val back = schema.decodeString[Json](out).getOrThrow
        assert(back.name == value.name && back.count == value.count, s"round-trip failed: $back (encoded: $out)")
        assert(back.byLevel.is(value.byLevel), s"round-trip failed: $back (encoded: $out)")
    }

    "empty Dict[String, V] field is omitted under omitEmptyCollections and round-trips" in {
        val schema = Schema[MTStringDictRecord].omitEmptyCollections
        val value  = MTStringDictRecord("alice", Dict.empty[String, Int], 7)
        val out    = schema.encodeString[Json](value)
        assert(out == """{"name":"alice","count":7}""", s"empty String-key Dict must be omitted: $out")
        assert(!out.contains("Ljava.lang.Object"), s"must never encode the backing array's identity hash: $out")
        val back = schema.decodeString[Json](out).getOrThrow
        assert(back.name == value.name && back.count == value.count, s"round-trip failed: $back (encoded: $out)")
        assert(back.tags.is(value.tags), s"round-trip failed: $back (encoded: $out)")
    }

    "empty Dict[Int, V] field (non-String key) is omitted under omitEmptyCollections and round-trips" in {
        val schema = Schema[MTIntStringDictRecord].omitEmptyCollections
        val value  = MTIntStringDictRecord("alice", Dict.empty[Int, String], 7)
        val out    = schema.encodeString[Json](value)
        assert(out == """{"name":"alice","count":7}""", s"empty non-String-key Dict must be omitted: $out")
        assert(!out.contains("Ljava.lang.Object"), s"must never encode the backing array's identity hash: $out")
        val back = schema.decodeString[Json](out).getOrThrow
        assert(back.name == value.name && back.count == value.count, s"round-trip failed: $back (encoded: $out)")
        assert(back.byId.is(value.byId), s"round-trip failed: $back (encoded: $out)")
    }

    "empty Map[String, V] field is omitted under omitEmptyCollections and round-trips" in {
        val schema = Schema[MTStringMapRecord].omitEmptyCollections
        val value  = MTStringMapRecord("alice", Map.empty[String, Int], 7)
        val out    = schema.encodeString[Json](value)
        assert(out == """{"name":"alice","count":7}""", s"empty String-key Map must be omitted: $out")
        val back = schema.decodeString[Json](out)
        assert(back == Result.succeed(value), s"round-trip failed: $back (encoded: $out)")
    }

    "empty Map[Int, V] field (non-String key) is omitted under omitEmptyCollections and round-trips" in {
        val schema = Schema[MTIntMapRecord].omitEmptyCollections
        val value  = MTIntMapRecord("alice", Map.empty[Int, String], 7)
        val out    = schema.encodeString[Json](value)
        assert(out == """{"name":"alice","count":7}""", s"empty non-String-key Map must be omitted: $out")
        val back = schema.decodeString[Json](out)
        assert(back == Result.succeed(value), s"round-trip failed: $back (encoded: $out)")
    }

    // The wire form of a mapping field belongs to the bound given, not to the declared key type, so an
    // injected empty value chosen from the key type is a guess. These leaves bind the array form for a
    // String key, the binding no key-type guess can serve (getkyo/kyo#1748).

    "empty String-key Dict bound to the array-form given round-trips under omitEmptyCollections" in {
        given arrayForm: Schema[Dict[String, Int]] = Schema.dictAsPairs[String, Int]
        val schema                                 = Schema.derived[MTStringDictRecord].omitEmptyCollections
        val value                                  = MTStringDictRecord("alice", Dict.empty[String, Int], 7)
        val out                                    = schema.encodeString[Json](value)
        assert(out == """{"name":"alice","count":7}""", s"empty field must still be omitted on encode: $out")
        val back = schema.decodeString[Json](out).getOrThrow
        assert(back.name == value.name && back.count == value.count, s"round-trip failed: $back (encoded: $out)")
        assert(back.tags.is(value.tags), s"round-trip failed: $back (encoded: $out)")
    }

    // A mapping's framing is part of what the bound given encodes, so a transform, which materializes
    // the value into a Structure.Value tree and replays it, has to replay the framing it was given. It
    // cannot re-derive it from the entries: a map node carries no framing.

    "a non-empty String-key Dict bound to the array-form given keeps the array wire form under a transform" in {
        given arrayForm: Schema[Dict[String, Int]] = Schema.dictAsPairs[String, Int]
        val schema                                 = Schema.derived[MTStringDictRecord].omitEmptyCollections
        val value                                  = MTStringDictRecord("alice", Dict("x" -> 1), 7)
        val out                                    = schema.encodeString[Json](value)
        assert(
            out == """{"name":"alice","tags":[{"key":"x","value":1}],"count":7}""",
            s"the bound given's array wire form must be what is written: $out"
        )
        val back = schema.decodeString[Json](out).getOrThrow
        assert(back.name == value.name && back.count == value.count, s"round-trip failed: $back (encoded: $out)")
        assert(back.tags.is(value.tags), s"round-trip failed: $back (encoded: $out)")
    }

    // Not an omit-policy defect: any transform replays the tree, so a rename reaches the same path.
    "a rename keeps a String-key Dict's array wire form" in {
        given arrayForm: Schema[Dict[String, Int]] = Schema.dictAsPairs[String, Int]
        val schema                                 = Schema[MTStringDictRecord].rename(_.name, "who")
        val value                                  = MTStringDictRecord("alice", Dict("x" -> 1), 7)
        val out                                    = schema.encodeString[Json](value)
        assert(
            out == """{"who":"alice","tags":[{"key":"x","value":1}],"count":7}""",
            s"the bound given's array wire form must survive a rename: $out"
        )
        val back = schema.decodeString[Json](out).getOrThrow
        assert(back.name == value.name && back.count == value.count, s"round-trip failed: $back (encoded: $out)")
        assert(back.tags.is(value.tags), s"round-trip failed: $back (encoded: $out)")
    }

    "the default String-key Dict given keeps the object wire form under a transform" in {
        val schema = Schema[MTStringDictRecord].rename(_.name, "who")
        val value  = MTStringDictRecord("alice", Dict("x" -> 1), 7)
        val out    = schema.encodeString[Json](value)
        assert(out == """{"who":"alice","tags":{"x":1},"count":7}""", s"the object form is the default given's form: $out")
        val back = schema.decodeString[Json](out).getOrThrow
        assert(back.tags.is(value.tags), s"round-trip failed: $back (encoded: $out)")
    }

    "a non-empty String-key Map bound to the array-form given keeps the array wire form under a transform" in {
        given arrayForm: Schema[Map[String, Int]] = Schema.mapAsPairs[String, Int]
        val schema                                = Schema.derived[MTStringMapRecord].omitEmptyCollections
        val value                                 = MTStringMapRecord("alice", Map("x" -> 1), 7)
        val out                                   = schema.encodeString[Json](value)
        assert(
            out == """{"name":"alice","tags":[{"key":"x","value":1}],"count":7}""",
            s"the bound given's array wire form must be what is written: $out"
        )
        val back = schema.decodeString[Json](out)
        assert(back == Result.succeed(value), s"round-trip failed: $back (encoded: $out)")
    }

    "empty String-key OrderedDict bound to the array-form given round-trips under omitEmptyCollections" in {
        given arrayForm: Schema[OrderedDict[String, Int]] = Schema.orderedDictAsPairs[String, Int]
        val schema                                        = Schema.derived[MTOrderedDictRecord].omitEmptyCollections
        val value                                         = MTOrderedDictRecord("alice", OrderedDict.empty[String, Int], 7)
        val out                                           = schema.encodeString[Json](value)
        assert(out == """{"name":"alice","count":7}""", s"empty field must still be omitted on encode: $out")
        val back = schema.decodeString[Json](out).getOrThrow
        assert(back.name == value.name && back.count == value.count, s"round-trip failed: $back (encoded: $out)")
        assert(back.settings.is(value.settings), s"round-trip failed: $back (encoded: $out)")
    }

    "empty String-key Map bound to the array-form given round-trips under omitEmptyCollections" in {
        given arrayForm: Schema[Map[String, Int]] = Schema.mapAsPairs[String, Int]
        val schema                                = Schema.derived[MTStringMapRecord].omitEmptyCollections
        val value                                 = MTStringMapRecord("alice", Map.empty[String, Int], 7)
        val out                                   = schema.encodeString[Json](value)
        assert(out == """{"name":"alice","count":7}""", s"empty field must still be omitted on encode: $out")
        val back = schema.decodeString[Json](out)
        assert(back == Result.succeed(value), s"round-trip failed: $back (encoded: $out)")
    }

    "per-field .omit(_.f).whenEmpty round-trips a String-key Dict bound to the array-form given" in {
        given arrayForm: Schema[Dict[String, Int]] = Schema.dictAsPairs[String, Int]
        val schema                                 = Schema[MTStringDictRecord].omit(_.tags).whenEmpty
        val value                                  = MTStringDictRecord("alice", Dict.empty[String, Int], 7)
        val out                                    = schema.encodeString[Json](value)
        assert(out == """{"name":"alice","count":7}""", s"per-field WhenEmpty must omit the empty field: $out")
        val back = schema.decodeString[Json](out).getOrThrow
        assert(back.name == value.name && back.count == value.count, s"round-trip failed: $back (encoded: $out)")
        assert(back.tags.is(value.tags), s"round-trip failed: $back (encoded: $out)")
    }

    "non-empty OrderedDict field is NOT omitted under omitEmptyCollections and keeps insertion order" in {
        val schema = Schema[MTOrderedDictRecord].omitEmptyCollections
        val value  = MTOrderedDictRecord("alice", OrderedDict("z" -> 1, "a" -> 2), 7)
        val out    = schema.encodeString[Json](value)
        assert(
            out == """{"name":"alice","settings":{"z":1,"a":2},"count":7}""",
            s"non-empty OrderedDict must be retained in insertion order: $out"
        )
        val back = schema.decodeString[Json](out).getOrThrow
        assert(back.name == value.name && back.count == value.count, s"round-trip failed: $back (encoded: $out)")
        assert(back.settings.toChunk == value.settings.toChunk, s"round-trip failed: $back (encoded: $out)")
    }

    "non-empty Dict field is NOT omitted under omitEmptyCollections" in {
        val schema = Schema[MTStringDictRecord].omitEmptyCollections
        val value  = MTStringDictRecord("alice", Dict("x" -> 1), 7)
        val out    = schema.encodeString[Json](value)
        assert(out == """{"name":"alice","tags":{"x":1},"count":7}""", s"non-empty Dict must be retained: $out")
        val back = schema.decodeString[Json](out).getOrThrow
        assert(back.name == value.name && back.count == value.count, s"round-trip failed: $back (encoded: $out)")
        assert(back.tags.is(value.tags), s"round-trip failed: $back (encoded: $out)")
    }

    "per-field .omit(_.f).whenEmpty omits an empty OrderedDict[String, V] field and round-trips" in {
        val schema = Schema[MTOrderedDictRecord].omit(_.settings).whenEmpty
        val value  = MTOrderedDictRecord("alice", OrderedDict.empty[String, Int], 7)
        val out    = schema.encodeString[Json](value)
        assert(out == """{"name":"alice","count":7}""", s"per-field WhenEmpty must omit an empty OrderedDict: $out")
        val back = schema.decodeString[Json](out).getOrThrow
        assert(back.name == value.name && back.count == value.count, s"round-trip failed: $back (encoded: $out)")
        assert(back.settings.is(value.settings), s"round-trip failed: $back (encoded: $out)")
    }

    "per-field .omit(_.f).whenEmpty omits an empty Dict[Int, V] field (non-String key) and round-trips" in {
        val schema = Schema[MTIntStringDictRecord].omit(_.byId).whenEmpty
        val value  = MTIntStringDictRecord("alice", Dict.empty[Int, String], 7)
        val out    = schema.encodeString[Json](value)
        assert(out == """{"name":"alice","count":7}""", s"per-field WhenEmpty must omit an empty non-String-key Dict: $out")
        val back = schema.decodeString[Json](out).getOrThrow
        assert(back.name == value.name && back.count == value.count, s"round-trip failed: $back (encoded: $out)")
        assert(back.byId.is(value.byId), s"round-trip failed: $back (encoded: $out)")
    }

    "omitNone schema-wide: absent Maybe field is omitted on encode and decodes back to Maybe.empty" in {
        // Cart has: items: Chunk[String], note: Maybe[String]
        val schema = Schema[Cart].omitNone
        val value  = Cart(Chunk("a"), Maybe.empty)
        val out    = schema.encodeString[Json](value)
        assert(!out.contains("\"note\""), s"note key must be absent when omitNone and Maybe.empty: $out")
        assert(!out.contains("null"), s"null must not appear under omitNone: $out")
        val back = schema.decodeString[Json](out)
        assert(back == Result.succeed(value), s"round-trip must restore Maybe.empty: $back (encoded: $out)")
    }

    "omitNone schema-wide: absent Option field is omitted on encode and decodes back to None" in {
        // OmitAllPolicyCase has: items, tags, note: Option[String], name
        val schema = Schema[OmitAllPolicyCase].omitNone
        val value  = OmitAllPolicyCase(Chunk("x"), Map("k" -> 1), None, "y")
        val out    = schema.encodeString[Json](value)
        assert(!out.contains("\"note\""), s"note key must be absent when omitNone and None: $out")
        assert(!out.contains("null"), s"null must not appear under omitNone: $out")
        val back = schema.decodeString[Json](out)
        assert(back == Result.succeed(value), s"round-trip must restore None: $back (encoded: $out)")
    }

    "omit per-field whenNone: absent Maybe field is omitted on encode and decodes back to Maybe.empty" in {
        val schema = Schema[Cart].omit(_.note).whenNone
        val value  = Cart(Chunk("a"), Maybe.empty)
        val out    = schema.encodeString[Json](value)
        assert(!out.contains("\"note\""), s"note key must be absent with per-field whenNone and Maybe.empty: $out")
        val back = schema.decodeString[Json](out)
        assert(back == Result.succeed(value), s"per-field whenNone round-trip must restore Maybe.empty: $back (encoded: $out)")
    }

    "omit per-field whenNone: absent Option field is omitted on encode and decodes back to None" in {
        val schema = Schema[OmitAllPolicyCase].omit(_.note).whenNone
        val value  = OmitAllPolicyCase(Chunk("x"), Map("k" -> 1), None, "y")
        val out    = schema.encodeString[Json](value)
        assert(!out.contains("\"note\""), s"note key must be absent with per-field whenNone and None: $out")
        val back = schema.decodeString[Json](out)
        assert(back == Result.succeed(value), s"per-field whenNone round-trip must restore None: $back (encoded: $out)")
    }

end SchemaOmitTest
