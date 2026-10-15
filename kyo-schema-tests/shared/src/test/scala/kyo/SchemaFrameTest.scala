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

    "a value whose constructor takes a Frame keeps it through the wire" - {

        def roundTrip[A](schema: Schema[A], value: A, codec: Codec): A =
            schema.decode(schema.encode(value)(using codec))(using codec).getOrThrow

        val codecs = Seq(summon[Json], summon[Protobuf], summon[MsgPack], summon[Bson], summon[Ion], summon[Yaml])

        "through every codec" in {
            val live = SFWRaised("p", 3)
            codecs.foreach { codec =>
                val decoded = roundTrip(Schema[SFWRaised], live, codec)
                assert(decoded == live)
                assert(decoded.frame == live.frame, s"${codec.getClass.getSimpleName}: decoded ${decoded.frame}, raised ${live.frame}")
            }
            succeed
        }

        "through a Structure.Value" in {
            val live    = SFWRaised("p", 3)
            val decoded = Structure.decode[SFWRaised](Structure.encode(live)).getOrThrow
            assert(decoded == live)
            assert(decoded.frame == live.frame)
        }

        "written under frame beside the fields" in {
            val live = SFWRaised("p", 3)
            assert(Json.encode(live) == s"""{"provider":"p","retries":3,"frame":${Json.encode(live.frame)}}""")
        }

        "a case field named frame is rejected on a class that keeps its frame" in {
            typeCheckFailure("kyo.Schema.derived[kyo.SFWFrameField]")("has a case field named 'frame'")
        }

        "a case field named frame is an ordinary field on a class that keeps no frame" in {
            val wire = Json.encode(SFWUnkeptFrameField(1))
            assert(wire == """{"frame":1}""")
            assert(Json.decode[SFWUnkeptFrameField](wire).getOrThrow == SFWUnkeptFrameField(1))
        }

        "a case field of type Frame is a field beside the frame the value keeps" in {
            val origin  = summon[Frame]
            val live    = SFWFrameTyped(origin, 1)
            val decoded = Json.decode[SFWFrameTyped](Json.encode(live)).getOrThrow
            assert(decoded.origin == origin)
            assert(decoded.frame == live.frame)
            assert(decoded.origin != decoded.frame)
        }

        "under a naming convention that cases the frame key" in {
            val live    = SFWRaised("p", 3)
            val schema  = Schema[SFWRaised].renameAllFields(Schema.NameCase.PascalCase)
            val decoded = roundTrip(schema, live, summon[Json])
            assert(decoded == live)
            assert(decoded.frame == live.frame, Json.encode(live)(using schema))
        }

        "a key a builder points at frame collides with it" in {
            val live = SFWRaised("p", 3)
            Seq(
                Schema[SFWRaised].rename("provider", "frame"),
                Schema[SFWRaised].alias("provider", "frame"),
                Schema[SFWRaised].add("frame")(_ => 1)
            ).foreach { schema =>
                val e = intercept[FieldNameCollisionException](Json.encode(live)(using schema))
                assert(e.wireName == "frame")
            }
            succeed
        }

        "a field whose value carries its frame keeps it" in {
            val inner   = SFWRaised("p", 3)
            val decoded = Json.decode[SFWOuter](Json.encode(SFWOuter(1, inner))).getOrThrow
            assert(decoded.inner == inner)
            assert(decoded.inner.frame == inner.frame)
        }

        "a value and the field inside it each keep their own frame" in {
            val inner   = SFWRaised("p", 3)
            val live    = SFWFramedOuter(1, inner)
            val decoded = Json.decode[SFWFramedOuter](Json.encode(live)).getOrThrow
            assert(decoded.frame == live.frame)
            assert(decoded.inner.frame == inner.frame)
            assert(decoded.frame != decoded.inner.frame)
        }

        "a flattened field that carries its frame fails to encode, naming the key" in {
            val e =
                intercept[TransformFailedException](Json.encode(SFWOuter(1, SFWRaised("p", 3)))(using Schema[SFWOuter].flatten(_.inner)))
            assert(e.detail.contains("wrote the key 'frame'"))
        }

        "a field flattened into a value that carries its frame keeps that frame" in {
            val live    = SFWFramedHolder(1, SFWPage(2, "a"))
            val schema  = Schema[SFWFramedHolder].flatten(_.page)
            val wire    = Json.encode(live)(using schema)
            val decoded = Json.decode[SFWFramedHolder](wire)(using summon[Json], schema).getOrThrow
            assert(wire == s"""{"id":1,"size":2,"label":"a","frame":${Json.encode(live.frame)}}""")
            assert(decoded.page == SFWPage(2, "a"))
            assert(decoded.frame == live.frame)
        }

        "setting a field through a focus keeps the value's frame" in {
            val live = SFWRaised("p", 3)
            val set  = Schema[SFWRaised].focus(_.provider).set(live, "q")
            assert(set == SFWRaised("q", 3))
            assert(set.frame == live.frame)
        }

        "setting a field through a focus on a class that keeps no frame builds it with the focus site's frame" in {
            val live        = SFWUnkept(1)
            val (set, site) = (Schema[SFWUnkept].focus(_.code).set(live, 2), summon[Frame])
            assert(set.code == 2)
            assert(set.line == site.position.lineNumber)
        }

        "a KyoException subclass, whose frame is the exception's" in {
            val live = SFWFailure(7)
            codecs.foreach { codec =>
                val decoded = roundTrip(Schema[SFWFailure], live, codec)
                assert(decoded.code == 7)
                assert(decoded.frame == live.frame, codec.getClass.getSimpleName)
            }
            succeed
        }

        "a generic class" in {
            val live    = SFWBox(Chunk(1, 2))
            val decoded = Json.decode[SFWBox[Chunk[Int]]](Json.encode(live)).getOrThrow
            assert(decoded.value == Chunk(1, 2))
            assert(decoded.frame == live.frame)
        }

        "a variant of a sum, under each representation that writes its fields by name" in {
            val live: SFWFault = SFWTimeout(5)
            val schemas        = Seq(
                Schema[SFWFault],
                Schema[SFWFault].discriminator("type"),
                Schema[SFWFault].adjacent("type", "value"),
                Schema[SFWFault].tupleTagged,
                Schema[SFWFault].untagged
            )
            schemas.foreach { schema =>
                val decoded = roundTrip(schema, live, summon[Json])
                assert(decoded == live)
                assert(decoded.frame == live.frame, Json.encode(live)(using schema))
            }
            succeed
        }

        "through Schema.derivedVia, whose constructor is given the decoded frame" in {
            val live    = SFWChecked(5)
            val decoded = Json.decode[SFWChecked](Json.encode(live)).getOrThrow
            assert(decoded.code == 5)
            assert(decoded.frame == live.frame)
        }

        "a variant of a tupleFlat sum, whose positions hold only the fields, decodes with the decode call's frame" in {
            val live: SFWFault = SFWTimeout(5)
            val schema         = Schema[SFWFault].tupleFlat
            val decodeSite     = summon[Frame]
            assert(Json.encode(live)(using schema) == """["SFWTimeout",5]""")
            val decoded = Json.decode[SFWFault]("""["SFWTimeout",5]""")(using summon[Json], schema, decodeSite).getOrThrow
            assert(decoded == live)
            assert(decoded.frame == decodeSite)
        }

        "under field renames and denyUnknownFields" in {
            val live    = SFWRaised("p", 3)
            val schemas = Seq(
                Schema[SFWRaised].renameAllFields(Schema.NameCase.SnakeCase),
                Schema[SFWRaised].rename("provider", "vendor"),
                Schema[SFWRaised].denyUnknownFields
            )
            schemas.foreach { schema =>
                val decoded = roundTrip(schema, live, summon[Json])
                assert(decoded == live)
                assert(decoded.frame == live.frame, Json.encode(live)(using schema))
            }
            succeed
        }

        "the decode call's frame when the input has none" in {
            val decodeSite = summon[Frame]
            val decoded    =
                Json.decode[SFWRaised]("""{"provider":"p","retries":3}""")(using summon[Json], summon[Schema[SFWRaised]], decodeSite)
                    .getOrThrow
            assert(decoded == SFWRaised("p", 3))
            assert(decoded.frame == decodeSite)
        }

        "the decode call's frame for a class that keeps none" in {
            val decodeSite = summon[Frame]
            val decoded    =
                Json.decode[SFWUnkept]("""{"code":1}""")(using summon[Json], summon[Schema[SFWUnkept]], decodeSite).getOrThrow
            assert(decoded.code == 1)
            assert(decoded.line == decodeSite.position.lineNumber)
            assert(Json.encode(SFWUnkept(1)) == """{"code":1}""")
        }

        "is not a field of its Structure" in {
            Structure.of[SFWRaised] match
                case product: Structure.Type.Product =>
                    assert(product.fields.map(_.name) == Chunk("provider", "retries"))
                case other => fail(s"expected a product, got $other")
            end match
        }

        "conforms to its Structure" in {
            val live = SFWRaised("p", 3)
            assert(Structure.conform(Structure.encode(live), Structure.of[SFWRaised]) == Absent)
        }

        "a class that keeps its frame in two members is rejected" in {
            typeCheckFailure("kyo.Schema.derived[kyo.SFWTwoFrames]")(
                "Cannot derive Schema for kyo.SFWTwoFrames: its constructor takes a Frame and it has several members of type Frame " +
                    "(frame, other), so the frame it keeps is ambiguous. Keep the constructor's frame in one public member, or provide a " +
                    "given Schema explicitly."
            )
        }
    }

