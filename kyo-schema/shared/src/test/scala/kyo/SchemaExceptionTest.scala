package kyo

class SchemaExceptionTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    // Minimal Codec stand-in for tests that only need a Codec value to construct a DecodeException.
    // kyo-schema cannot see the real codecs (Json, Yaml, ...), which live in their own format
    // modules, and no Codec instance is otherwise built in this module's test scope. The tests
    // below never call newWriter/newReader, so both throw.
    object TestCodec extends Codec:
        def newWriter()(using Frame): Codec.Writer                  = throw NotImplementedError()
        def newReader(input: Span[Byte])(using Frame): Codec.Reader = throw NotImplementedError()

    "RecordDecodeException carries position and renders it" in {
        val cause = TruncatedInputException(TestCodec, "unexpected end")
        val e     = RecordDecodeException(41L, 2048L, cause)
        assert(e.recordIndex == 41L)
        assert(e.byteOffset == 2048L)
        assert(e.getCause eq cause)
        assert(e.problem == "Failed to decode record 41 at byte 2048")
        assert(e.getMessage.contains("record 41"))
        assert(e.getMessage.contains("byte 2048"))
    }

    // A record of an application log or an agent transcript is the payload, and a payload placed on
    // an exception reaches every log line and error page that renders it. This type reports where
    // the record sat and never what it held, so its own contribution to the message is positional
    // and the only other text is whatever the cause chose to render.
    "RecordDecodeException adds nothing but position to its cause's message" in {
        val cause = TruncatedInputException(TestCodec, "unexpected end")
        val e     = RecordDecodeException(41L, 2048L, cause)
        assert(e.productArity == 3)
        assert(e.productIterator.toList == List(41L, 2048L, cause))
    }

    "RecordDecodeException is a DecodeException" in {
        val cause              = TruncatedInputException(TestCodec, "unexpected end")
        val e: DecodeException = RecordDecodeException(0L, 0L, cause)
        // The marker is what lets the record surfaces declare one `Abort[DecodeException]` channel for
        // every way a record can fail, so what the membership has to survive is a handler for the marker
        // catching this failure at run time: `Abort.run` resolves the type it handles by `Tag`, which the
        // static ascription above does not exercise. The caught value is the record failure itself, with
        // its position and its underlying cause intact rather than flattened into the marker type.
        Abort.run[DecodeException](Abort.fail(e)).map { r =>
            r.foldError(
                value => fail(s"expected a failure, got $value"),
                {
                    case Result.Failure(caught: RecordDecodeException) =>
                        assert(caught.recordIndex == 0L)
                        assert(caught.byteOffset == 0L)
                        assert(caught.cause == cause)
                    case other => fail(s"unexpected error $other")
                }
            )
        }
    }

    "ParseException with a column prints its input whole and a caret beneath the character at the column" in {
        val input = """{"questions":[{"Open":{"text":"xxxxxxxxxxxxxxxxxxxxxxxxxxx"}}}}"""
        val e     = ParseException(TestCodec, input, "Expected ',' or ']'", Nil, 334, 61)
        assert(e.problem ==
            s"Cannot parse '$input' as Expected ',' or ']' at position 334\n" + " " * ("Cannot parse '".length + 61) + "^")
        assert(input(61) == '}')
    }

    "ParseException's caret sits beneath the line of its input that holds the column, indented by the code points before it" in {
        val e = ParseException(TestCodec, "[\n  \"😀\" 2,\n  3\n]", "Expected ',' or ']'", Nil, 11, 9)
        assert(e.problem ==
            "Cannot parse '[\n  \"😀\" 2,\n" + " " * 6 + "^\n  3\n]' as Expected ',' or ']' at position 11")
        val inline = ParseException(TestCodec, "\"😀\"}", "Unexpected trailing content", Nil, 6, 4)
        assert(inline.problem ==
            "Cannot parse '\"😀\"}' as Unexpected trailing content at position 6\n" + " " * 17 + "^")
    }

    "ParseException without a column cuts its input at 50 chars and prints no caret" in {
        val e = ParseException(TestCodec, "x" * 60, "Int", Nil, 3)
        assert(e.problem == s"Cannot parse '${"x" * 50}...' as Int at position 3")
    }

    "a failure with a remedy states the problem alone, and its message is the problem followed by the remedy" in {
        val path  = Seq("user", "age")
        val cases = Chunk[(SchemaException, String, String)](
            (MissingFieldException(path, "age"), "Missing required field 'age' at user.age", "Add this field to the input."),
            (
                TypeMismatchException(path, "Int", "String"),
                "Type mismatch at user.age: expected Int but got String",
                "Check the input value matches the expected type."
            ),
            (
                UnknownVariantException(Seq.empty, "Cat"),
                "Unknown variant 'Cat'",
                "Check that the Scala variant name is one of the defined case class or object variants."
            ),
            (
                UnknownVariantException(Seq("pet"), "Cat"),
                "Unknown variant 'Cat' at pet",
                "Check that the discriminator value matches one of the defined case class or object variants."
            ),
            (
                UnknownFieldException(path, "nick"),
                "Unknown field 'nick' at user.age",
                "Remove this field from the input, or decode with a schema that does not configure denyUnknownFields."
            ),
            (
                NoVariantMatchException(path, Chunk("A", "B")),
                "No variant matched the untagged input at user.age. Attempted 2 variants: A, B",
                "Ensure the input matches one of the declared variants."
            ),
            (
                AmbiguousVariantMatchException(path, Chunk("A", "B")),
                "Multiple union members matched the untagged input at user.age. Matched 2 members: A, B",
                "Configure unionAmbiguity(FirstMatch) to resolve by declared order, or tag the union with a representation."
            ),
            (
                MissingTagKeyException(path, "type"),
                "Missing adjacent tag key 'type' at user.age",
                "Add the tag key to the input or check the adjacent representation configuration."
            ),
            (
                ConstructorRejectedException(path, "Age", "must be positive"),
                "Constructor rejected the decoded value for 'Age' at user.age: must be positive",
                "The fields decoded successfully; the smart constructor refused them. Correct the input, or decode with a schema whose constructor accepts it."
            ),
            (
                VariantNameCollisionException("cat", Chunk("Cat", "Kitten")),
                "Wire name 'cat' is targeted by 2 variants: Cat, Kitten",
                "Give each variant a distinct wire name."
            ),
            (
                FieldNameCollisionException("id", Chunk("id", "key")),
                "Wire name 'id' is targeted by 2 fields: id, key",
                "Give each field a distinct wire name."
            ),
            (
                PathNotFoundException(path, "age"),
                "Path segment 'age' not found at user.age",
                "Check that the field name exists in the target type."
            ),
            (
                SchemaNotSerializableException("a function"),
                "Schema cannot serialize: a function",
                "Ensure the type is a case class or sealed trait with serialization support, or provide a custom Schema with writeTo/readFrom."
            ),
            (
                RepresentationUnsupportedException("Protobuf", "adjacent"),
                "Codec 'Protobuf' cannot express the 'adjacent' sum representation",
                "Use a self-describing codec (such as: Json, Yaml, Ion, MsgPack) or a different representation."
            ),
            (
                TransformUnsupportedException("Protobuf", "rename"),
                "Codec 'Protobuf' cannot carry the 'rename' schema transform",
                "Use a self-describing codec (such as: Json, Yaml, Ion, MsgPack, Bson) or a schema without it."
            ),
            (
                DuplicateRepresentationException(Chunk(Schema.UnionRepresentation.External, Schema.UnionRepresentation.External)),
                "Representation chain contains a duplicate entry: External, External",
                "Each chain entry must be distinct."
            )
        )
        cases.foreach { (e, problem, remedy) =>
            assert(e.problem == problem)
            assert(e.getMessage.contains(s"$problem. $remedy"))
        }
        succeed
    }

    "a failure with no remedy states its problem as its message" in {
        val e = ValidationFailedException(Seq("age"), "must be positive")
        assert(e.problem == "Validation failed: must be positive at age")
        assert(e.getMessage.contains(e.problem))
    }

    "builder call context remains in the full message and outside the problem" in {
        val call                          = Seq("variantNames", "(Cat -> cat)").mkString
        given SchemaException.BuilderCall = SchemaException.BuilderCall(call)
        val failure                       = TransformFailedException("missing variant")
        assert(failure.problem == "Transform failed: missing variant")
        assert(failure.getMessage == KyoException("Transform failed: missing variant", call)(using failure.frame).getMessage)
        val unknown = UnknownVariantException(Seq.empty, "Cat")
        assert(unknown.problem == "Unknown variant 'Cat'")
        assert(unknown.getMessage == KyoException(
            "Unknown variant 'Cat'. Check that the Scala variant name is one of the defined case class or object variants.",
            call
        )(using unknown.frame).getMessage)
    }

    "a parse failure keeps its column when the decode path is prepended" in {
        val failure = ParseException(TestCodec, "[1 2]", "Expected ',' or ']'", Nil, 3, 3).prependPath("value")
        failure match
            case e: ParseException =>
                assert(e.path == Seq("value"))
                assert(e.column == 3)
                assert(e.position == 3)
                assert(e.problem == "Cannot parse '[1 2]' as Expected ',' or ']' at position 3 at value\n" + " " * 17 + "^")
            case other => fail(s"expected a ParseException, got $other")
        end match
    }

end SchemaExceptionTest
