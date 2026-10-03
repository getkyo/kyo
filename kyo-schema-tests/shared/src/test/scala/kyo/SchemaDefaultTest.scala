package kyo

class SchemaDefaultTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    "default" - {

        def assertUnknownField[A](result: Result[DecodeException, A], fieldName: String)(using kyo.test.AssertScope): Unit =
            result match
                case Result.Failure(ex: UnknownFieldException) =>
                    assert(ex.fieldName == fieldName)
                case other =>
                    fail(s"Expected UnknownFieldException for $fieldName, got $other")
            end match
        end assertUnknownField

        "primitive field default decodes concrete value when missing" in {
            val schema = Schema[DefaultPrimitive].default(_.name)("generated")
            val result = schema.decodeString[Json]("""{"id":1}""")
            assert(result == Result.Success(DefaultPrimitive(1, "generated")))
        }

        "nested product field default decodes concrete value when missing" in {
            val child  = DefaultNestedChild("sku", 2)
            val schema = Schema[DefaultNestedParent].default(_.child)(child)
            val result = schema.decodeString[Json]("""{"id":1}""")
            assert(result == Result.Success(DefaultNestedParent(1, child)))
        }

        "collection field default decodes concrete value when missing" in {
            val schema = Schema[DefaultCollection].default(_.items)(Chunk("one", "two"))
            val result = schema.decodeString[Json]("""{"name":"cart"}""")
            assert(result == Result.Success(DefaultCollection(Chunk("one", "two"), "cart")))
        }

        "sum field default decodes concrete variant when missing" in {
            val schema = Schema[DefaultSumParent].default(_.shape)(MTCircle(5.0): MTShape)
            val result = schema.decodeString[Json]("""{"id":1}""")
            assert(result == Result.Success(DefaultSumParent(1, MTCircle(5.0))))
        }

        "supplier wins over Scala default" in {
            val schema = Schema[DefaultWithScala].default(_.name)("configured")
            val result = schema.decodeString[Json]("""{"id":1}""")
            assert(result == Result.Success(DefaultWithScala(1, "configured")))
        }

        "present field does not evaluate supplier" in {
            var evaluations = 0
            val schema      = Schema[DefaultPrimitive].default(_.name) {
                evaluations += 1
                "generated"
            }
            val result = schema.decodeString[Json]("""{"id":1,"name":"wire"}""")
            assert(result == Result.Success(DefaultPrimitive(1, "wire")))
            assert(evaluations == 0)
        }

        "missing field evaluates supplier exactly once per decode" in {
            var evaluations = 0
            val schema      = Schema[DefaultPrimitive].default(_.name) {
                evaluations += 1
                s"generated-$evaluations"
            }
            val first  = schema.decodeString[Json]("""{"id":1}""")
            val second = schema.decodeString[Json]("""{"id":2}""")
            assert(first == Result.Success(DefaultPrimitive(1, "generated-1")))
            assert(second == Result.Success(DefaultPrimitive(2, "generated-2")))
            assert(evaluations == 2)
        }

        "unconfigured missing required field still fails" in {
            val result       = Schema[DefaultPrimitive].decodeString[Json]("""{"id":1}""")
            val missingField = result match
                case Result.Failure(_: MissingFieldException) => true
                case _                                        => false
            assert(missingField)
        }

        "builder order preserves source keys across rename and strict mode" in {
            val defaultThenRename = Schema[DefaultRename]
                .default(_.firstName)("Ada")
                .rename(_.firstName, "givenName")
                .denyUnknownFields
            val renameThenDefault = Schema[DefaultRename]
                .rename(_.firstName, "givenName")
                .default(_.givenName)("Ada")
                .denyUnknownFields

            val input = """{"lastName":"Lovelace"}"""
            assert(defaultThenRename.decodeString[Json](input) == Result.Success(DefaultRename("Ada", "Lovelace")))
            assert(renameThenDefault.decodeString[Json](input) == Result.Success(DefaultRename("Ada", "Lovelace")))
            assertUnknownField(defaultThenRename.decodeString[Json]("""{"lastName":"Lovelace","extra":true}"""), "extra")
        }

        "drop composition does not evaluate an unused supplier" in {
            var evaluations = 0
            val schema      = Schema[DefaultDrop]
                .default(_.removed) {
                    evaluations += 1
                    Maybe("configured")
                }
                .drop("removed")
            val result = schema.decodeString[Json]("""{"id":1}""")
            assert(result == Result.Success(DefaultDrop(1, Maybe.empty)))
            assert(evaluations == 0)
        }

