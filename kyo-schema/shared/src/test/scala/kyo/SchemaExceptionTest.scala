package kyo

class SchemaExceptionTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    // Minimal Codec stand-in for tests that only need a Codec value to construct a DecodeException.
    // kyo-schema cannot see the real codecs (Json, Yaml, ...), which live in their own format
    // modules, and no Codec instance is otherwise built in this module's test scope. The tests
    // below never call newWriter/newReader, so both throw.
    object TestCodec extends Codec:
        def newWriter(): Codec.Writer                               = throw NotImplementedError()
        def newReader(input: Span[Byte])(using Frame): Codec.Reader = throw NotImplementedError()

    "RecordDecodeException carries position and renders it" in {
        val cause = TruncatedInputException(TestCodec, "unexpected end")
        val e     = RecordDecodeException(41L, 2048L, cause)
        assert(e.recordIndex == 41L)
        assert(e.byteOffset == 2048L)
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

end SchemaExceptionTest
