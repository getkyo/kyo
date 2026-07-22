package kyo.internal.postgres

import java.util.concurrent.CopyOnWriteArrayList
import kyo.*
import kyo.SqlException
import kyo.Test
import kyo.internal.postgres.unmarshaller.Unmarshallers
import kyo.net.Connection
import kyo.net.NetPlatform
import kyo.net.StubConnection

// Helper: thread-safe synchronous log sink for capturing log entries in tests.
private class MessageReaderTestLogSink extends Log.Unsafe:
    private val entries                                                        = new CopyOnWriteArrayList[(Log.Level, String)]()
    def name: String                                                           = "MessageReaderTestLogSink"
    def withName(name: String): Log.Unsafe                                     = this
    def level: Log.Level                                                       = Log.Level.trace
    def trace(msg: => String)(using Frame, AllowUnsafe): Unit                  = discard(entries.add((Log.Level.trace, msg.toString)))
    def trace(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = discard(entries.add((Log.Level.trace, msg.toString)))
    def debug(msg: => String)(using Frame, AllowUnsafe): Unit                  = discard(entries.add((Log.Level.debug, msg.toString)))
    def debug(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = discard(entries.add((Log.Level.debug, msg.toString)))
    def info(msg: => String)(using Frame, AllowUnsafe): Unit                   = discard(entries.add((Log.Level.info, msg.toString)))
    def info(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit  = discard(entries.add((Log.Level.info, msg.toString)))
    def warn(msg: => String)(using Frame, AllowUnsafe): Unit                   = discard(entries.add((Log.Level.warn, msg.toString)))
    def warn(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit  = discard(entries.add((Log.Level.warn, msg.toString)))
    def error(msg: => String)(using Frame, AllowUnsafe): Unit                  = discard(entries.add((Log.Level.error, msg.toString)))
    def error(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = discard(entries.add((Log.Level.error, msg.toString)))
    def captured: Chunk[(Log.Level, String)] = Chunk.from(entries.toArray(Array.empty[(Log.Level, String)]))
end MessageReaderTestLogSink

/** Unit tests for [[MessageReader]], message decoding paths including error recovery.
  *
  * Tests use controlled byte payloads delivered via in-process [[StubConnection]] to trigger specific code paths inside [[MessageReader]].
  */
class MessageReaderTest extends Test:

    /** Verifies that [[MessageReader.readOne]] correctly decodes a valid [[ReadyForQuery]] frame.
      *
      * A well-formed Postgres `'Z'` (ReadyForQuery) frame with a 1-byte status body (`'I'` = idle) is placed into the [[StubConnection]]'s
      * inbound channel. [[MessageReader.readOne]] must successfully parse it into a [[ReadyForQuery]] message.
      *
      * This test exercises the full [[MessageReader]] code path through [[pullUntilComplete]] → [[decodeMessage]] → unmarshaller → result.
      */
    "MessageReader readOne decodes ReadyForQuery from inbound channel" in {
        val conn = StubConnection()
        // Postgres wire frame: type='Z' (ReadyForQuery), length=5 (4-byte length field + 1-byte body), status='I'.
        val frame = Span.from(Array[Byte](
            'Z'.toByte, // type byte
            0x00,
            0x00,
            0x00,
            0x05,      // Int32(5), length includes itself, body = 1 byte
            'I'.toByte // status: 'I' = idle
        ))
        val reader        = new MessageReader()
        val unmarshallers = Unmarshallers.default
        Abort.run[Closed](conn.inbound.safe.put(frame)).flatMap { _ =>
            Abort.run[SqlException](reader.readOne(conn, unmarshallers)).map {
                case Result.Success(ReadyForQuery('I')) => succeed
                case other                              => fail(s"Expected ReadyForQuery('I'), got: $other")
            }
        }
    }

    /** Verifies that [[MessageReader.readOne]] correctly decodes an [[Authentication]] (AuthOk) frame.
      *
      * A well-formed `'R'` frame with a 4-byte body containing the AuthOk sub-type code (0x00000000) is decoded to
      * [[Authentication]]([[AuthenticationKind.Ok]]).
      */
    "MessageReader readOne decodes AuthenticationOk from inbound channel" in {
        val conn = StubConnection()
        // Postgres wire frame: type='R' (Authentication), length=8, sub-type=0 (Ok).
        val frame = Span.from(Array[Byte](
            'R'.toByte, // type byte
            0x00,
            0x00,
            0x00,
            0x08, // Int32(8), length field (4 bytes) + body (4 bytes)
            0x00,
            0x00,
            0x00,
            0x00 // AuthOk sub-type = 0
        ))
        val reader        = new MessageReader()
        val unmarshallers = Unmarshallers.default
        Abort.run[Closed](conn.inbound.safe.put(frame)).flatMap { _ =>
            Abort.run[SqlException](reader.readOne(conn, unmarshallers)).map {
                case Result.Success(Authentication(AuthenticationKind.Ok)) => succeed
                case other                                                 => fail(s"Expected Authentication(Ok), got: $other")
            }
        }
    }

    /** Verifies that [[MessageReader.readOne]] reassembles a message split across two inbound chunks.
      *
      * The 5-byte header is delivered in one chunk, the 1-byte body in a second chunk. [[MessageReader]] must suspend (blocking on the
      * inbound channel) until both chunks arrive, then reassemble and decode.
      */
    "MessageReader readOne reassembles split-chunk ReadyForQuery" in {
        val conn          = StubConnection()
        val reader        = new MessageReader()
        val unmarshallers = Unmarshallers.default
        // Deliver header and body as two separate chunks.
        val header = Span.from(Array[Byte]('Z'.toByte, 0x00, 0x00, 0x00, 0x05))
        val body   = Span.from(Array[Byte]('I'.toByte))
        Abort.run[Closed](conn.inbound.safe.put(header)).flatMap { _ =>
            Abort.run[Closed](conn.inbound.safe.put(body)).flatMap { _ =>
                Abort.run[SqlException](reader.readOne(conn, unmarshallers)).map {
                    case Result.Success(ReadyForQuery('I')) => succeed
                    case other                              => fail(s"Expected ReadyForQuery('I') from split chunks, got: $other")
                }
            }
        }
    }

    /** Verifies that [[MessageReader.readOne]] fails with [[SqlConnectionException]] when the inbound channel is closed mid-read.
      *
      * A partial frame (header only, no body) is delivered, then the inbound channel is closed. [[MessageReader]] must detect the closure
      * and fail with [[SqlConnectionException]] rather than hanging indefinitely.
      */
    "MessageReader readOne fails with SqlConnectionException when channel closes mid-read" in {
        val conn          = StubConnection()
        val reader        = new MessageReader()
        val unmarshallers = Unmarshallers.default
        // Deliver only the 5-byte header (ReadyForQuery with a 1-byte body), then close the channel.
        val header = Span.from(Array[Byte]('Z'.toByte, 0x00, 0x00, 0x00, 0x05))
        Abort.run[Closed](conn.inbound.safe.put(header)).flatMap { _ =>
            // Close the inbound channel so the next take returns Abort[Closed].
            Sync.Unsafe.defer(conn.close()).flatMap { _ =>
                Abort.run[SqlException](reader.readOne(conn, unmarshallers)).map {
                    case Result.Failure(_: SqlConnectionException) => succeed
                    case other => fail(s"Expected SqlConnectionException on closed channel, got: $other")
                }
            }
        }
    }

    /** Verifies that a malformed BackendKeyData frame (body too short for the declared type) produces a [[SqlConnectionException]] rather
      * than an unhandled panic.
      *
      * A `'K'` (BackendKeyData) frame normally has an 8-byte body (processId + secretKey = 4+4 bytes). This test delivers a frame whose
      * length field claims only 6 bytes (i.e., 4-byte length field + 2-byte body). The [[MessageReader]] treats this as a complete message
      * and delivers the 2-byte body to [[BackendKeyDataUnmarshaller]], which calls `readInt32()` but has only 2 bytes available.
      *
      * With the effectful [[PostgresBufferReader]] API, `readInt32()` on the under-length buffer returns
      * `Abort.fail(SqlDecodeException(...))` instead of throwing. [[decodeMessage]] catches this as `Result.Failure` and converts it to
      * `SqlConnectionException`.
      *
      * Before the fix, `readInt32()` threw `ArrayIndexOutOfBoundsException` eagerly outside `Abort.run[SqlDecodeException]`'s try-block,
      * bypassing the `Result.Failure` arm entirely and escaping as an unhandled panic.
      */
    "MessageReader decodeMessage converts short-read body to SqlConnectionException" in {
        val conn = StubConnection()
        // BackendKeyData ('K') frame: type byte + Int32(length=6) + 2-byte body.
        // Length=6 means: 4-byte length field + 2-byte body = 6. Total frame = 7 bytes.
        // BackendKeyDataUnmarshaller expects 8 body bytes (pid Int32 + secret Int32),
        // so readInt32() on a 2-byte buffer returns Abort.fail(SqlDecodeException(...)).
        val frame = Span.from(Array[Byte](
            'K'.toByte, // type byte
            0x00,
            0x00,
            0x00,
            0x06, // Int32(6) = 4-byte length + 2-byte body (deliberately undersized)
            0x00,
            0x01 // 2 bytes of body (BackendKeyDataUnmarshaller needs 8)
        ))
        val reader        = new MessageReader()
        val unmarshallers = Unmarshallers.default
        Abort.run[Closed](conn.inbound.safe.put(frame)).flatMap { _ =>
            Abort.run[SqlException](reader.readOne(conn, unmarshallers)).map {
                case Result.Failure(_: SqlConnectionException) => succeed
                case Result.Panic(t) =>
                    fail(s"Expected SqlConnectionException but got panic: ${t.getMessage}")
                case other =>
                    fail(s"Expected SqlConnectionException, got: $other")
            }
        }
    }

    /** Verifies that [[MessageReader.onTakePanic]] logs an error message and returns a [[SqlConnectionException]] with the expected
      * message.
      *
      * Calls the real production helper directly with a synthetic [[Throwable]]. A capturing [[Log]] is installed via [[Log.let]] so the
      * assertion can confirm the error was logged, not just swallowed.
      */
    "MessageReader onTakePanic logs error and returns SqlConnectionException with throwable message" in {
        val sink   = new MessageReaderTestLogSink
        val cause  = new RuntimeException("boom from test")
        val reader = new MessageReader()
        Log.let(Log(sink)) {
            reader.onTakePanic(cause).map { exc =>
                Log.flush.andThen {
                    val entries = sink.captured
                    assert(entries.size == 1, s"expected exactly 1 log entry, got: $entries")
                    val (level, msg) = entries(0)
                    assert(level == Log.Level.error, s"expected error level, got: $level")
                    assert(msg.contains("boom from test"), s"log message should contain throwable message: $msg")
                    assert(msg.contains("[kyo-sql] MessageReader"), s"log message should contain module prefix: $msg")
                    assert(
                        exc.message.contains("boom from test"),
                        s"SqlConnectionException message should contain throwable: ${exc.message}"
                    )
                }
            }
        }
    }

    /** Verifies that [[MessageReader.readOne]] correctly accumulates bytes from three chunks and produces byte-identical output to a
      * single-chunk delivery of the same frame.
      *
      * A ReadyForQuery frame split across three separate chunks: (type), (length bytes 1-4), (body). The accumulation path (pending buffer
      * concatenation) must produce the same decoded message.
      */
    "MessageReader accumulates three-chunk frame byte-identically" in {
        val conn          = StubConnection()
        val reader        = new MessageReader()
        val unmarshallers = Unmarshallers.default
        // Split the ReadyForQuery frame into 3 chunks: type, length, body
        val chunk1 = Span.from(Array[Byte]('Z'.toByte))             // type byte
        val chunk2 = Span.from(Array[Byte](0x00, 0x00, 0x00, 0x05)) // Int32(5)
        val chunk3 = Span.from(Array[Byte]('I'.toByte))             // body: idle status
        Abort.run[Closed](conn.inbound.safe.put(chunk1)).flatMap { _ =>
            Abort.run[Closed](conn.inbound.safe.put(chunk2)).flatMap { _ =>
                Abort.run[Closed](conn.inbound.safe.put(chunk3)).flatMap { _ =>
                    Abort.run[SqlException](reader.readOne(conn, unmarshallers)).map {
                        case Result.Success(ReadyForQuery('I')) => succeed
                        case other => fail(s"Expected ReadyForQuery('I') from 3-chunk accumulation, got: $other")
                    }
                }
            }
        }
    }

end MessageReaderTest
