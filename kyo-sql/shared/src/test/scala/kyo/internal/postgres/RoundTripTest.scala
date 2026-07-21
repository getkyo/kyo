package kyo.internal.postgres

import kyo.*
import kyo.SqlException
import kyo.internal.postgres.Parse
import kyo.internal.postgres.marshaller.ParseMarshaller

/** Round-trip test: marshal a Frontend message, then decode the body with the corresponding reader primitives.
  *
  * This validates that the marshal/unmarshal representations are mutually consistent.
  */
class RoundTripTest extends Test:

    "Marshal then unmarshal Parse round-trip" in {
        val original = Parse("s1", "SELECT $1", Chunk(23))
        val buf      = new PostgresBufferWriter
        ParseMarshaller.write(original, buf)
        val bytes = buf.toSpan.toArray

        // The wire format is: 'P'(1) + length(4) + body
        // Skip type byte(1) + length field(4) = 5 bytes to get to body
        val bodyBytes = bytes.drop(5)
        val reader    = PostgresBufferReader(bodyBytes)

        val stmtName = reader.readString()
        val sql      = reader.readString()
        Abort.run[SqlException.Decode](reader.readInt16()).flatMap {
            case Result.Success(numParamsShort) =>
                val numParams = numParamsShort.toInt & 0xffff
                readOids(reader, numParams, Chunk.empty).map { oids =>
                    assert(stmtName == original.stmtName)
                    assert(sql == original.sql)
                    assert(oids.size == original.paramTypes.size)
                    assert(oids(0) == original.paramTypes(0))
                }
            case other => fail(s"readInt16 failed: $other")
        }
    }

    private def readOids(reader: PostgresBufferReader, remaining: Int, acc: Chunk[Int])(using
        Frame
    ): Chunk[Int] < Abort[SqlException.Decode] =
        if remaining == 0 then acc
        else reader.readInt32().flatMap { oid => readOids(reader, remaining - 1, acc.appended(oid)) }

end RoundTripTest
