package kyo

class SchemaTest extends kyo.test.Test[Any]:

    "ByteSize schema" - {
        "round-trips representative values through the Long representation" in {
            val schema = summon[Schema[ByteSize]]
            val values = Chunk(ByteSize.Zero, 64.kib, ByteSize.fromBytes(Long.MaxValue))

            values.foreach { value =>
                val writer = new TestWriter
                schema.writeTo(value, writer)
                val decoded = schema.readFrom(new TestReader(writer.resultTokens))
                assert(decoded == value)
            }
        }

        "clamps a negative decoded byte count to zero" in {
            val schema  = summon[Schema[ByteSize]]
            val decoded = schema.readFrom(new TestReader(List(Token.LongVal(-1L))))
            assert(decoded == ByteSize.Zero)
        }
    }

end SchemaTest
