package externalschema

import kyo.*

// Regression fixtures for issue #1887: configuration applied through the fluent
// API (discriminator, variant renames, field renames) must reach recursive
// occurrences of the type. The givens are TOP-LEVEL and declared AFTER the
// containers on purpose: with an object-scoped given the derivation macro ties
// recursive references to the configured given, but a top-level given is not
// found by the self-summon inside its own right-hand side, so nested
// occurrences silently bound to an unconfigured derived schema.

sealed trait RTNode
case class RTParent(ref: String, children: Seq[RTNode]) extends RTNode
case class RTChild(ref: String)                         extends RTNode

final case class RTContainer(items: Seq[RTNode]) derives Schema

given Schema[RTNode] = Schema[RTNode]
    .discriminator("type")
    .renameAllVariants(Schema.NameCase.KebabCase)

enum RTTree:
    case Branch(label: String, limbs: Seq[RTTree])
    case Leaf(label: String)

given Schema[RTTree] = Schema[RTTree]
    .discriminator("kind")
    .renameAllVariants(Schema.NameCase.KebabCase)

final case class RTDoc(fullName: String, parts: Seq[RTDoc])

given Schema[RTDoc] = Schema[RTDoc].renameAllFields(Schema.NameCase.SnakeCase)

class SchemaRecursiveTransformTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    "sealed trait, top-level given" - {
        "encode uses the discriminator at every depth" in {
            val json = Json.encode(RTContainer(Seq(RTParent("p1", Seq(RTChild("c1"))))))
            assert(json == """{"items":[{"type":"rt-parent","ref":"p1","children":[{"type":"rt-child","ref":"c1"}]}]}""")
        }
        "decode reads the discriminator at every depth" in {
            val json   = """{"items":[{"type":"rt-parent","ref":"p1","children":[{"type":"rt-child","ref":"c1"}]}]}"""
            val result = Json.decode[RTContainer](json, 32, Int.MaxValue)
            assert(result == Result.succeed(RTContainer(Seq(RTParent("p1", Seq(RTChild("c1")))))))
        }
        "decode ignores extra fields in nested variants" in {
            val json   = """{"items":[{"type":"rt-parent","ref":"p1","children":[{"extra":"val","type":"rt-child","ref":"c1"}]}]}"""
            val result = Json.decode[RTContainer](json, 32, Int.MaxValue)
            assert(result == Result.succeed(RTContainer(Seq(RTParent("p1", Seq(RTChild("c1")))))))
        }
        "round-trip at depth three" in {
            val value = RTContainer(Seq(RTParent("p1", Seq(RTChild("c1"), RTParent("p2", Seq(RTChild("c2")))))))
            assert(Json.decode[RTContainer](Json.encode(value)) == Result.succeed(value))
        }
    }

    "enum, top-level given" - {
        "encode uses the discriminator at every depth" in {
            val json = Json.encode(RTTree.Branch("a", Seq(RTTree.Leaf("b"))): RTTree)
            assert(json == """{"kind":"branch","label":"a","limbs":[{"kind":"leaf","label":"b"}]}""")
        }
        "round-trip" in {
            val value: RTTree = RTTree.Branch("a", Seq(RTTree.Leaf("b"), RTTree.Branch("c", Seq.empty)))
            assert(Json.decode[RTTree](Json.encode(value)) == Result.succeed(value))
        }
    }

    "recursive product with renameAllFields, top-level given" - {
        "encode renames fields at every depth" in {
            val json = Json.encode(RTDoc("root", Seq(RTDoc("kid", Seq.empty))))
            assert(json == """{"full_name":"root","parts":[{"full_name":"kid","parts":[]}]}""")
        }
        "round-trip" in {
            val value = RTDoc("root", Seq(RTDoc("kid", Seq.empty)))
            assert(Json.decode[RTDoc](Json.encode(value)) == Result.succeed(value))
        }
    }
end SchemaRecursiveTransformTest
