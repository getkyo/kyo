package kyo

class SchemaUnionRepresentationNamingTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    // =========================================================================
    // Group: naming composition
    // =========================================================================

    "adjacent with snake-case naming emits snake-cased tag" in {
        val schema           = Schema[SSRShape].adjacent("kind", "data").renameAllVariants(Schema.NameCase.SnakeCase)
        val circle: SSRShape = SSRCircle(10.0)
        val wire             = schema.encodeString[Json](circle)
        // SSRCircle -> tokens [SSR, Circle] -> ssr_circle
        assert(wire == """{"kind":"ssr_circle","data":{"radius":10.0}}""")
    }

    "tuple tag resolves through naming layer on encode and decode" in {
        val schema           = Schema[SSRShape].tupleTagged.renameAllVariants(Schema.NameCase.SnakeCase)
        val circle: SSRShape = SSRCircle(10.0)
        val wire             = schema.encodeString[Json](circle)
        // encode emits the snake-cased tag as element 0
        assert(wire == """["ssr_circle",{"radius":10.0}]""")
        // decode accepts the snake-cased tag as element 0
        val decoded = schema.decodeString[Json](wire)
        assert(decoded == Result.succeed(SSRCircle(10.0)))
    }

    "untagged skips tag naming but keeps field naming" in {
        // renameAllVariants on the sum suppresses the tag under Untagged but does NOT rename payload
        // fields: field naming on a sum schema does not propagate into each variant's own product schema.
        // SSRUItem.itemCount is a multi-word camelCase field; under SnakeCase it would become item_count.
        // Asserting the wire still carries itemCount (not item_count) confirms the payload field names are
        // passed through exactly as the variant schema produces them.
        val schema          = Schema[SSRUNamed].untagged.renameAllVariants(Schema.NameCase.SnakeCase)
        val item: SSRUNamed = SSRUItem(42)
        val wire            = schema.encodeString[Json](item)
        // Bare payload: the variant's own schema emits itemCount unchanged; no sum-level field rename fires.
        assert(wire == """{"itemCount":42}""")
        // No variant-name token appears: Untagged suppresses the tag even with renameAllVariants configured.
        assert(!wire.contains("SSRUItem"))
        assert(!wire.contains("ssru_item"))
        // Round-trip: the untagged decoder reconstructs the original value from the bare payload.
        val decoded = schema.decodeString[Json](wire)
        assert(decoded == Result.succeed(SSRUItem(42)))
    }

    "sum-level renameAllFields does not rename variant payload field names (payload-bearing reps)" in {
        // A field convention configured on the SUM schema governs only the sum wrapper, never the
        // variant's own product fields. SSRUItem.itemCount is multi-word camelCase: under SnakeCase a leaked
        // sum-level rename would surface as item_count in the payload. Each payload-bearing representation
        // (External, Internal, Adjacent, Tuple) carries the payload as a named object, so a leak would be
        // wire-visible here. TupleFlat (positional) and Untagged (bare payload) are pinned separately.
        val item: SSRUNamed                         = SSRUItem(42)
        def wire(schema: Schema[SSRUNamed]): String =
            schema.renameAllFields(Schema.NameCase.SnakeCase).encodeString[Json](item)

        // External: wrapper key is the variant name; the payload object keeps itemCount.
        assert(wire(Schema[SSRUNamed]) == """{"SSRUItem":{"itemCount":42}}""")
        // Internal: discriminator key plus inlined payload; itemCount unchanged.
        assert(wire(Schema[SSRUNamed].discriminator("type")) == """{"type":"SSRUItem","itemCount":42}""")
        // Adjacent: tag key plus nested content object; itemCount unchanged.
        assert(wire(Schema[SSRUNamed].adjacent("t", "c")) == """{"t":"SSRUItem","c":{"itemCount":42}}""")
        // Tuple: [tag, payload-object]; itemCount unchanged.
        assert(wire(Schema[SSRUNamed].tupleTagged) == """["SSRUItem",{"itemCount":42}]""")
    }

    // =========================================================================
    // Group: alias acceptance set
    // =========================================================================

    "alias accepted on decode under Internal, Adjacent, and Tuple" in {
        val internalSchema = Schema[SSRAShape].discriminator("type").variantAlias("SSRACircle", "circ_v1")
        val adjacentSchema = Schema[SSRAShape].adjacent("t", "c").variantAlias("SSRACircle", "circ_v1")
        val tupleSchema    = Schema[SSRAShape].tupleTagged.variantAlias("SSRACircle", "circ_v1")

        val internalResult = internalSchema.decodeString[Json]("""{"type":"circ_v1","radius":10.0}""")
        assert(internalResult == Result.succeed(SSRACircle(10.0)))

        val adjacentResult = adjacentSchema.decodeString[Json]("""{"t":"circ_v1","c":{"radius":10.0}}""")
        assert(adjacentResult == Result.succeed(SSRACircle(10.0)))

        val tupleResult = tupleSchema.decodeString[Json]("""["circ_v1",{"radius":10.0}]""")
        assert(tupleResult == Result.succeed(SSRACircle(10.0)))
    }

    "a renamed variant's Scala name is not its tag under Internal, Adjacent, Tuple and TupleFlat" in {
        def rejects(result: Result[DecodeException, SSRAShape]): Unit =
            result match
                case Result.Failure(e: UnknownVariantException) => assert(e.variantName == "SSRACircle")
                case other                                      => fail(s"expected UnknownVariantException, got $other")
        rejects(Schema[SSRAShape].discriminator("type").variantNames("SSRACircle" -> "circle")
            .decodeString[Json]("""{"type":"SSRACircle","radius":10.0}"""))
        rejects(Schema[SSRAShape].adjacent("t", "c").variantNames("SSRACircle" -> "circle")
            .decodeString[Json]("""{"t":"SSRACircle","c":{"radius":10.0}}"""))
        rejects(Schema[SSRAShape].tupleTagged.variantNames("SSRACircle" -> "circle")
            .decodeString[Json]("""["SSRACircle",{"radius":10.0}]"""))
        rejects(Schema[SSRAShape].tupleFlat.variantNames("SSRACircle" -> "circle")
            .decodeString[Json]("""["SSRACircle",10.0]"""))
    }

    "alias accepted on decode under TupleFlat" in {
        val schema = Schema[SSRAShape].tupleFlat.variantAlias("SSRACircle", "circ_v1")
        val wire   = """["circ_v1",10.0]"""
        val result = schema.decodeString[Json](wire)
        assert(result == Result.succeed(SSRACircle(10.0)))
    }

    "External does not accept alias as wrapper key; untagged decode is unaffected by alias" in {
        // External: alias is NOT accepted as the wrapper object key
        val externalSchema = Schema[SSRAShape].variantAlias("SSRACircle", "circ_v1")
        val externalResult = externalSchema.decodeString[Json]("""{"circ_v1":{"radius":10.0}}""")
        assert(!externalResult.isSuccess)

        // The canonical name still works under External
        val canonicalResult = externalSchema.decodeString[Json]("""{"SSRACircle":{"radius":10.0}}""")
        assert(canonicalResult == Result.succeed(SSRACircle(10.0)))

        // Untagged: alias has no effect on which variant a bare payload decodes to
        val untaggedSchema = Schema[SSRAShape].untagged.variantAlias("SSRACircle", "circ_v1")
        val untaggedResult = untaggedSchema.decodeString[Json]("""{"radius":10.0}""")
        assert(untaggedResult == Result.succeed(SSRACircle(10.0)))
    }

    // =========================================================================
    // Group: tupleFlat naming/alias
    // =========================================================================

    "tupleFlat tag resolves through naming layer" in {
        val schema             = Schema[SSRShape].tupleFlat.renameAllVariants(Schema.NameCase.SnakeCase)
        val triangle: SSRShape = SSRTriangle(10.0, 10.0, 10.0)
        val wire               = schema.encodeString[Json](triangle)
        // SSRTriangle -> tokens [SSR, Triangle] -> ssr_triangle
        assert(wire == """["ssr_triangle",10.0,10.0,10.0]""")
    }

    "tupleFlat field rename leaves the positional wire unchanged" in {
        val schemaPlain        = Schema[SSRShape].tupleFlat
        val schemaWithRename   = Schema[SSRShape].tupleFlat.renameAllFields(Schema.NameCase.SnakeCase)
        val triangle: SSRShape = SSRTriangle(10.0, 10.0, 10.0)

        val plainWire   = schemaPlain.encodeString[Json](triangle)
        val renamedWire = schemaWithRename.encodeString[Json](triangle)
        // TupleFlat drops field names: a field-level rename is wire-invisible
        assert(plainWire == renamedWire)
        assert(renamedWire == """["SSRTriangle",10.0,10.0,10.0]""")
    }

    "union member naming via reused variantNames and variantAlias composes through the one variantNaming layer" in {
        // Use product-member union (SSRUCircle | SSRUSquare) so the adjacent content is an object.
        // Adjacent representation makes the tag observable in the wire output.
        val s = summon[Schema[SSRUCircle | SSRUSquare]]
            .adjacent("type", "content")
            .variantNames("SSRUCircle" -> "circle", "SSRUSquare" -> "square")
        val value: SSRUCircle | SSRUSquare = SSRUCircle(10.0)
        // Encode: the tag must be the renamed wire name "circle".
        val wire = s.encodeString[Json](value)
        assert(wire.contains("\"circle\""), s"Encoded wire must contain renamed tag 'circle'; got: $wire")
        assert(!wire.contains("\"SSRUCircle\""), s"Original name must not appear in wire; got: $wire")
        // Decode via the primary renamed tag.
        val decoded = s.decodeString[Json](wire)
        assert(decoded == Result.succeed(value), s"Decode via renamed tag must return SSRUCircle(10.0); got: $decoded")
        // variantAlias lets a secondary name decode to the same variant.
        val sWithAlias  = s.variantAlias("circle", "circ")
        val aliasWire   = wire.replace("\"circle\"", "\"circ\"")
        val aliasResult = sWithAlias.decodeString[Json](aliasWire)
        assert(aliasResult == Result.succeed(value), s"Alias 'circ' must decode to SSRUCircle(10.0); got: $aliasResult")
    }

    "variant naming reaches every representation that writes a variant's name" in {
        val circle: SSRAShape = SSRACircle(1.0)
        val adjacent          = Schema[SSRAShape].adjacent("t", "c").variantNames("SSRACircle" -> "circle")
        assert(adjacent.encodeString[Json](circle) == """{"t":"circle","c":{"radius":1.0}}""")
        assert(adjacent.decodeString[Json]("""{"t":"circle","c":{"radius":1.0}}""") == Result.succeed(circle))
        assert(Schema[SSRAShape].tupleTagged.variantNames("SSRACircle" -> "circle").encodeString[Json](circle) ==
            """["circle",{"radius":1.0}]""")
        assert(Schema[SSRAShape].tupleFlat.variantNames("SSRACircle" -> "circle").encodeString[Json](circle) == """["circle",1.0]""")
    }

    "the wrapper-object format keeps the Scala variant names" in {
        val circle: SSRAShape = SSRACircle(1.0)
        assert(Schema[SSRAShape].variantNames("SSRACircle" -> "circle").encodeString[Json](circle) == """{"SSRACircle":{"radius":1.0}}""")
        assert(Schema[SSRAShape].renameAllVariants(Schema.NameCase.SnakeCase).encodeString[Json](circle) ==
            """{"SSRACircle":{"radius":1.0}}""")
        val renamed: SSRARenamed = SSRARenamedCircle(1)
        assert(Json.encode(renamed) == """{"SSRARenamedCircle":{"r":1}}""")
    }

end SchemaUnionRepresentationNamingTest
