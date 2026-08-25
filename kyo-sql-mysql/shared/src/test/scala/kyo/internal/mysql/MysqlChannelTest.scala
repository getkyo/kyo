package kyo.internal.mysql

import kyo.*
import kyo.SqlException
import kyo.Test
import kyo.net.StubConnection

/** Unit tests for [[MysqlChannel]] atomic-state operations and seqId invariants.
  *
  * Tests run against a stub [[kyo.net.Connection]] because they exercise only the in-memory atomic fields ([[_corrupted]],
  * [[_cleanupLatch]]) and the single-fiber seqId state, not the actual wire protocol.
  */
class MysqlChannelTest extends Test:

    "markCorrupted then readRawPayload raises SqlConnectionProtocolCorruptedException" in {
        MysqlChannel(StubConnection()).flatMap { channel =>
            channel.markCorrupted("LOAD DATA LOCAL INFILE").flatMap { _ =>
                // readRawPayload calls checkCorrupted() first; after markCorrupted it should abort
                // immediately with SqlConnectionProtocolCorruptedException before touching the stub's inbound.
                Abort.run[SqlException](channel.readRawPayload).map {
                    case Result.Failure(e: SqlConnectionProtocolCorruptedException) =>
                        assert(e.operation == "LOAD DATA LOCAL INFILE")
                    case other =>
                        fail(s"Expected SqlConnectionProtocolCorruptedException, got: $other")
                }
            }
        }
    }

    "resetSeq, setSeq, advanceSeq produce the expected seqId sequence" in {
        MysqlChannel(StubConnection()).map { channel =>
            // Initial state: seqId == 0.
            assert(channel.currentSeq == 0)

            // setSeq: set to an arbitrary value.
            channel.setSeq(42)
            assert(channel.currentSeq == 42)

            // advanceSeq: advance by 5.
            channel.advanceSeq(5)
            assert(channel.currentSeq == 47)

            // resetSeq: back to 0.
            channel.resetSeq()
            assert(channel.currentSeq == 0)

            // Wrap-around: seqId is modulo 256.
            channel.setSeq(250)
            channel.advanceSeq(10)
            // (250 + 10) & 0xff == 4
            assert(channel.currentSeq == 4)
        }
    }

    // The socketTimeout bound must cover a whole logical payload, not each chunk take. A payload reassembles from as
    // many chunks as the server sends (up to 16 MB), so a per-chunk budget would let a server trickling one chunk per
    // sub-bound interval evade the timeout for any number of chunks. The leaf below drives exactly that shape.

    /** Puts one byte into `conn`'s inbound every 50ms, forever.
      *
      * One byte at a time never completes the 4-byte packet header, so the reassembly loop always needs more and the
      * read can only end by a bound placed over the whole loop. Each byte arrives well inside the 500ms budget, which
      * is what a per-chunk bound restarts on.
      */
    private def trickle(conn: kyo.net.Connection)(using Frame): Unit < (Async & Abort[Closed]) =
        Loop.forever {
            Async.sleep(50.millis).andThen(conn.inbound.safe.put(Span.from(Array[Byte](0x01.toByte))))
        }

    "the socketTimeout bounds a whole payload, not each chunk of it" in {
        val conn = StubConnection()
        MysqlChannel(conn, socketTimeout = 500.millis).flatMap { channel =>
            Fiber.initUnscoped(Abort.run[Closed](trickle(conn))).flatMap { feeder =>
                // The outer guard is set well above the inner budget so an unbounded read ends the leaf as a
                // diagnosis rather than hanging the suite: reaching it means no bound covered the loop.
                Abort.run[Timeout](
                    Async.timeout(10.seconds)(Abort.run[SqlException](channel.readRawPayload))
                ).flatMap { outcome =>
                    feeder.interrupt.map { _ =>
                        outcome match
                            case Result.Success(Result.Failure(e: SqlConnectionSocketTimeoutException)) =>
                                assert(
                                    e.socketTimeout == 500.millis,
                                    s"the abort must carry the configured bound, got ${e.socketTimeout}"
                                )
                            case Result.Failure(_: Timeout) =>
                                fail(
                                    "the socketTimeout restarted on every chunk: a server trickling bytes below the " +
                                        "bound was never timed out, and the outer 10s guard is what ended the read"
                                )
                            case other =>
                                fail(s"expected SqlConnectionSocketTimeoutException(500.millis), got: $other")
                    }
                }
            }
        }
    }

end MysqlChannelTest
