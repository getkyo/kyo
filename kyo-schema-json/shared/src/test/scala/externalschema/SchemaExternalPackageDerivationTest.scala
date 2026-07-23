package externalschema

import kyo.*
import kyo.schema.*

// These types are declared outside the kyo package, which is the exact condition that
// reproduces the private-type leak bug. The derives Schema macro emits the slot types
// Schema.OmitPolicy and Schema.FieldTransform[A] (both private[kyo]) into the generated
// code for @omit and @transform. From any package that is not kyo (or a sub-package of
// kyo), naming those private types in an explicit type-argument position caused a
// "Required: ?{ OmitPolicy: ? }" compile error.
//
// Two splice families had to be made package-safe:
//   - the EMPTY branches (kyo.Chunk.empty), hit by a plain derives Schema with no omit or
//     transform config (ExtPerson, ExtAddress, ExtShape below);
//   - the POPULATED branches (Chunk.from over the entry tuples), hit when @omit or
//     @transform is actually present (ExtSettings, ExtPacket below).
//
// This file is the regression guard. If either leak reappears, it fails to compile.

case class ExtPerson(name: String, age: Int) derives CanEqual, Schema

case class ExtAddress(street: String, city: String) derives CanEqual, Schema

sealed trait ExtShape derives CanEqual, Schema
case class ExtCircle(radius: Double)              extends ExtShape derives CanEqual
case class ExtRect(width: Double, height: Double) extends ExtShape derives CanEqual

case class ExtSettings(@omit(omit.WhenNone) nickname: Maybe[String] = Maybe.empty) derives CanEqual, Schema

object ExtHex extends Transformer.Full[Int]:
    def write(value: Int, writer: Codec.Writer): Unit = writer.string(value.toHexString)
    def read(reader: Codec.Reader): Int               = java.lang.Integer.parseInt(reader.string(), 16)

case class ExtPacket(@transform(ExtHex) code: Int) derives CanEqual, Schema

class SchemaExternalPackageDerivationTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    "external package derives Schema" - {

        "product round-trip via Json" in {
            val person  = ExtPerson("Alice", 30)
            val json    = Json.encode[ExtPerson](person)
            val decoded = Json.decode[ExtPerson](json).getOrThrow
            assert(decoded == person)
        }

        "product with multiple string fields round-trip" in {
            val addr    = ExtAddress("123 Main St", "Springfield")
            val json    = Json.encode[ExtAddress](addr)
            val decoded = Json.decode[ExtAddress](json).getOrThrow
            assert(decoded == addr)
        }

        "sealed trait round-trip - circle variant" in {
            val shape: ExtShape = ExtCircle(5.0)
            val json            = Json.encode[ExtShape](shape)
            val decoded         = Json.decode[ExtShape](json).getOrThrow
            assert(decoded == shape)
        }

        "sealed trait round-trip - rect variant" in {
            val shape: ExtShape = ExtRect(3.0, 4.0)
            val json            = Json.encode[ExtShape](shape)
            val decoded         = Json.decode[ExtShape](json).getOrThrow
            assert(decoded == shape)
        }

        "encoded Json has expected field names and values" in {
            val json = Json.encode[ExtPerson](ExtPerson("Bob", 25))
            assert(json.contains("\"name\""))
            assert(json.contains("\"Bob\""))
            assert(json.contains("\"age\""))
            assert(json.contains("25"))
        }

        "sealed trait Json contains variant discriminator" in {
            val json = Json.encode[ExtShape](ExtCircle(7.5))
            assert(json.contains("\"ExtCircle\""))
            assert(json.contains("7.5"))
        }

        "@omit (populated omitPolicies branch) round-trips" in {
            assert(Json.encode[ExtSettings](ExtSettings(Maybe.empty)) == "{}")
            assert(Json.encode[ExtSettings](ExtSettings(Maybe("alice"))) == """{"nickname":"alice"}""")
            assert(Json.decode[ExtSettings]("{}").getOrThrow == ExtSettings(Maybe.empty))
        }

        "@transform (populated fieldTransforms branch) round-trips" in {
            assert(Json.encode[ExtPacket](ExtPacket(255)) == """{"code":"ff"}""")
            assert(Json.decode[ExtPacket]("""{"code":"ff"}""").getOrThrow == ExtPacket(255))
        }
    }

end SchemaExternalPackageDerivationTest
