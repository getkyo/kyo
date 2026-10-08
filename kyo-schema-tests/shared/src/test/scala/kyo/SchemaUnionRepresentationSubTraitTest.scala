package kyo

class SchemaUnionRepresentationSubTraitTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    "a sealed sub-trait without a Schema of its own" - {

        val leaves: Chunk[SSRFRoot] = Chunk(SSRFLeaf(1), SSRFMark, SSRFDeepLeaf("d"), SSRFDirect(2))

        "adds its leaves to the root's variants, in declaration order" in {
            Schema[SSRFRoot].structure match
                case sum: Structure.Type.Sum =>
                    assert(sum.variants.map(_.name) == Chunk("SSRFLeaf", "SSRFMark", "SSRFDeepLeaf", "SSRFDirect"))
                case other => fail(s"expected a sum, got $other")
        }

        "the wrapper object keys each leaf by its own name" in {
            assert(leaves.map(Json.encode(_)) == Chunk(
                """{"SSRFLeaf":{"x":1}}""",
                """{"SSRFMark":{}}""",
                """{"SSRFDeepLeaf":{"z":"d"}}""",
                """{"SSRFDirect":{"y":2}}"""
            ))
            assert(leaves.map(v => Json.decode[SSRFRoot](Json.encode(v))) == leaves.map(Result.succeed(_)))
        }

        "a discriminator tags each leaf with its own name and keeps its fields" in {
            val schema = Schema[SSRFRoot].discriminator("type")
            assert(leaves.map(schema.encodeString[Json](_)) == Chunk(
                """{"type":"SSRFLeaf","x":1}""",
                """{"type":"SSRFMark"}""",
                """{"type":"SSRFDeepLeaf","z":"d"}""",
                """{"type":"SSRFDirect","y":2}"""
            ))
            assert(leaves.map(v => schema.decodeString[Json](schema.encodeString[Json](v))) == leaves.map(Result.succeed(_)))
        }

        "adjacent, tupleTagged and tupleFlat round-trip each leaf" in {
            val schemas = Chunk(Schema[SSRFRoot].adjacent("t", "c"), Schema[SSRFRoot].tupleTagged, Schema[SSRFRoot].tupleFlat)
            assert(Schema[SSRFRoot].adjacent("t", "c").encodeString[Json](SSRFDeepLeaf("d")) == """{"t":"SSRFDeepLeaf","c":{"z":"d"}}""")
            schemas.foreach { schema =>
                assert(leaves.map(v => schema.decodeString[Json](schema.encodeString[Json](v))) == leaves.map(Result.succeed(_)))
            }
            succeed
        }

        "untagged writes each leaf's fields alone and reads them back" in {
            // Without a case object, which untagged would read from any object.
            val schema                   = Schema[SSRFPlain].untagged
            val values: Chunk[SSRFPlain] = Chunk(SSRFPlainLeaf(1), SSRFPlainDirect("d"))
            assert(values.map(schema.encodeString[Json](_)) == Chunk("""{"x":1}""", """{"z":"d"}"""))
            assert(values.map(v => schema.decodeString[Json](schema.encodeString[Json](v))) == values.map(Result.succeed(_)))
        }

        "tag-only writes a leaf case object by its own name" in {
            val warm: SSRFColor = SSRFRed
            assert(Json.encode(warm) == "\"SSRFRed\"")
            assert(Json.decode[SSRFColor]("\"SSRFRed\"") == Result.succeed(SSRFRed))
            assert(Json.decode[SSRFColor]("\"SSRFBlue\"") == Result.succeed(SSRFBlue))
        }

        "the catch-all builder names a leaf of a sub-trait" in {
            val schema = Schema[SSRFPlain].discriminator("type").catchAll("SSRFPlainOther")
            val other  = Structure.Value.Record(Chunk("type" -> Structure.Value.Str("zzz")))
            assert(schema.decodeString[Json]("""{"type":"zzz"}""") == Result.succeed(SSRFPlainOther("zzz", other)))
            assert(schema.decodeString[Json]("""{"type":"SSRFPlainLeaf","x":1}""") == Result.succeed(SSRFPlainLeaf(1)))
        }

        "a leaf under two sub-traits is one variant" in {
            Schema[SSRFShared].structure match
                case sum: Structure.Type.Sum => assert(sum.variants.map(_.name) == Chunk("SSRFSharedBoth", "SSRFSharedOnly"))
                case other                   => fail(s"expected a sum, got $other")
            val both: SSRFShared = SSRFSharedBoth(1)
            assert(Json.encode(both) == """{"SSRFSharedBoth":{"x":1}}""")
            assert(Json.decode[SSRFShared]("""{"SSRFSharedBoth":{"x":1}}""") == Result.succeed(both))
        }

        "sub-traits mixed into one another derive, each leaf once" in {
            // 2^23 paths lead down the sub-traits from SSRFChain, so the derivation compiles only if it visits, probes and derives each
            // sub-trait and variant once: a cost per path, such as a given probe that expands `Schema.derived`, would not finish.
            Schema[SSRFChain].structure match
                case sum: Structure.Type.Sum => assert(sum.variants.map(_.name) == Chunk("SSRFChainLeaf"))
                case other                   => fail(s"expected a sum, got $other")
            val leaf: SSRFChain = SSRFChainLeaf(1)
            assert(Json.decode[SSRFChain](Json.encode(leaf)) == Result.succeed(leaf))
        }

        "navigation reaches a leaf by its own name" in {
            val schema = Schema[SSRFRoot]
            assert(schema.focus(_.SSRFDeepLeaf).tag =:= Tag[SSRFDeepLeaf])
            assert(schema.fieldNames == Set("SSRFLeaf", "SSRFMark", "SSRFDeepLeaf", "SSRFDirect"))
        }

        "a leaf's annotations apply under the root, the catch-all included" in {
            val leaf: SSRFTagged = SSRFTaggedLeaf(1)
            assert(Json.encode(leaf) == """{"type":"leaf","x":1}""")
            assert(Json.decode[SSRFTagged]("""{"type":"leaf","x":1}""") == Result.succeed(leaf))
            assert(Json.decode[SSRFTagged]("""{"type":"direct","y":2}""") == Result.succeed(SSRFTaggedDirect(2)))
            val other = Structure.Value.Record(Chunk("type" -> Structure.Value.Str("zzz")))
            assert(Json.decode[SSRFTagged]("""{"type":"zzz"}""") == Result.succeed(SSRFTaggedOther("zzz", other)))
        }
    }

    "a sealed sub-trait with a Schema of its own stays one variant, encoded by that schema" in {
        val leaf: SSRFNested = SSRFNestedLeaf(1)
        val wire             = Json.encode(leaf)
        assert(wire == """{"op":"SSRFNestedGroup","body":{"kind":"SSRFNestedLeaf","x":1}}""")
        assert(Json.decode[SSRFNested](wire) == Result.succeed(leaf))
    }

end SchemaUnionRepresentationSubTraitTest
