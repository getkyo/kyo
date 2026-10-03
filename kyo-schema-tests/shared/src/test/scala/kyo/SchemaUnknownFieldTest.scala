package kyo

import Record.*

class SchemaUnknownFieldTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    "round two field builders preserve Focused and expose inert state" in {
        val schema      = Schema[Cart]
        val strict      = schema.denyUnknownFields
        val withDefault = schema.default(_.note)(Maybe("hello"))
        val withWhen    = schema.omit(_.items).when {
            case Structure.Value.Sequence(values) => values.isEmpty
            case _                                => false
        }
        val withWhenDefault = schema.omit(_.note).whenDefault

        val _: Schema[Cart] { type Focused = "items" ~ Chunk[String] & "note" ~ Maybe[String] } = strict
        val _: Schema[Cart] { type Focused = "items" ~ Chunk[String] & "note" ~ Maybe[String] } = withDefault
        val _: Schema[Cart] { type Focused = "items" ~ Chunk[String] & "note" ~ Maybe[String] } = withWhen
        val _: Schema[Cart] { type Focused = "items" ~ Chunk[String] & "note" ~ Maybe[String] } = withWhenDefault

        assert(!schema.denyUnknownFieldsEnabled)
        assert(schema.fieldDefaults.isEmpty)
        assert(schema.fieldTransforms.isEmpty)
        assert(!schema.hasTransforms)
        assert(!schema.hasReadTransforms)

        assert(strict.denyUnknownFieldsEnabled)
        assert(!strict.hasTransforms)
        assert(strict.hasReadTransforms)

        assert(withDefault.fieldDefaults.map(_._1) == Chunk("note"))
        assert(!withDefault.hasTransforms)
        assert(withDefault.hasReadTransforms)

        assert(withWhen.omitPolicies.map(_._1) == Chunk("items"))
        withWhen.omitPolicies(0)._2 match
            case Schema.OmitPolicy.When(predicate) =>
                assert(predicate(Structure.Value.Sequence(Chunk.empty)))
                assert(!predicate(Structure.Value.Sequence(Chunk(Structure.Value.Str("x")))))
            case other => fail(s"Expected When policy, got $other")
        end match

        assert(withWhenDefault.omitPolicies == Chunk("note" -> Schema.OmitPolicy.WhenDefault))
    }

    "unknown field exception has decode-only shape" in {
        val ex = UnknownFieldException(Seq("root"), "extra")
        assert(ex.isInstanceOf[SchemaException])
        assert(ex.isInstanceOf[DecodeException])
        assert(!ex.isInstanceOf[NavigationException])
        assert(ex.path == Seq("root"))
        assert(ex.fieldName == "extra")
        assert(ex.getMessage.contains("Unknown field 'extra'"))
        assert(ex.getMessage.contains("at root"))
        assert(
            ex.getMessage.contains("Remove this field from the input, or decode with a schema that does not configure denyUnknownFields.")
        )
    }

    "denyUnknownFields" - {

        def assertUnknownField[A](result: Result[DecodeException, A], fieldName: String)(using kyo.test.AssertScope): Unit =
            result match
                case Result.Failure(ex: UnknownFieldException) =>
                    assert(ex.fieldName == fieldName)
                case other =>
                    fail(s"Expected UnknownFieldException for $fieldName, got $other")
            end match
        end assertUnknownField

        "unconfigured product decode ignores an extra JSON field" in {
            val result = Schema[StrictPerson].decodeString[Json]("""{"id":1,"name":"Ada","extra":true}""")
            assert(result == Result.Success(StrictPerson(1, "Ada")))
        }

        "configured product decode rejects the first extra JSON field" in {
            val schema = Schema[StrictPerson].denyUnknownFields
            val result = schema.decodeString[Json]("""{"id":1,"extra":true,"name":"Ada"}""")
            assertUnknownField(result, "extra")
        }

        "renamed and aliased wire names are accepted" in {
            val schema = Schema[StrictRename]
                .rename(_.firstName, "given")
                .alias("given", "first")
                .denyUnknownFields

            val renamed = schema.decodeString[Json]("""{"given":"Ada","lastName":"Lovelace"}""")
            val aliased = schema.decodeString[Json]("""{"first":"Ada","lastName":"Lovelace"}""")

            assert(renamed == Result.Success(StrictRename("Ada", "Lovelace")))
            assert(aliased == Result.Success(StrictRename("Ada", "Lovelace")))
        }

        "renamed-away source name is rejected with the raw field name" in {
            val schema = Schema[StrictRename]
                .rename(_.firstName, "given")
                .denyUnknownFields
            val result = schema.decodeString[Json]("""{"firstName":"Ada","lastName":"Lovelace"}""")
            assertUnknownField(result, "firstName")
        }

        "a parent's rename does not reach a nested record's field of the same name" in {
            val schema = Schema[MTRenamedCityHolder].rename("city", "town")
            val value  = MTRenamedCityHolder("x", MTAddress("a", "b", "c"))
            val wire   = schema.encodeString[Json](value)
            assert(wire == """{"town":"x","home":{"street":"a","city":"b","zip":"c"}}""", wire)
            assert(schema.decodeString[Json](wire) == Result.succeed(value))
        }

        "field-case wire names are accepted and unrelated fields are rejected" in {
            val schema = Schema[StrictFieldCase]
                .renameAllFields(Schema.NameCase.SnakeCase)
                .denyUnknownFields

            val accepted = schema.decodeString[Json]("""{"first_name":"Ada","last_name":"Lovelace"}""")
            val rejected = schema.decodeString[Json]("""{"first_name":"Ada","middle_name":"Byron","last_name":"Lovelace"}""")

            assert(accepted == Result.Success(StrictFieldCase("Ada", "Lovelace")))
            assertUnknownField(rejected, "middle_name")
        }

        "nested product strict decode reports a typed failure" in {
            val schema = Schema[StrictOuter].denyUnknownFields
            val result = schema.decodeString[Json]("""{"name":"root","inner":{"value":1,"extra":2}}""")
            assertUnknownField(result, "extra")
        }

        "flatten accepts flattened child keys and rejects unrelated unknown keys" in {
            val schema   = Schema[StrictFlattenParent].flatten.denyUnknownFields
            val accepted = schema.decodeString[Json]("""{"id":1,"code":"sku","quantity":2}""")
            val rejected = schema.decodeString[Json]("""{"id":1,"code":"sku","quantity":2,"extra":true}""")

            assert(accepted == Result.Success(StrictFlattenParent(1, StrictFlattenChild("sku", 2))))
            assertUnknownField(rejected, "extra")
        }

        "strict mode does not reject synthetic empty collection injection" in {
            val schema = Schema[CartWithList].omitEmptyCollections.denyUnknownFields
            val result = schema.decodeString[Json]("""{"name":"x"}""")
            assert(result == Result.Success(CartWithList(List.empty, "x")))
        }
    }

end SchemaUnknownFieldTest
