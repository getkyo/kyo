package kyo

import kyo.Json.JsonSchema
import kyo.schema.doc

class JsonDocTest extends kyo.test.Test[Any]:

    case class City(@doc("ISO country code") country: String) derives Schema

    "Json.jsonSchema of a @doc field carries description" in {
        Json.jsonSchema[City] match
            case JsonSchema.Obj(properties, _, _, _, _, _) =>
                val countryNode = properties.find(_._1 == "country").map(_._2).get
                countryNode match
                    case s: JsonSchema.Str =>
                        assert(s.description == Maybe.Present("ISO country code"))
                    case other =>
                        fail(s"expected Str node, got $other")
                end match
            case other =>
                fail(s"expected Obj schema, got $other")
        end match
    }

    "Json.encode of jsonSchema emits the description key" in {
        val encoded = Json.encode(Json.jsonSchema[City])
        assert(encoded.contains("\"description\""))
        assert(encoded.contains("ISO country code"))
    }

    case class CityNoDoc(country: String) derives Schema

    "a field with no @doc yields no description (negative)" in {
        val encoded = Json.encode(Json.jsonSchema[CityNoDoc])
        assert(!encoded.contains("\"description\""))
    }

    case class Inner(@doc("the x") x: Int) derives Schema
    case class Outer(@doc("the inner") inner: Inner) derives Schema

    "a @doc on a nested-object field attaches to the object node" in {
        Json.jsonSchema[Outer] match
            case JsonSchema.Obj(properties, _, _, _, _, _) =>
                val innerNode = properties.find(_._1 == "inner").map(_._2).get
                innerNode match
                    case obj: JsonSchema.Obj =>
                        assert(obj.description == Maybe.Present("the inner"))
                        val xNode = obj.properties.find(_._1 == "x").map(_._2).get
                        xNode match
                            case i: JsonSchema.Integer =>
                                assert(i.description == Maybe.Present("the x"))
                            case other =>
                                fail(s"expected Integer node for x, got $other")
                        end match
                    case other =>
                        fail(s"expected Obj node for inner, got $other")
                end match
            case other =>
                fail(s"expected Obj schema, got $other")
        end match
    }

    case class Flagged(@doc("strictly followed all instructions") followed: Boolean = true) derives Schema

    "a @doc on a Boolean field emits the description (load-bearing Bool fix)" in {
        Json.jsonSchema[Flagged] match
            case JsonSchema.Obj(properties, _, _, _, _, _) =>
                val followedNode = properties.find(_._1 == "followed").map(_._2).get
                followedNode match
                    case b: JsonSchema.Bool =>
                        assert(b.description == Maybe.Present("strictly followed all instructions"))
                    case other =>
                        fail(s"expected Bool node, got $other")
                end match
            case other =>
                fail(s"expected Obj schema, got $other")
        end match
        val encoded = Json.encode(Json.jsonSchema[Flagged])
        assert(encoded.contains("\"type\":\"boolean\""))
        assert(encoded.contains("\"description\":\"strictly followed all instructions\""))
    }

    case class NoDocBool(followed: Boolean) derives Schema

    "a Boolean field with no @doc produces a Bool with description == Maybe.empty (negative)" in {
        Json.jsonSchema[NoDocBool] match
            case JsonSchema.Obj(properties, _, _, _, _, _) =>
                val followedNode = properties.find(_._1 == "followed").map(_._2).get
                followedNode match
                    case b: JsonSchema.Bool =>
                        assert(b.description == Maybe.empty)
                    case other =>
                        fail(s"expected Bool node, got $other")
                end match
            case other =>
                fail(s"expected Obj schema, got $other")
        end match
        val encoded = Json.encode(Json.jsonSchema[NoDocBool])
        assert(!encoded.contains("\"description\""))
    }

    "a Bool/Null JsonSchema round-trips its description through JSON" in {
        val boolSchema: JsonSchema = JsonSchema.Bool(Maybe("a boolean note"))
        val encodedBool            = Json.encode(boolSchema)
        val decodedBool            = Json.decode[JsonSchema](encodedBool).getOrThrow
        assert(decodedBool == boolSchema)

        val nullSchema: JsonSchema = JsonSchema.Null(Maybe("a null note"))
        val encodedNull            = Json.encode(nullSchema)
        val decodedNull            = Json.decode[JsonSchema](encodedNull).getOrThrow
        assert(decodedNull == nullSchema)
    }

end JsonDocTest
