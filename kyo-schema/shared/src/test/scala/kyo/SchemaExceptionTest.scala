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

    "RecordDecodeException carries position and renders a truncated record" in {
        val long  = "x" * 80
        val cause = TruncatedInputException(TestCodec, "unexpected end")
        val e     = RecordDecodeException(41L, 2048L, long, cause)
        assert(e.recordIndex == 41L)
        assert(e.byteOffset == 2048L)
        assert(e.record == long)
        assert(e.getMessage.contains("record 41"))
        assert(e.getMessage.contains("byte 2048"))
        assert(e.getMessage.contains("x" * 50 + "..."))
        assert(!e.getMessage.contains("x" * 51))
    }

    "RecordDecodeException is a DecodeException" in {
        val cause              = TruncatedInputException(TestCodec, "unexpected end")
        val e: DecodeException = RecordDecodeException(0L, 0L, "{}", cause)
        assert(e.isInstanceOf[SchemaException])
    }

end SchemaExceptionTest
