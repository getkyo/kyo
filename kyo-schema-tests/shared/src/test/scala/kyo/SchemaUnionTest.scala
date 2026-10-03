package kyo

class SchemaUnionTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    // =========================================================================
    // union derivation (OrType arm)
    // =========================================================================

    "a Scala type union derives to a two-variant Sum structure" in {
        // The produced schema must expose a Sum structure with one variant per union member,
        // proving derivation took the union path rather than a fallback.
        val s = summon[Schema[Int | String]]
        s.structure match
            case sum: Structure.Type.Sum =>
                assert(sum.variants.size == 2, s"expected 2 variants; got ${sum.variants.size}")
                assert(sum.variants.map(_.name) == Chunk("Int", "String"), s"expected Chunk(Int, String); got ${sum.variants.map(_.name)}")
            case other =>
                fail(s"expected Structure.Type.Sum from union derivation; got $other")
        end match
    }

    "zero-config union round-trips on all four self-describing codecs" in {
        val s = summon[Schema[Int | String]]
        // Bare untagged payload pin (the untagged default)
        val encInt = s.encodeString[Json](42)
        assert(encInt == "42", s"Int member JSON: expected '42' but got '$encInt'")
        val decInt = s.decodeString[Json]("42")
        assert(decInt == Result.succeed(42), s"Int JSON decode: $decInt")
        val encStr = s.encodeString[Json]("hello")
        assert(encStr == "\"hello\"", s"String member JSON: expected '\"hello\"' but got '$encStr'")
        val decStr = s.decodeString[Json]("\"hello\"")
        assert(decStr == Result.succeed("hello"), s"String JSON decode: $decStr")
        // Yaml
        val yamlInt = s.encodeString[Yaml](42)
        assert(s.decodeString[Yaml](yamlInt) == Result.succeed(42), s"Int Yaml round-trip: $yamlInt")
        val yamlStr = s.encodeString[Yaml]("hello")
        assert(s.decodeString[Yaml](yamlStr) == Result.succeed("hello"), s"String Yaml round-trip: $yamlStr")
        // Ion
        val ionInt = s.encodeString[Ion](42)
        assert(s.decodeString[Ion](ionInt) == Result.succeed(42), s"Int Ion round-trip: $ionInt")
        val ionStr = s.encodeString[Ion]("hello")
        assert(s.decodeString[Ion](ionStr) == Result.succeed("hello"), s"String Ion round-trip: $ionStr")
        // MsgPack (binary codec)
        val mpInt = s.encode[MsgPack](42)
        assert(s.decode[MsgPack](mpInt) == Result.succeed(42), "Int MsgPack round-trip failed")
        val mpStr = s.encode[MsgPack]("hello")
        assert(s.decode[MsgPack](mpStr) == Result.succeed("hello"), "String MsgPack round-trip failed")
    }

    "nested union flattens to one ordered Sum with declared member order" in {
        val s = summon[Schema[Int | (String | Boolean)]]
        s.structure match
            case sum: Structure.Type.Sum =>
                val names = sum.variants.map(_.name)
                assert(
                    names == Chunk("Int", "String", "Boolean"),
                    s"expected Chunk(Int, String, Boolean) but got $names"
                )
                assert(sum.variants.size == 3, s"expected 3 variants but got ${sum.variants.size}")
            case other =>
                fail(s"expected Structure.Type.Sum but got $other")
        end match
    }

    "declaration order determines Sum variant order" in {
        val s = summon[Schema[Boolean | Int | String]]
        s.structure match
            case sum: Structure.Type.Sum =>
                val names = sum.variants.map(_.name)
                assert(
                    names == Chunk("Boolean", "Int", "String"),
                    s"reordered declaration yields reordered variants; got $names"
                )
            case other =>
                fail(s"expected Structure.Type.Sum but got $other")
        end match
    }

    "Maybe/Result/Either keep their explicit given schemas and are never rerouted through union derivation" in {
        // Maybe[A] resolves to maybeSchema (the explicit given), not the OrType union arm.
        val mSchema    = summon[Schema[Maybe[Int]]]
        val encPresent = mSchema.encodeString[Json](Maybe(5))
        assert(encPresent == "5", s"Maybe(5) must encode as '5' (null-or-inner); got '$encPresent'")
        val encAbsent = mSchema.encodeString[Json](Maybe.empty)
        assert(encAbsent == "null", s"Maybe.empty must encode as 'null'; got '$encAbsent'")
        // Maybe[A] is a nominal sealed trait; its structure is a Sum with the sealed children,
        // not a 2-variant union Sum named "Union".
        val mStructure = mSchema.structure
        mStructure match
            case sum: Structure.Type.Sum =>
                assert(sum.name != "Union", s"maybeSchema must not produce a union-derived Sum (name 'Union'); got name '${sum.name}'")
            case _ => ()
        end match
        // Result[E, A] encodes with "success"/"failure" keys (adjacent-like shape), not bare payload.
        val rSchema = summon[Schema[Result[String, Int]]]
        val encSucc = rSchema.encodeString[Json](Result.succeed(42))
        assert(encSucc.contains("success"), s"Result.succeed must encode with 'success' key; got '$encSucc'")
        assert(encSucc.contains("42"), s"Result.succeed must include value 42; got '$encSucc'")
        val encFail = rSchema.encodeString[Json](Result.fail("oops"))
        assert(encFail.contains("failure"), s"Result.fail must encode with 'failure' key; got '$encFail'")
        // Either[A, B] encodes with "Right"/"Left" discriminator.
        val eSchema  = summon[Schema[Either[String, Int]]]
        val encRight = eSchema.encodeString[Json](Right(42))
        assert(encRight.contains("Right"), s"Right must encode with 'Right' discriminator; got '$encRight'")
        assert(encRight.contains("42"), s"Right must include value 42; got '$encRight'")
    }

    "untagged union decode is a typed three-way outcome (no-match, exactly-one, multi-match under Strict)" in {
        // No-match: "true" decodes as neither Int nor String; yields NoVariantMatchException.
        val sIntStr = summon[Schema[Int | String]]
        val noMatch = sIntStr.decodeString[Json]("true")
        noMatch match
            case Result.Failure(ex: NoVariantMatchException) =>
                assert(ex.variants.size == 2, s"Expected 2 attempted variants, got ${ex.variants.size}")
            case other => fail(s"Expected Failure(NoVariantMatchException), got $other")
        end match
        // Exactly-one-match: "\"hi\"" decodes only as String (not Int).
        val oneMatch = sIntStr.decodeString[Json]("\"hi\"")
        assert(oneMatch == Result.succeed("hi"), s"Exactly-one-match must return String 'hi', got $oneMatch")
        // Multi-match under Strict (default): "42" decodes as both Int and Long;
        // yields AmbiguousVariantMatchException listing matched members.
        val sIntLong   = summon[Schema[Int | Long]]
        val multiMatch = sIntLong.decodeString[Json]("42")
        multiMatch match
            case Result.Failure(ex: AmbiguousVariantMatchException) =>
                assert(ex.matched.size == 2, s"Expected 2 matched members, got ${ex.matched.size}")
            case other => fail(s"Expected Failure(AmbiguousVariantMatchException) for Int|Long on '42', got $other")
        end match
    }

    "FirstMatch resolves a multi-member match by declared order" in {
        // "42" matches both Int and Long; FirstMatch returns Int (first-declared).
        val s      = summon[Schema[Int | Long]].unionAmbiguity(Schema.UnionAmbiguity.FirstMatch)
        val result = s.decodeString[Json]("42")
        result match
            case Result.Success(v) =>
                assert(v.isInstanceOf[Int], s"FirstMatch must return Int (first-declared), got ${v.getClass}")
                assert(v == 42, s"Value must be 42, got $v")
            case other => fail(s"Expected Result.Success(42: Int), got $other")
        end match
    }

    "ambiguity slot is decode-only: encode output is byte-identical regardless of policy" in {
        val sDefault      = summon[Schema[Int | Long]]
        val sFirstMatch   = summon[Schema[Int | Long]].unionAmbiguity(Schema.UnionAmbiguity.FirstMatch)
        val encDefault    = sDefault.encodeString[Json](42)
        val encFirstMatch = sFirstMatch.encodeString[Json](42)
        assert(
            encDefault == encFirstMatch,
            s"Encode must be identical regardless of ambiguity policy; got '$encDefault' vs '$encFirstMatch'"
        )
        // Policy is preserved through subsequent builder calls (copyWith chain).
        val sChained = sFirstMatch.adjacent("type", "content")
        assert(
            sChained.unionAmbiguityPolicy == Schema.UnionAmbiguity.FirstMatch,
            "unionAmbiguityPolicy must survive copyWith/adjacent chain"
        )
    }

    "union decode probe is non-destructive: a value valid only for a later member decodes correctly" in {
        // "\"hello\"" is not a valid Int but is a valid String (declared second in Int | String).
        // A destructive read would consume the input on the first (failing) Int probe,
        // leaving nothing for the String probe. Non-destructive replay must succeed.
        val s      = summon[Schema[Int | String]]
        val result = s.decodeString[Json]("\"hello\"")
        assert(result == Result.succeed("hello"), s"Non-destructive probe must decode String 'hello', got $result")
    }

    "union member naming via reused variantNames rejects a non-member name at the first encode" in {
        val s      = summon[Schema[Int | String]].variantNames("Nope" -> "x")
        val result = Result.catching[UnknownVariantException](s.encodeString[Json](1))
        result match
            case Result.Failure(e) => assert(e.variantName == "Nope")
            case other             => fail(s"variantNames with unknown member must fail; got $other")
    }

    "nominal untagged sum keeps first-declared-wins decode while type unions probe all members" in {
        // SSRUAmbig is a sealed nominal sum; both SSRUAmbigFirst and SSRUAmbigSecond have field 'x'.
        // readUntagged (first-wins) must still be used for nominal sums, not readUnionMultiProbe.
        val schema = Schema[SSRUAmbig].untagged
        val result = schema.decodeString[Json]("""{"x":5.0}""")
        assert(result == Result.succeed(SSRUAmbigFirst(5.0)), s"Nominal sum must keep first-declared-wins; got $result")
    }

    "union with duplicate simple-name labels is rejected at compile time" in {
        typeCheckFailure(
            "summon[kyo.Schema[kyo.SfA.Dup | kyo.SfB.Dup]]"
        )("duplicate wire labels")
    }

    "disjoint union (Int | String) still derives without error" in {
        val s = summon[Schema[Int | String]]
        s.structure match
            case sum: Structure.Type.Sum =>
                assert(sum.variants.size == 2, s"expected 2 variants; got ${sum.variants.size}")
                assert(sum.variants.map(_.name) == Chunk("Int", "String"), s"expected Chunk(Int, String); got ${sum.variants.map(_.name)}")
            case other =>
                fail(s"expected Structure.Type.Sum from disjoint union; got $other")
        end match
    }

    "case class union round-trips via isInstanceOf dispatch on all four codecs" in {
        val s = summon[Schema[UnionCaseA | UnionCaseB]]
        val a = UnionCaseA("x", 1)
        val b = UnionCaseB(true)
        // Json
        val encA = s.encodeString[Json](a)
        assert(s.decodeString[Json](encA) == Result.succeed(a), s"CaseA Json round-trip: $encA")
        val encB = s.encodeString[Json](b)
        assert(s.decodeString[Json](encB) == Result.succeed(b), s"CaseB Json round-trip: $encB")
        // Yaml
        val yamlA = s.encodeString[Yaml](a)
        assert(s.decodeString[Yaml](yamlA) == Result.succeed(a), s"CaseA Yaml round-trip: $yamlA")
        val yamlB = s.encodeString[Yaml](b)
        assert(s.decodeString[Yaml](yamlB) == Result.succeed(b), s"CaseB Yaml round-trip: $yamlB")
        // Ion
        val ionA = s.encodeString[Ion](a)
        assert(s.decodeString[Ion](ionA) == Result.succeed(a), s"CaseA Ion round-trip: $ionA")
        val ionB = s.encodeString[Ion](b)
        assert(s.decodeString[Ion](ionB) == Result.succeed(b), s"CaseB Ion round-trip: $ionB")
        // MsgPack (binary)
        val mpA = s.encode[MsgPack](a)
        assert(s.decode[MsgPack](mpA) == Result.succeed(a), "CaseA MsgPack round-trip failed")
        val mpB = s.encode[MsgPack](b)
        assert(s.decode[MsgPack](mpB) == Result.succeed(b), "CaseB MsgPack round-trip failed")
    }

end SchemaUnionTest
