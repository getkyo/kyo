package kyo

import Json.JsonSchema

/** Covers `JsonSchema.toStructure`, the runtime inverse of `JsonSchema.fromStructure`.
  *
  * A schema that arrives at runtime (an MCP server's tool `inputSchema`, a hand-built descriptor) has no
  * Scala type behind it, so the only way to validate a value against it is to recover the structural type
  * `Structure.conform` reads. These tests pin both halves of that contract: the recovered type validates
  * the values the schema describes, and re-deriving a JsonSchema from it preserves the parts a model acts
  * on (property names, types, nesting, the required set, and property descriptions).
  */
class JsonSchemaStructureTest extends kyo.test.Test[Any]:

    private def conforms(value: Structure.Value, tpe: Structure.Type): Boolean =
        Structure.conform(value, tpe).isEmpty

    private def violation(value: Structure.Value, tpe: Structure.Type): String =
        Structure.conform(value, tpe).getOrElse("<conformed>")

    "primitives recover the derived structural type" - {
        "string" in {
            assert(JsonSchema.toStructure(JsonSchema.Str()).equals(summon[Schema[String]].structure))
        }
        "integer" in {
            assert(JsonSchema.toStructure(JsonSchema.Integer()).equals(summon[Schema[Long]].structure))
        }
        "number" in {
            assert(JsonSchema.toStructure(JsonSchema.Num()).equals(summon[Schema[Double]].structure))
        }
        "boolean" in {
            assert(JsonSchema.toStructure(JsonSchema.Bool()).equals(summon[Schema[Boolean]].structure))
        }
    }

    "an object recovers a product whose fields carry name, type, optionality and doc" in {
        val schema = JsonSchema.Obj(
            properties = List(
                "sql"   -> JsonSchema.Str(description = Present("the query to run")),
                "limit" -> JsonSchema.Integer()
            ),
            required = List("sql")
        )
        JsonSchema.toStructure(schema) match
            case p: Structure.Type.Product =>
                assert(p.fields.map(_.name) == Chunk("sql", "limit"))
                val sql = p.fields(0)
                val lim = p.fields(1)
                assert(sql.fieldType.equals(summon[Schema[String]].structure))
                assert(sql.doc == Present("the query to run"))
                assert(!sql.optional, "a required property must not be optional")
                assert(lim.fieldType.equals(summon[Schema[Long]].structure))
                assert(lim.optional, "a property absent from `required` must be optional")
            case other => fail(s"expected a Product, got: $other")
        end match
    }

    "conformance against a recovered object type" - {
        val schema = JsonSchema.Obj(
            properties = List(
                "sql"   -> JsonSchema.Str(),
                "limit" -> JsonSchema.Integer()
            ),
            required = List("sql")
        )
        val tpe = JsonSchema.toStructure(schema)

        "accepts a record carrying the required field" in {
            assert(conforms(Structure.Value.Record(Chunk("sql" -> Structure.Value.Str("select 1"))), tpe))
        }
        "accepts a record carrying every field" in {
            val value = Structure.Value.Record(Chunk(
                "sql"   -> Structure.Value.Str("select 1"),
                "limit" -> Structure.Value.Integer(10)
            ))
            assert(conforms(value, tpe))
        }
        "rejects a record missing the required field" in {
            val value = Structure.Value.Record(Chunk("limit" -> Structure.Value.Integer(10)))
            assert(violation(value, tpe).contains("missing required field 'sql'"))
        }
        "rejects a required field of the wrong kind" in {
            val value = Structure.Value.Record(Chunk("sql" -> Structure.Value.Bool(true)))
            assert(violation(value, tpe).contains("sql"))
        }
        "rejects a non-object" in {
            assert(violation(Structure.Value.Str("select 1"), tpe).contains("expected an object"))
        }
    }

    "nested objects keep distinct product names so re-derivation does not read them as a cycle" in {
        // `fromStructure` guards recursion by product NAME: two distinct nested objects sharing a name
        // would make the second re-derive as an empty `{}`, silently erasing its properties.
        val schema = JsonSchema.Obj(
            properties = List(
                "origin"      -> JsonSchema.Obj(List("city" -> JsonSchema.Str()), List("city")),
                "destination" -> JsonSchema.Obj(List("code" -> JsonSchema.Str()), List("code"))
            ),
            required = List("origin", "destination")
        )
        val roundTripped = JsonSchema.fromStructure(JsonSchema.toStructure(schema))
        roundTripped match
            case JsonSchema.Obj(properties, required, _, _, _, _) =>
                assert(required.toSet == Set("origin", "destination"))
                val byName = properties.toMap
                assert(byName("origin") == JsonSchema.Obj(List("city" -> JsonSchema.Str()), List("city")))
                assert(byName("destination") == JsonSchema.Obj(List("code" -> JsonSchema.Str()), List("code")))
            case other => fail(s"expected an Obj, got: $other")
        end match
    }

    "arrays recover a collection over the element type" in {
        val schema = JsonSchema.Arr(JsonSchema.Obj(List("name" -> JsonSchema.Str()), List("name")))
        JsonSchema.toStructure(schema) match
            case c: Structure.Type.Collection =>
                assert(c.elementType.isInstanceOf[Structure.Type.Product])
            case other => fail(s"expected a Collection, got: $other")
        end match

        val tpe = JsonSchema.toStructure(schema)
        val ok = Structure.Value.Sequence(Chunk(
            Structure.Value.Record(Chunk("name" -> Structure.Value.Str("a")))
        ))
        assert(conforms(ok, tpe))
        val bad = Structure.Value.Sequence(Chunk(Structure.Value.Record(Chunk.empty)))
        assert(violation(bad, tpe).contains("missing required field 'name'"))
    }

    "a nullable property recovers an optional that accepts null" in {
        val schema = JsonSchema.Obj(
            properties = List("note" -> JsonSchema.Nullable(JsonSchema.Str())),
            required = List("note")
        )
        val tpe = JsonSchema.toStructure(schema)
        assert(conforms(Structure.Value.Record(Chunk("note" -> Structure.Value.Null)), tpe))
        assert(conforms(Structure.Value.Record(Chunk("note" -> Structure.Value.Str("hi"))), tpe))
        assert(violation(Structure.Value.Record(Chunk("note" -> Structure.Value.Integer(1))), tpe).contains("note"))
    }

    "an open object recovers a mapping over its additionalProperties" in {
        val schema = JsonSchema.Obj(
            properties = List.empty,
            required = List.empty,
            additionalProperties = Present(JsonSchema.Str())
        )
        JsonSchema.toStructure(schema) match
            case m: Structure.Type.Mapping =>
                assert(m.keyType.equals(summon[Schema[String]].structure))
                assert(m.valueType.equals(summon[Schema[String]].structure))
            case other => fail(s"expected a Mapping, got: $other")
        end match
    }

    "an unconstrained object recovers the open type, which accepts any value" in {
        val tpe = JsonSchema.toStructure(JsonSchema.Obj(List.empty, List.empty))
        assert(tpe.isInstanceOf[Structure.Type.Open])
        assert(conforms(Structure.Value.Record(Chunk("anything" -> Structure.Value.Integer(1))), tpe))
    }

    "a oneOf recovers a sum over its variants" in {
        val schema = JsonSchema.OneOf(List(
            "text"  -> JsonSchema.Obj(List("body" -> JsonSchema.Str()), List("body")),
            "image" -> JsonSchema.Obj(List("url" -> JsonSchema.Str()), List("url"))
        ))
        JsonSchema.toStructure(schema) match
            case s: Structure.Type.Sum =>
                assert(s.variants.map(_.name) == Chunk("text", "image"))
            case other => fail(s"expected a Sum, got: $other")
        end match
    }

    "re-deriving a JsonSchema from the recovered type preserves what a model acts on" in {
        // The load-bearing round trip: an MCP tool's inputSchema is recovered and re-derived, and the
        // property names, types, required set, nesting and per-property descriptions all survive.
        val original = JsonSchema.Obj(
            properties = List(
                "sql"     -> JsonSchema.Str(description = Present("a read-only SELECT")),
                "limit"   -> JsonSchema.Integer(description = Present("max rows")),
                "tags"    -> JsonSchema.Arr(JsonSchema.Str()),
                "options" -> JsonSchema.Obj(List("verbose" -> JsonSchema.Bool()), List("verbose"))
            ),
            required = List("sql", "tags", "options")
        )
        val roundTripped = JsonSchema.fromStructure(JsonSchema.toStructure(original))
        roundTripped match
            case JsonSchema.Obj(properties, required, _, _, _, _) =>
                assert(properties.map(_._1) == List("sql", "limit", "tags", "options"))
                assert(required.toSet == Set("sql", "tags", "options"))
                val byName = properties.toMap
                assert(byName("sql") == JsonSchema.Str(description = Present("a read-only SELECT")))
                assert(byName("limit") == JsonSchema.Integer(description = Present("max rows")))
                assert(byName("tags") == JsonSchema.Arr(JsonSchema.Str()))
                assert(byName("options") == JsonSchema.Obj(List("verbose" -> JsonSchema.Bool()), List("verbose")))
            case other => fail(s"expected an Obj, got: $other")
        end match
    }

end JsonSchemaStructureTest
