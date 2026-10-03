package kyo

class SchemaFrameTest extends kyo.test.Test[Any]:

    "a value the schema writes on a codec call's behalf carries that call's Frame" - {
        val refusing: Schema[String] = Schema.init[String](
            writeFn = (_, w) => throw TransformFailedException("refused")(using w.frame),
            readFn = _.string(),
            structure = Schema.stringSchema.structure
        )

        "a string map key" in {
            val mapSchema  = Schema.mapSchema[String, Int](using refusing, Schema[Int])
            val encodeSite = summon[Frame]
            val e          = intercept[TransformFailedException](Json.encode(Map("a" -> 1))(using mapSchema, encodeSite, summon[Json]))
            assert(e.detail == "refused")
            assert(e.frame == encodeSite, s"raised at ${e.frame}, encoded at $encodeSite")
        }

        "a string map key whose schema writes no string" in {
            val numeric: Schema[String] =
                Schema.init[String](writeFn = (_, w) => w.int(1), readFn = _.string(), structure = Schema.stringSchema.structure)
            val mapSchema  = Schema.mapSchema[String, Int](using numeric, Schema[Int])
            val encodeSite = summon[Frame]
            val e          = intercept[TransformFailedException](Json.encode(Map("a" -> 1))(using mapSchema, encodeSite, summon[Json]))
            assert(e.detail.contains("a string map key's schema wrote"), e.detail)
            assert(e.frame == encodeSite, s"raised at ${e.frame}, encoded at $encodeSite")
        }

        "a default injected for a field the input lacks" in {
            val schema =
                given Schema[String] = refusing
                Schema[SFWPage].default(_.label)("none")
            val decodeSite = summon[Frame]
            Json.decode[SFWPage]("""{"size":1}""")(using summon[Json], schema, decodeSite) match
                case Result.Panic(e: TransformFailedException) =>
                    assert(e.detail == "refused")
                    assert(e.frame == decodeSite, s"raised at ${e.frame}, decoded at $decodeSite")
                case other => fail(s"expected a TransformFailedException, got $other")
            end match
        }

        "a field read through its read override" in {
            val schema =
                given Schema[String] = refusing
                Schema[SFWPage].transformFieldRead(_.label)(_.string())
            val decodeSite = summon[Frame]
            Json.decode[SFWPage]("""{"size":1,"label":"a"}""")(using summon[Json], schema, decodeSite) match
                case Result.Panic(e: TransformFailedException) =>
                    assert(e.detail == "refused")
                    assert(e.frame == decodeSite, s"raised at ${e.frame}, decoded at $decodeSite")
                case other => fail(s"expected a TransformFailedException, got $other")
            end match
        }
    }

    "a value the schema reads back from a captured value carries the decode call's Frame" - {

        "a string map key" in {
            val decodeSite = summon[Frame]
            Json.decode[Map[SFWWord, Int]]("""{"a b":1}""")(using summon[Json], summon[Schema[Map[SFWWord, Int]]], decodeSite) match
                case Result.Failure(e: ConstructorRejectedException) =>
                    assert(e.typeName == "SFWWord")
                    assert(e.frame == decodeSite, s"rejected at ${e.frame}, decoded at $decodeSite")
                case other => fail(s"expected a ConstructorRejectedException, got $other")
            end match
        }

        "a string-keyed map value in the key and value record form" in {
            val decodeSite = summon[Frame]
            Json.decode[Map[SFWWord, SFWWord]]("""[{"key":"abc","value":"a b"}]""")(using
                summon[Json],
                summon[Schema[Map[SFWWord, SFWWord]]],
                decodeSite
            ) match
                case Result.Failure(e: ConstructorRejectedException) =>
                    assert(e.typeName == "SFWWord")
                    assert(e.frame == decodeSite, s"rejected at ${e.frame}, decoded at $decodeSite")
                case other => fail(s"expected a ConstructorRejectedException, got $other")
            end match
        }
    }

end SchemaFrameTest

final case class SFWPage(size: Int, label: String) derives CanEqual

final case class SFWWord(text: String) derives CanEqual

object SFWWord:
    given Schema[SFWWord] =
        Schema.stringSchema.transformVia((text: String) =>
            if text.forall(_.isLetter) then Result.succeed(SFWWord(text)) else Result.fail(s"not a word: $text")
        )(_.text)
end SFWWord
