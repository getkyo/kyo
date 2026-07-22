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
            channel.markCorrupted().flatMap { _ =>
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

end MysqlChannelTest
