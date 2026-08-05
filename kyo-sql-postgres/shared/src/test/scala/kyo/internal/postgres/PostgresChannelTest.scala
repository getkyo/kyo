package kyo.internal.postgres

import kyo.*
import kyo.SqlException
import kyo.Test
import kyo.net.StubConnection

/** Unit tests for [[PostgresChannel]]'s atomic-state operations and its per-read bound.
  *
  * Tests run against a stub [[kyo.net.Connection]] rather than a server. The atomic-state leaves exercise the in-memory fields
  * ([[_corrupted]], [[_cleanup]]) and never reach the wire; the `socketTimeout` leaves need a read that never completes, and a stub whose
  * inbound channel is simply never fed is exactly that, deterministically and with no socket.
  */
class PostgresChannelTest extends Test:

    /** A pending cleanup whose abort action does nothing, for leaves that resolve it by hand. */
    private def freshPending(using Frame): PostgresChannel.PendingCopyCleanup < Sync =
        Latch.init(1).flatMap { latch =>
            AtomicBoolean.init(false).map { resolved =>
                PostgresChannel.PendingCopyCleanup(latch, resolved, ())
            }
        }

    "markCorrupted then receive raises SqlConnectionProtocolCorruptedException" in {
        PostgresChannel(StubConnection()).flatMap { channel =>
            channel.markCorrupted().flatMap { _ =>
                // receive calls checkCorrupted() first; after markCorrupted it should abort
                // immediately with SqlConnectionProtocolCorruptedException before touching the stub's inbound.
                Abort.run[SqlException](channel.receive).map {
                    case Result.Failure(e: SqlConnectionProtocolCorruptedException) =>
                        assert(e.operation == "COPY")
                    case other =>
                        fail(s"Expected SqlConnectionProtocolCorruptedException, got: $other")
                }
            }
        }
    }

    "beginCleanup installs a pending cleanup; endCleanup clears it; send succeeds after endCleanup" in {
        PostgresChannel(StubConnection()).flatMap { channel =>
            freshPending.flatMap { pending =>
                // Happy-path round-trip: install, resolve by hand the way the exchange's barrier resolution
                // does (release the latch, clear the registration), then send. After endCleanup the channel
                // must NOT be corrupted and checkCorrupted must pass through so that send can write to the
                // in-memory outbound.
                channel.beginCleanup(pending).flatMap { _ =>
                    pending.latch.release.flatMap { _ =>
                        channel.endCleanup().flatMap { _ =>
                            Abort.run[SqlException](
                                channel.send(Terminate)(using channel.marshallers.terminate)
                            ).map { result =>
                                assert(result == Result.Success(()), s"send after endCleanup must succeed, got: $result")
                            }
                        }
                    }
                }
            }
        }
    }

    "a claimed pending cleanup blocks a send until the cleanup resolves" in {
        PostgresChannel(StubConnection()).flatMap { channel =>
            freshPending.flatMap { pending =>
                // Simulate another party mid-cleanup: it already holds the claim, so a writer must wait for
                // that party rather than run the cleanup a second time over the same wire.
                pending.claim.flatMap { claimed =>
                    channel.beginCleanup(pending).flatMap { _ =>
                        Fiber.initUnscoped(
                            Abort.run[SqlException](
                                channel.send(Terminate)(using channel.marshallers.terminate)
                            )
                        ).flatMap { sendFiber =>
                            // Wait deterministically until the forked send has parked on the cleanup latch, observed
                            // through the channel's parked-writer gauge, instead of guessing with a sleep that cannot
                            // prove the send reached its blocking point.
                            assertEventually(channel.pendingCleanupWaiters.map(_ > 0)).flatMap { _ =>
                                sendFiber.done.flatMap { doneBeforeRelease =>
                                    // Resolve the cleanup the way the real claimant does: clear the
                                    // registration, then release the latch the loser waits on.
                                    channel.endCleanup().flatMap { _ =>
                                        pending.latch.release.flatMap { _ =>
                                            sendFiber.get.map { result =>
                                                assert(claimed, "the leaf's own claim must be the winning one")
                                                assert(
                                                    !doneBeforeRelease,
                                                    "send must be blocked while the claimant is still cleaning"
                                                )
                                                assert(
                                                    result == Result.Success(()),
                                                    s"send must succeed once the cleanup resolves, got: $result"
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "an unclaimed pending cleanup is run by the next writer, which then proceeds" in {
        PostgresChannel(StubConnection()).flatMap { channel =>
            AtomicBoolean.init(false).flatMap { ran =>
                Latch.init(1).flatMap { latch =>
                    AtomicBoolean.init(false).flatMap { resolved =>
                        // The abort action stands in for the CopyFail-and-drain: it records that it ran and
                        // resolves the channel the way the real cleanup does. An unclaimed registration means
                        // the transfer was abandoned with its finalizer parked, and waiting for that finalizer
                        // is the deadlock this behavior exists to prevent: the writer must claim, clean, and
                        // proceed on its own.
                        val abort: Unit < Async =
                            ran.set(true).andThen(channel.endCleanup()).andThen(latch.release)
                        val pending = PostgresChannel.PendingCopyCleanup(latch, resolved, abort)
                        channel.beginCleanup(pending).flatMap { _ =>
                            Abort.run[SqlException](
                                channel.send(Terminate)(using channel.marshallers.terminate)
                            ).flatMap { result =>
                                ran.get.map { didRun =>
                                    assert(didRun, "the writer must run the abandoned cleanup itself")
                                    assert(
                                        result == Result.Success(()),
                                        s"send must proceed after running the cleanup, got: $result"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // --- the per-read socketTimeout bound ---
    //
    // Every leaf below reads from a stub whose inbound is NEVER fed, so the read can only end by a bound.
    // Each wraps the channel call in an outer Async.timeout well above the inner budget, because a read with
    // no bound would otherwise hang the suite, and a hung leaf proves nothing. The outer guard firing is
    // therefore itself a diagnosis: it means no inner bound applied.

    "receiveSkipCheck applies the socketTimeout, so a stalled COPY read cannot wait forever" in {
        PostgresChannel(StubConnection(), socketTimeout = 1.second).flatMap { channel =>
            Abort.run[Timeout](
                Async.timeout(5.seconds)(Abort.run[SqlException](channel.receiveSkipCheck))
            ).map {
                case Result.Success(Result.Failure(e: SqlConnectionSocketTimeoutException)) =>
                    assert(e.socketTimeout == 1.second, s"the abort must carry the configured bound, got ${e.socketTimeout}")
                case Result.Failure(_: Timeout) =>
                    fail(
                        "receiveSkipCheck applied no bound: the outer 5s guard fired, so a COPY read on a " +
                            "stalled server would have waited forever"
                    )
                case other =>
                    fail(s"expected SqlConnectionSocketTimeoutException(1.second), got: $other")
            }
        }
    }

    "receive still applies the socketTimeout, the path that already worked" in {
        PostgresChannel(StubConnection(), socketTimeout = 1.second).flatMap { channel =>
            // A regression guard rather than a discriminator: this held before the bound was shared, and the
            // point is that funnelling both readers through one helper did not cost the one that was right.
            Abort.run[Timeout](
                Async.timeout(5.seconds)(Abort.run[SqlException](channel.receive))
            ).map {
                case Result.Success(Result.Failure(e: SqlConnectionSocketTimeoutException)) =>
                    assert(e.socketTimeout == 1.second, s"the abort must carry the configured bound, got ${e.socketTimeout}")
                case Result.Failure(_: Timeout) =>
                    fail("receive lost its bound: the outer 5s guard fired")
                case other =>
                    fail(s"expected SqlConnectionSocketTimeoutException(1.second), got: $other")
            }
        }
    }

    "receiveSkipCheckIfAvailable takes no bound because it cannot wait, and answers Absent at once" in {
        PostgresChannel(StubConnection(), socketTimeout = 1.second).flatMap { channel =>
            // The absence of a bound here is a decision, not an oversight: the poll returns whatever has
            // already arrived, so it can never sit waiting and a timeout could never fire. This pins the
            // behaviour that makes that reasoning true, so nobody later "fixes" the missing bound.
            Abort.run[Timeout](
                Async.timeout(5.seconds)(Abort.run[SqlException](channel.receiveSkipCheckIfAvailable))
            ).map {
                case Result.Success(Result.Success(Maybe.Absent)) => succeed
                case other => fail(s"the poll must answer Absent without waiting for the server, got: $other")
            }
        }
    }

    "clearSocketTimeout removes the bound, which is the listener's escape" in {
        PostgresChannel(StubConnection(), socketTimeout = 200.millis).flatMap { channel =>
            channel.clearSocketTimeout.flatMap { _ =>
                // The dedicated notification listener waits for a message that may not come for hours, so its
                // escape has to survive the bound moving to a shared helper. With the bound cleared the read
                // must outlive the 200ms it would otherwise have died at; the outer guard is what ends the
                // leaf, and reaching it is the pass condition.
                Abort.run[Timeout](
                    Async.timeout(1.second)(Abort.run[SqlException](channel.receiveSkipCheck))
                ).map {
                    case Result.Failure(_: Timeout) => succeed
                    case other =>
                        fail(s"a cleared bound must not cut a read off; expected the outer guard to fire, got: $other")
                }
            }
        }
    }

end PostgresChannelTest
