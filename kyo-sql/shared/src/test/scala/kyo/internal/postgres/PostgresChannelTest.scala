package kyo.internal.postgres

import kyo.*
import kyo.SqlException
import kyo.Test
import kyo.net.StubConnection

/** Unit tests for [[PostgresChannel]] atomic-state operations.
  *
  * Tests run against a stub [[kyo.net.Connection]] because they exercise only the in-memory atomic fields ([[_corrupted]],
  * [[_cleanupLatch]]), not the actual wire protocol.
  */
class PostgresChannelTest extends Test:

    "markCorrupted then receive raises SqlException.Connection with 'unusable'" in {
        PostgresChannel(StubConnection()).flatMap { channel =>
            channel.markCorrupted().flatMap { _ =>
                // receive calls checkCorrupted() first; after markCorrupted it should abort
                // immediately with SqlException.Connection before touching the stub's inbound.
                Abort.run[SqlException](channel.receive).map {
                    case Result.Failure(e: SqlException.Connection) =>
                        assert(e.message.contains("unusable"))
                    case other =>
                        fail(s"Expected SqlException.Connection 'unusable', got: $other")
                }
            }
        }
    }

    "beginCleanup installs a latch; endCleanup clears it; send succeeds after endCleanup" in {
        PostgresChannel(StubConnection()).flatMap { channel =>
            Latch.init(1).flatMap { latch =>
                // ── Part A: happy-path latch round-trip ───────────────────────
                // Install latch, release it, clear it via endCleanup, then send.
                // After endCleanup the channel must NOT be corrupted and checkCorrupted
                // must pass through so that send can write to the in-memory outbound.
                channel.beginCleanup(latch).flatMap { _ =>
                    latch.release.flatMap { _ =>
                        channel.endCleanup().flatMap { _ =>
                            Abort.run[SqlException](
                                channel.send(Terminate)(using channel.marshallers.terminate)
                            ).flatMap {
                                case Result.Success(_) =>
                                    // ── Part B: blocking semantics ─────────────────────────────
                                    // A fresh latch (count 1, NOT yet released) is installed.
                                    // A forked send must block inside checkCorrupted's latch.await
                                    // and must NOT complete until we release the latch.
                                    Latch.init(1).flatMap { blockingLatch =>
                                        channel.beginCleanup(blockingLatch).flatMap { _ =>
                                            Fiber.initUnscoped(
                                                Abort.run[SqlException](
                                                    channel.send(Terminate)(using channel.marshallers.terminate)
                                                )
                                            ).flatMap { sendFiber =>
                                                // Yield so the forked fiber can run and reach latch.await.
                                                Async.sleep(10.millis).flatMap { _ =>
                                                    sendFiber.done.flatMap { doneBeforeRelease =>
                                                        // Clear the latch ref so checkCorrupted proceeds after await.
                                                        channel.endCleanup().flatMap { _ =>
                                                            // Release the latch, the blocked send should now complete.
                                                            blockingLatch.release.flatMap { _ =>
                                                                sendFiber.get.map { result =>
                                                                    assert(
                                                                        !doneBeforeRelease,
                                                                        "send must be blocked (not done) while latch is held"
                                                                    )
                                                                    assert(
                                                                        result == Result.Success(()),
                                                                        s"send must succeed after latch release, got: $result"
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                case other =>
                                    fail(s"send after endCleanup must succeed, got: $other")
                            }
                        }
                    }
                }
            }
        }
    }

end PostgresChannelTest