        "flattened strict builder order preserves child fields" in {
            val flattenThenStrict = Schema[StrictFlattenParent].flatten.denyUnknownFields
            val strictThenFlatten = Schema[StrictFlattenParent].denyUnknownFields.flatten
            val accepted          = """{"id":1,"code":"sku","quantity":2}"""
            val rejected          = """{"id":1,"code":"sku","quantity":2,"extra":true}"""

            assert(flattenThenStrict.decodeString[Json](accepted) == Result.Success(StrictFlattenParent(1, StrictFlattenChild("sku", 2))))
            assert(strictThenFlatten.decodeString[Json](accepted) == Result.Success(StrictFlattenParent(1, StrictFlattenChild("sku", 2))))
            assertUnknownField(flattenThenStrict.decodeString[Json](rejected), "extra")
            assertUnknownField(strictThenFlatten.decodeString[Json](rejected), "extra")
        }

        "default targeting a flattened parent evaluates only when child fields are absent" in {
            var evaluations = 0
            val schema      = Schema[StrictFlattenParent]
                .default(_.child) {
                    evaluations += 1
                    StrictFlattenChild("fallback", 9)
                }
                .flatten
                .denyUnknownFields

            val fromChildren = schema.decodeString[Json]("""{"id":1,"code":"sku","quantity":2}""")
            assert(fromChildren == Result.Success(StrictFlattenParent(1, StrictFlattenChild("sku", 2))))
            assert(evaluations == 0)

            val fromDefault = schema.decodeString[Json]("""{"id":1}""")
            assert(fromDefault == Result.Success(StrictFlattenParent(1, StrictFlattenChild("fallback", 9))))
            assert(evaluations == 1)
        }
    }

    "cross-feature: denyUnknownFields + default + omit whenDefault + rename + builder-order reversal" in {
        val schemaA = Schema[CrossFeaturePerson]
            .rename(_.firstName, "first_name")
            .default(_.score)(42)
            .omit(_.score).whenDefault
            .denyUnknownFields

        val schemaB = Schema[CrossFeaturePerson]
            .denyUnknownFields
            .omit(_.score).whenDefault
            .default(_.score)(42)
            .rename(_.firstName, "first_name")

        // schemaA: renamed wire name accepted, score present decodes normally
        val r1 = schemaA.decodeString[Json]("""{"first_name":"Alice","score":42}""")
        assert(r1 == Result.Success(CrossFeaturePerson("Alice", 42)), s"score present must decode: $r1")

        // schemaA: absent score falls to default supplier (42)
        val r2 = schemaA.decodeString[Json]("""{"first_name":"Bob"}""")
        assert(r2 == Result.Success(CrossFeaturePerson("Bob", 42)), s"absent score must use supplier: $r2")

        // schemaA: encode score=0 (== Scala default) -> omitted by whenDefault
        val e1 = schemaA.encodeString[Json](CrossFeaturePerson("Carol", 0))
        assert(!e1.contains("\"score\""), s"score==0 (Scala default) must be omitted: $e1")
        assert(e1.contains("\"first_name\":\"Carol\""), s"renamed field must appear: $e1")

        // schemaA: encode score=7 (not Scala default 0) -> retained
        val e2 = schemaA.encodeString[Json](CrossFeaturePerson("Dave", 7))
        assert(e2.contains("\"score\":7"), s"score!=0 must be retained: $e2")
        assert(e2.contains("\"first_name\":\"Dave\""), s"renamed field must appear: $e2")

        // schemaA: encode score=42 (not Scala default 0) -> retained (42 != compile-time default 0)
        val e3 = schemaA.encodeString[Json](CrossFeaturePerson("Ivan", 42))
        assert(e3.contains("\"score\":42"), s"score=42 must be retained (42 != Scala default 0): $e3")

        // schemaA: unknown field -> UnknownFieldException
        val r3 = schemaA.decodeString[Json]("""{"first_name":"Eve","extra":1}""")
        assert(r3.isFailure, s"unknown field must fail: $r3")
        assert(
            r3.failure.exists(_.isInstanceOf[UnknownFieldException]),
            s"failure must be UnknownFieldException: $r3"
        )

        // schemaA: original source name (renamed away) is rejected
        val r4 = schemaA.decodeString[Json]("""{"firstName":"X"}""")
        assert(r4.isFailure, s"renamed-away source name must fail: $r4")
        assert(
            r4.failure.exists(_.isInstanceOf[UnknownFieldException]),
            s"failure must be UnknownFieldException: $r4"
        )

        // schemaB: same decode behavior as schemaA (builder-order reversal)
        val r5 = schemaB.decodeString[Json]("""{"first_name":"F"}""")
        assert(r5 == Result.Success(CrossFeaturePerson("F", 42)), s"schemaB absent score must use supplier: $r5")

        // schemaB: same omit behavior as schemaA
        val e4 = schemaB.encodeString[Json](CrossFeaturePerson("G", 0))
        assert(!e4.contains("\"score\""), s"schemaB score==0 must be omitted: $e4")
    }

end SchemaDefaultTest
