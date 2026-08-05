package kyo.internal.mysql

import kyo.*
import kyo.SqlException
import kyo.Test
import kyo.net.StubConnection

/** Unit tests for [[MysqlConnection]] over a stub [[kyo.net.Connection]], for the paths whose whole output is a message.
  *
  * A connection method that meets a packet it did not expect has nothing to return and nothing to retry, so the refusal's `actual` field is
  * the entire diagnosis the caller gets. That makes the rendering of the offending message a behaviour rather than a detail, and it is
  * reachable without a server: the packet is fed to the channel's own inbound buffer.
  */
class MysqlConnectionPingTest extends Test:

    /** One MySQL frame decoding to an old-style EOF packet: LE payload length 5, sequence id 1, then `0xFE` with `warnings = 1` and
      * `statusFlags = 2` as LE `uint16`s.
      *
      * EOF is what a `0xFE` payload shorter than 7 bytes decodes to outside the authentication context, which is the context `ping` reads
      * in. `readPayload` does not validate the sequence id, so its value is free.
      */
    private val eofFrame: Span[Byte] = Span.from(
        Array[Byte](0x05, 0x00, 0x00, 0x01, 0xfe.toByte, 0x01, 0x00, 0x02, 0x00)
    )

    /** A connection wired to `conn`, with the handshake-derived state a `ping` reads set to values it never inspects. */
    private def connect(conn: kyo.net.Connection)(using Frame): MysqlConnection < Sync =
        for
            channel    <- MysqlChannel(conn)
            connIdRef  <- AtomicRef.init(7L)
            capsRef    <- AtomicRef.init(0L)
            versionRef <- AtomicRef.init("8.0.0")
            charsetRef <- AtomicRef.init(45)
            statusRef  <- AtomicRef.init(0)
            closesRef  <- AtomicRef.init(Chunk.empty[Int])
            stmtCache  <- MysqlConnection.mkStmtCache(closesRef, 8, Duration.Zero)
        yield new MysqlConnection(channel, connIdRef, capsRef, versionRef, charsetRef, statusRef, stmtCache, closesRef)

    "ping answered with neither OK nor ERR reports the packet it got, not the packet's class name" in {
        // `getSimpleName` would render `EofPacket`, which names the shape and drops every field, so two different EOF packets produce the
        // same refusal. A case class `toString` is a compiled-in rendering of the values, so the reader gets the warning count and the
        // status flags that actually arrived. The same arm one command over, ResetConnectionExchange, already renders `other.toString`.
        val conn = StubConnection()
        connect(conn).flatMap { connection =>
            Abort.run[Closed](conn.inbound.safe.put(eofFrame)).andThen {
                Abort.run[SqlException](connection.ping()).map {
                    case Result.Failure(e: SqlConnectionUnexpectedMessageException) =>
                        assert(e.phase == "ping")
                        assert(e.expected == "OkPacket / ErrPacket")
                        assert(e.actual == "EofPacket(1,2)", s"the refusal must render the packet's own values, got '${e.actual}'")
                    case other =>
                        fail(s"a ping answered with an EOF packet must be refused as an unexpected message, got: $other")
                }
            }
        }
    }

end MysqlConnectionPingTest