end SchemaFrameTest

final case class SFWRaised(provider: String, retries: Int)(using val frame: Frame) derives CanEqual, Schema

final case class SFWFailure(code: Int)(using Frame) extends KyoException(s"failure $code") derives Schema

final case class SFWBox[A](value: A)(using val frame: Frame) derives Schema

final case class SFWUnkept(code: Int)(using frame: Frame) derives Schema:
    val line: Int = frame.position.lineNumber

sealed abstract class SFWFault(using val frame: Frame) derives Schema, CanEqual

final case class SFWTimeout(seconds: Int)(using Frame) extends SFWFault derives CanEqual

final case class SFWRefused(host: String)(using Frame) extends SFWFault derives CanEqual

final case class SFWFrameField(frame: Int)(using val origin: Frame)

final case class SFWUnkeptFrameField(frame: Int)(using Frame) derives Schema, CanEqual

final case class SFWFrameTyped(origin: Frame, code: Int)(using val frame: Frame) derives Schema

final case class SFWOuter(id: Int, inner: SFWRaised) derives Schema

final case class SFWFramedOuter(id: Int, inner: SFWRaised)(using val frame: Frame) derives Schema

final case class SFWFramedHolder(id: Int, page: SFWPage)(using val frame: Frame) derives Schema

final case class SFWTwoFrames(code: Int)(using val frame: Frame):
    val other: Frame = frame

final case class SFWChecked(code: Int)(using val frame: Frame)

object SFWChecked:
    given Schema[SFWChecked] = Schema.derivedVia((code: Int) => SFWChecked(code))

final case class SFWPage(size: Int, label: String) derives CanEqual

final case class SFWWord(text: String) derives CanEqual

object SFWWord:
    given Schema[SFWWord] =
        Schema.stringSchema.transformVia((text: String) =>
            if text.forall(_.isLetter) then Result.succeed(SFWWord(text)) else Result.fail(s"not a word: $text")
        )(_.text)
end SFWWord
