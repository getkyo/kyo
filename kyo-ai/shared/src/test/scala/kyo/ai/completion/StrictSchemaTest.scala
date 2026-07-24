package kyo.ai.completion

import kyo.*
import kyo.Json
import kyo.Json.JsonSchema

class StrictSchemaTest extends kyo.test.Test[Any]:

    case class StrictProbe(name: String, note: Maybe[String]) derives Schema

    "result transforms a nested object to strict all-required, additionalProperties:false form" in {
        val schema = JsonSchema.Obj(
            properties = List("a" -> JsonSchema.Str(), "b" -> JsonSchema.Str()),
            required = List.empty
        )
        StrictSchema.result(schema) match
            case Result.Success(value) =>
                val encoded = Json.encode(value)
                assert(encoded.contains("\"required\":[\"a\",\"b\"]"), s"both properties must be required: $encoded")
                assert(encoded.contains("\"additionalProperties\":false"), s"object must be closed: $encoded")
            case other =>
                fail(s"expected success, got: $other")
        end match
    }

    "result with allowMaps=false fails the open-map path instead of 400-ing" in {
        val schema = JsonSchema.Obj(
            properties = List.empty,
            required = List.empty,
            additionalProperties = Present(JsonSchema.Integer())
        )
        StrictSchema.result(schema, allowMaps = false) match
            case Result.Failure(message) =>
                assert(message.contains("$"), s"failure must name the open-map path: $message")
            case other =>
                fail(s"expected a failure naming the open-map path, got: $other")
        end match
    }

    "result keeps a nullable field as anyOf, requires every property, and closes objects on a derived schema" in {
        StrictSchema.result(Json.jsonSchema[StrictProbe]) match
            case Result.Success(schema) =>
                val encoded = Json.encode(schema)
                assert(!encoded.contains("\"oneOf\""), s"strict schema must not contain oneOf: $encoded")
                assert(encoded.contains("\"anyOf\""), s"nullable fields should use anyOf: $encoded")
                assert(encoded.contains("\"required\":[\"name\",\"note\"]"), s"all properties must be required: $encoded")
                assert(encoded.contains("\"additionalProperties\":false"), s"objects must be closed: $encoded")
            case other =>
                fail(s"expected strict schema, got: $other")
    }

    "requireAll marks every object property required recursively, leaving objects open and unions as oneOf" in {
        val inner = JsonSchema.Obj(
            properties = List("x" -> JsonSchema.Integer()),
            required = List.empty,
            additionalProperties = Present(JsonSchema.Str())
        )
        val union = JsonSchema.OneOf(List(
            "left"  -> JsonSchema.Str(),
            "right" -> JsonSchema.Obj(properties = List("y" -> JsonSchema.Integer()), required = List.empty)
        ))
        val schema = JsonSchema.Obj(
            properties = List("obj" -> inner, "union" -> union),
            required = List.empty
        )
        StrictSchema.requireAll(schema) match
            case JsonSchema.Obj(properties, required, _, _, _, _) =>
                assert(required == List("obj", "union"), s"top-level required: $required")
                properties.toMap.get("obj") match
                    case Some(JsonSchema.Obj(_, innerRequired, innerAdditional, _, _, _)) =>
                        assert(innerRequired == List("x"), s"nested object required: $innerRequired")
                        assert(
                            innerAdditional == Present(JsonSchema.Str()),
                            s"additionalProperties must stay as given, not forced false: $innerAdditional"
                        )
                    case other => fail(s"expected the nested object schema, got: $other")
                end match
                properties.toMap.get("union") match
                    case Some(JsonSchema.OneOf(variants)) =>
                        assert(variants.map(_._1) == List("left", "right"), s"union variant names must be preserved: $variants")
                        variants.toMap.get("right") match
                            case Some(JsonSchema.Obj(_, rightRequired, _, _, _, _)) =>
                                assert(rightRequired == List("y"), s"a variant's own object must be require-alled too: $rightRequired")
                            case other => fail(s"expected the right variant's object schema, got: $other")
                        end match
                    case other => fail(s"expected the union unchanged as OneOf, got: $other")
                end match
            case other => fail(s"expected an Obj, got: $other")
        end match
    }

    "requireAll keeps a nullable property nullable while marking it required, and recurses into array items" in {
        // The uniform advisory contract rests on this: a required nullable field must stay
        // satisfiable by null (the strict:false backends get no 400 and no decode error if this
        // regresses, only a model that can no longer answer null), so the nullable arm surviving
        // requireAll is pinned here.
        val schema = JsonSchema.Obj(
            properties = List(
                "note" -> JsonSchema.Nullable(JsonSchema.Str()),
                "rows" -> JsonSchema.Arr(items =
                    JsonSchema.Obj(properties = List("x" -> JsonSchema.Integer()), required = List.empty)
                )
            ),
            required = List.empty
        )
        StrictSchema.requireAll(schema) match
            case JsonSchema.Obj(properties, required, _, _, _, _) =>
                assert(required == List("note", "rows"), s"both properties must be required: $required")
                assert(
                    properties.toMap.get("note") == Some(JsonSchema.Nullable(JsonSchema.Str())),
                    s"a required nullable property must STAY nullable (present but may be null): ${properties.toMap.get("note")}"
                )
                properties.toMap.get("rows") match
                    case Some(JsonSchema.Arr(JsonSchema.Obj(_, itemRequired, _, _, _, _), _, _, _, _)) =>
                        assert(itemRequired == List("x"), s"requireAll must recurse into array items: $itemRequired")
                    case other => fail(s"expected the array schema with a require-all'd item object, got: $other")
                end match
            case other => fail(s"expected an Obj, got: $other")
        end match
    }

    "requireAll leaves a scalar schema unchanged" in {
        val schema = JsonSchema.Str(description = Present("a name"))
        assert(StrictSchema.requireAll(schema) == schema)
    }

end StrictSchemaTest
