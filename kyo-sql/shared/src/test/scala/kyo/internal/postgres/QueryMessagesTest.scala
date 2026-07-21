package kyo.internal.postgres

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.Chunk
import kyo.Maybe
import kyo.Span
import kyo.SqlException
import kyo.Test
import kyo.internal.postgres.Parse
import kyo.internal.postgres.marshaller.BindMarshaller
import kyo.internal.postgres.marshaller.ExecuteMarshaller
import kyo.internal.postgres.marshaller.ParseMarshaller
import kyo.internal.postgres.marshaller.PasswordMessageMarshaller
import kyo.internal.postgres.marshaller.QueryMarshaller
import kyo.internal.postgres.marshaller.SASLInitialResponseMarshaller

/** Tests for query-related Frontend messages: Query, Parse, Bind, Execute, PasswordMessage, SASLInitialResponse.
  *
  * Byte layouts per PostgreSQL §55.7 "Message Formats".
  */
class QueryMessagesTest extends Test:

    "Query marshaller prefixes 'Q' type byte" in {
        val buf = new PostgresBufferWriter
        QueryMarshaller.write(Query("SELECT 1"), buf)
        val span = buf.toSpan
        assert(span(0) == 'Q'.toByte)
    }

    "Query marshaller encodes length including itself" in {
        val sql = "SELECT 1"
        val buf = new PostgresBufferWriter
        QueryMarshaller.write(Query(sql), buf)
        val span = buf.toSpan
        // length field = 4 (length itself) + len(sql utf-8) + 1 (NUL)
        val sqlBytes    = sql.getBytes(StandardCharsets.UTF_8)
        val expectedLen = 4 + sqlBytes.length + 1
        val encodedLen  = ((span(1) & 0xff) << 24) | ((span(2) & 0xff) << 16) | ((span(3) & 0xff) << 8) | (span(4) & 0xff)
        assert(encodedLen == expectedLen)
        // Total span size = 1 (type) + 4 (len) + sql + 1 (NUL)
        assert(span.size == 1 + expectedLen)
    }

    "Parse marshaller writes statement name, sql, OID count" in {
        val msg = Parse("s1", "SELECT $1", Chunk(23))
        val buf = new PostgresBufferWriter
        ParseMarshaller.write(msg, buf)
        val bytes = buf.toSpan.toArray

        // type byte
        assert(bytes(0) == 'P'.toByte)

        // decode the body to verify fields
        // skip type(1) + length(4) = 5 bytes
        val reader = PostgresBufferReader(bytes.drop(5))
        assert(reader.readString() == "s1")
        assert(reader.readString() == "SELECT $1")
        Abort.run[SqlException.Decode](reader.readInt16()).flatMap {
            case Result.Success(numParams) =>
                assert(numParams == 1.toShort)
                Abort.run[SqlException.Decode](reader.readInt32()).map {
                    case Result.Success(oid) => assert(oid == 23)
                    case other               => fail(s"readInt32 failed: $other")
                }
            case other => fail(s"readInt16 failed: $other")
        }
    }

    "Bind marshaller encodes parameter format codes" in {
        val msg = Bind(
            "",
            "s1",
            Chunk(0.toShort, 1.toShort), // text, binary
            Chunk(Maybe.Present(Span.from(Array[Byte]('a')))),
            Chunk.empty
        )
        val buf = new PostgresBufferWriter
        BindMarshaller.write(msg, buf)
        val bytes = buf.toSpan.toArray
        // type byte
        assert(bytes(0) == 'B'.toByte)
        // Skip type(1)+len(4)+cstring("")+cstring("s1") to reach format codes
        // "" = 1 byte (just NUL), "s1" = 3 bytes (s,1,NUL) => header = 5+1+3 = 9 bytes
        val afterHeader = 5 + 1 + 3 // type+len=5, portal=1 byte (NUL), stmt=3 bytes
        val reader      = PostgresBufferReader(bytes.drop(afterHeader))
        Abort.run[SqlException.Decode](reader.readInt16()).flatMap {
            case Result.Success(numFmts) =>
                assert(numFmts == 2.toShort)
                Abort.run[SqlException.Decode](reader.readInt16()).flatMap {
                    case Result.Success(fmt0) =>
                        assert(fmt0 == 0.toShort) // text
                        Abort.run[SqlException.Decode](reader.readInt16()).map {
                            case Result.Success(fmt1) => assert(fmt1 == 1.toShort) // binary
                            case other                => fail(s"readInt16 fmt1 failed: $other")
                        }
                    case other => fail(s"readInt16 fmt0 failed: $other")
                }
            case other => fail(s"readInt16 numFmts failed: $other")
        }
    }

    "Bind marshaller encodes null parameter as -1 Int32" in {
        val msg = Bind("", "", Chunk.empty, Chunk(Maybe.Absent), Chunk.empty)
        val buf = new PostgresBufferWriter
        BindMarshaller.write(msg, buf)
        val bytes = buf.toSpan.toArray
        // type(1)+len(4)+portal""\0(1)+stmt""\0(1)+numFmts(2)+numParams(2) = 11
        val paramStart = 11
        val reader     = PostgresBufferReader(bytes.drop(paramStart))
        Abort.run[SqlException.Decode](reader.readInt32()).map {
            case Result.Success(colLen) => assert(colLen == -1)
            case other                  => fail(s"readInt32 failed: $other")
        }
    }

    "Execute marshaller encodes maxRows=0" in {
        val buf = new PostgresBufferWriter
        ExecuteMarshaller.write(Execute("", 0), buf)
        val bytes = buf.toSpan.toArray
        // type(1)+len(4)+portal""\0(1) = 6 bytes before maxRows
        val reader = PostgresBufferReader(bytes.drop(6))
        Abort.run[SqlException.Decode](reader.readInt32()).map {
            case Result.Success(v) => assert(v == 0)
            case other             => fail(s"readInt32 failed: $other")
        }
    }

    "Execute marshaller encodes maxRows=100" in {
        val buf = new PostgresBufferWriter
        ExecuteMarshaller.write(Execute("", 100), buf)
        val bytes = buf.toSpan.toArray
        // type(1)+len(4)+portal""\0(1) = 6 bytes before maxRows
        val reader = PostgresBufferReader(bytes.drop(6))
        Abort.run[SqlException.Decode](reader.readInt32()).map {
            case Result.Success(v) => assert(v == 100)
            case other             => fail(s"readInt32 failed: $other")
        }
    }

    "PasswordMessage marshaller writes 'p' type byte" in {
        val buf = new PostgresBufferWriter
        PasswordMessageMarshaller.write(PasswordMessage("secret"), buf)
        val span = buf.toSpan
        assert(span(0) == 'p'.toByte)
    }

    "SASLInitialResponse marshaller writes mechanism name NUL-terminated" in {
        val cfm = Span.from(Array[Byte]('c', 'l', 'i', 'e', 'n', 't'))
        val msg = SASLInitialResponse("SCRAM-SHA-256", cfm)
        val buf = new PostgresBufferWriter
        SASLInitialResponseMarshaller.write(msg, buf)
        val bytes = buf.toSpan.toArray
        // type(1)+len(4) = 5; then the mechanism cstring starts
        val mechStart = 5
        val mechBytes = "SCRAM-SHA-256".getBytes(StandardCharsets.UTF_8)
        assert(mechBytes.indices.forall(i => bytes(mechStart + i) == mechBytes(i)))
        // NUL after mechanism name
        assert(bytes(mechStart + mechBytes.length) == 0.toByte)
    }

end QueryMessagesTest
