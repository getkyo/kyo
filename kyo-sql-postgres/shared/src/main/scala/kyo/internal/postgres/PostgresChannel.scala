package kyo.internal.postgres

import kyo.*
import kyo.SqlConnectionClosedException
import kyo.SqlConnectionException
import kyo.SqlConnectionProtocolCorruptedException
import kyo.SqlConnectionWritePanicException
import kyo.SqlException
import kyo.internal.postgres.marshaller.Marshallers
import kyo.internal.postgres.unmarshaller.Unmarshallers
import kyo.net.Connection

/** Thin wrapper over [[kyo.net.Connection]] that adds typed send/receive for Postgres protocol messages.
  *
  * [[send]] serialises a [[FrontendMessage]] to bytes via the appropriate [[Marshaller]] and writes it to the connection. [[receive]] reads
  * one complete [[BackendMessage]] from the connection via [[MessageReader]].
  *
  * This class carries no protocol state; all stateful logic lives in [[PostgresConnection]] and the exchange functions.
  *
  * ==COPY cleanup race protection==
  *
  * Mirrors the [[kyo.internal.mysql.MysqlChannel]] cleanup-latch pattern, extended with an exactly-once claim. A COPY registers a
  * [[PostgresChannel.PendingCopyCleanup]] for the duration of the transfer via [[beginCleanup]]; the exchange resolves it at the
  * ReadyForQuery barrier on its own paths, and a [[send]]/[[receive]] that arrives while it is still pending either runs the abort
  * cleanup itself (claim won: the transfer was abandoned, and its finalizer may be parked until an enclosing scope closes) or waits for
  * the party already running it (claim lost). Either way the caller sees a clean connection or "unusable", never stale protocol bytes,
  * and never an unbounded wait on a cleanup nobody has started.
  */
final class PostgresChannel(
    val conn: Connection,
    val marshallers: Marshallers,
    val unmarshallers: Unmarshallers,
    val reader: MessageReader,
    private val _socketTimeout: AtomicRef[Duration],
    private val _corrupted: AtomicBoolean,
    private val _cleanup: AtomicRef[Maybe[PostgresChannel.PendingCopyCleanup]],
    private val _pendingCleanupWaiters: AtomicInt
):

    /** Serialises `msg` using the provided [[Marshaller]] and writes the resulting bytes to the connection. */
    def send[T <: FrontendMessage](msg: T)(using m: Marshaller[T])(using Frame): Unit < (Async & Abort[SqlException]) =
        checkCorrupted().andThen {
            val buf = new PostgresBufferWriter
            m.write(msg, buf)
            writeBytes(buf.toSpan, "")
        }
    end send

    /** Reads the next [[BackendMessage]] from the connection, bounded by `socketTimeout` when the caller set one.
      *
      * Every path that can WAIT for bytes applies the bound, and [[bounded]] is what makes that true by construction rather than by each
      * reader remembering. The bound is opt-in and [[Duration.Infinity]] by default, because it cuts off a wait for bytes rather than a
      * statement: a server legitimately takes as long as the query takes to answer, so a bound short enough to catch a stalled connection is
      * also short enough to kill a slow query. A caller that asks for one is choosing that trade.
      */
    def receive(using Frame): BackendMessage < (Async & Abort[SqlException]) =
        checkCorrupted().andThen(bounded(reader.readOne(conn, unmarshallers)))

    /** Marks the channel as corrupted after a failed COPY cleanup attempt.
      *
      * Once corrupted, all subsequent send/receive operations on this channel fail immediately with a [[SqlConnectionException]]. Called by
      * [[exchange.CopyExchange]] when the error-path cleanup itself fails.
      */
    private[postgres] def markCorrupted()(using Frame): Unit < Sync = _corrupted.set(true)

    /** Registers a pending COPY cleanup that gates subsequent channel operations until it is resolved.
      *
      * Called by [[exchange.CopyExchange]] at the very start of a COPY operation. Ensures callers see either a clean channel or "unusable"
      * never stale protocol bytes from an in-flight cleanup.
      */
    private[postgres] def beginCleanup(cleanup: PostgresChannel.PendingCopyCleanup)(using Frame): Unit < Sync =
        _cleanup.set(Maybe.Present(cleanup))

    /** Clears the pending cleanup, signalling that no COPY resolution is owed on this channel.
      *
      * Must be called (together with releasing the cleanup's latch) by whichever party resolves the cleanup. Always pair with
      * [[beginCleanup]].
      */
    private[postgres] def endCleanup()(using Frame): Unit < Sync = _cleanup.set(Maybe.Absent)

    /** Removes the per-read bound for this connection's remaining lifetime.
      *
      * For the dedicated notification listener only: its whole job is to wait for a message that may not come for hours, so a read budget
      * would close a healthy subscription rather than bound a stall. Called once, before the listener's first read, while the connection
      * still has a single owner.
      */
    private[postgres] def clearSocketTimeout(using Frame): Unit < Sync = _socketTimeout.set(Duration.Infinity)

    /** Writers currently parked on another party's pending COPY cleanup latch.
      *
      * A read-only, test-visible gauge that exists so a test can observe deterministically that a [[send]]/[[receive]]
      * has reached the claim-lost wait, instead of guessing with a fixed sleep. It carries no protocol state and gates
      * no behavior.
      */
    private[postgres] def pendingCleanupWaiters(using Frame): Int < Sync = _pendingCleanupWaiters.get

    /** Reads the next [[BackendMessage]] without checking the corruption/cleanup guard, still bounded by `socketTimeout`.
      *
      * Used exclusively by [[exchange.CopyExchange]] on both the happy path and cleanup code. The pending cleanup is registered for the
      * duration of the COPY operation, so [[receive]] (which calls [[checkCorrupted]]) would claim the cleanup the exchange still owns and
      * cut across the live transfer. This variant skips that guard only, safe because [[exchange.CopyExchange]] is the sole reader during a
      * COPY op.
      *
      * It skips the CLEANUP guard, never the read bound: what a caller opts out of here is claiming its own pending cleanup, and a COPY has
      * no more claim than any other statement to wait forever on a server that has stopped answering.
      */
    private[postgres] def receiveSkipCheck(using Frame): BackendMessage < (Async & Abort[SqlException]) =
        bounded(reader.readOne(conn, unmarshallers))

    /** Reads the next [[BackendMessage]] only if one has already arrived, without checking the corruption/cleanup guard and without ever
      * waiting for the server.
      *
      * Used by [[exchange.CopyExchange]]'s `COPY FROM STDIN` pump, whose whole upload otherwise runs to completion before the first inbound
      * read can see that the server rejected the transfer. Delegates to [[MessageReader.readOneIfAvailable]] rather than polling [[conn]]
      * here, because the reader's buffer can already hold a whole message that no channel poll would see, and taking bytes off the channel
      * from outside the reader would strip the next read.
      *
      * This is the one reader that takes NO `socketTimeout` bound, by design: it answers with whatever has already arrived and returns
      * [[Absent]] otherwise, so it can never sit waiting on the server and a read budget has nothing to cut off. Do not route it through
      * [[bounded]]: the timeout could not fire, and wrapping it would fork a fiber per polled chunk on the upload's hot path.
      */
    private[postgres] def receiveSkipCheckIfAvailable(using Frame): Maybe[BackendMessage] < (Async & Abort[SqlException]) =
        reader.readOneIfAvailable(conn, unmarshallers)

    /** Serialises `msg` and writes it to the connection without checking the corruption/cleanup guard.
      *
      * Used exclusively by [[exchange.CopyExchange]] on the happy path. The pending cleanup is registered for the duration of the COPY
      * operation, so [[send]] (which calls [[checkCorrupted]]) would claim the cleanup the exchange still owns and cut across the live
      * transfer. This variant marshals and writes directly, safe because [[exchange.CopyExchange]] is the sole writer during a COPY op.
      */
    private[postgres] def sendSkipCheck[T <: FrontendMessage](msg: T)(using
        m: Marshaller[T]
    )(using Frame): Unit < (Async & Abort[SqlException]) =
        val buf = new PostgresBufferWriter
        m.write(msg, buf)
        writeBytes(buf.toSpan, " (skip-check)")
    end sendSkipCheck

    /** Writes `bytes` to the connection, mapping a closed channel and a write panic to typed failures. `label` names the caller in both, so a
      * failure tells the checked [[send]] path from the [[sendSkipCheck]] one.
      */
    private def writeBytes(bytes: Span[Byte], label: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        Abort.run[Closed](conn.outbound.safe.put(bytes)).flatMap {
            case Result.Success(_) => ()
            case Result.Failure(_) => Abort.fail(SqlConnectionClosedException(s"writing$label"))
            case Result.Panic(t) =>
                Log.error(s"[kyo-sql] PostgresChannel: write panic$label: ${t.getMessage}").andThen(
                    Abort.fail(SqlConnectionWritePanicException(t))
                )
        }

    /** Fails immediately if the channel has been marked corrupted by a previous COPY cleanup failure.
      *
      * A pending COPY cleanup is claimed and run here rather than waited on. A truncated `COPY TO STDOUT` leaves its cleanup finalizer
      * parked until the enclosing scope closes (`Stream.take` drops the continuation holding the scope's own close), and on a pinned
      * session the next write is the very COMMIT that closure waits on, so waiting for the registrant is a deadlock, not a delay. The
      * claim winner runs the same masked, budgeted CopyFail-and-drain the finalizer would have run, then proceeds; a loser waits on the
      * latch, which is bounded because every claimant releases it in every arm. Claiming is sound because a connection runs one exchange
      * at a time, the same invariant every reader on this wire already relies on: a writer arriving here with the cleanup unclaimed
      * means the transfer was abandoned, never that it is still in progress.
      */
    private def checkCorrupted()(using Frame): Unit < (Async & Abort[SqlException]) =
        _cleanup.get.flatMap {
            case Maybe.Present(pending) =>
                pending.claim.flatMap {
                    case true  => pending.abort.andThen(checkCorrupted())
                    case false =>
                        // Another party is running the cleanup. Async.mask makes this wait uninterruptible
                        // so the caller cannot skip it and race the cleanup's bytes on the wire. The gauge
                        // bracket is a read-only signal that a writer has parked here so a test can observe the
                        // block deterministically; it changes no behavior and the wait is unchanged.
                        Async.mask {
                            _pendingCleanupWaiters.incrementAndGet.andThen {
                                pending.latch.await.andThen(_pendingCleanupWaiters.decrementAndGet.unit)
                            }
                        }.andThen(checkCorrupted())
                }
            case Maybe.Absent =>
                _corrupted.get.flatMap { corrupted =>
                    if corrupted then
                        Abort.fail(SqlConnectionProtocolCorruptedException("COPY"))
                    else
                        (
                    )
                }
        }

    /** Applies this connection's `socketTimeout` to a read that can wait for bytes.
      *
      * Funnelling every blocking reader through one helper is what keeps every path that can wait for bytes under the bound,
      * `receiveSkipCheck` included. What the bound covers is one whole message, `MessageReader.readOne` included, not one chunk of the bytes
      * that make it up: a per-chunk budget restarts on every chunk that arrives and so never expires against a server that trickles.
      * `MysqlChannel.readPayload` bounds its reassembly loop for the same reason, which is what makes the two engines the same shape.
      *
      * `read` is by-name so it is constructed inside the branch that runs it: [[MessageReader.readOne]] consults its pending buffer as soon
      * as it is called, and a by-value parameter would move that outside the wrapper.
      *
      * [[Duration.Infinity]], the default and what [[clearSocketTimeout]] installs, passes through unwrapped. The inner bound itself is
      * [[kyo.db.Connection.boundedRead]], shared with `MysqlChannel`; only the timeout source (a mutable ref here) is per-engine.
      */
    private def bounded[A](read: => A < (Async & Abort[SqlException]))(using Frame): A < (Async & Abort[SqlException]) =
        _socketTimeout.get.flatMap { socketTimeout =>
            kyo.db.Connection.boundedRead(socketTimeout)(read)
        }

end PostgresChannel

object PostgresChannel:
    /** Creates a [[PostgresChannel]] over the given safe [[Connection]] with default marshallers and unmarshallers.
      *
      * `socketTimeout` bounds each read; [[Duration.Infinity]], the default, leaves reads unbounded.
      */
    def apply(conn: Connection, socketTimeout: Duration = Duration.Infinity)(using Frame): PostgresChannel < Sync =
        AtomicBoolean.init(false).flatMap { corrupted =>
            AtomicRef.init[Maybe[PendingCopyCleanup]](Maybe.Absent).flatMap { cleanup =>
                AtomicRef.init(socketTimeout).flatMap { readBound =>
                    AtomicInt.init(0).map { pendingCleanupWaiters =>
                        new PostgresChannel(
                            conn,
                            Marshallers.default,
                            Unmarshallers.default,
                            new MessageReader(),
                            readBound,
                            corrupted,
                            cleanup,
                            pendingCleanupWaiters
                        )
                    }
                }
            }
        }

    /** One COPY operation's cleanup, registered on the channel for the transfer's duration.
      *
      * Three parties can resolve it, and [[claim]] makes the resolution exactly-once: the exchange itself at a ReadyForQuery barrier
      * (release only, the wire is already idle), the Scope finalizer (the [[abort]] cleanup, when the transfer failed or was abandoned),
      * and the next writer arriving at [[PostgresChannel.send]] or [[PostgresChannel.receive]] (the same [[abort]] cleanup, when it gets
      * there before a parked finalizer has run). The last party is what keeps an abandoned `COPY TO STDOUT` from deadlocking a pinned
      * session: a truncated stream's finalizer can sit unrun until the enclosing scope closes, and on a transaction's connection the next
      * write is the COMMIT that closure waits on.
      *
      * [[latch]] is what a claim-loser waits on, and that wait is bounded by construction: every claimant runs under the exchange's
      * cleanup budget and releases the latch in every arm.
      */
    final private[postgres] class PendingCopyCleanup(
        val latch: Latch,
        private val resolved: AtomicBoolean,
        val abort: Unit < Async
    ):
        /** True for exactly one caller, which then owns the resolution. */
        def claim(using Frame): Boolean < Sync = resolved.compareAndSet(false, true)
    end PendingCopyCleanup
end PostgresChannel
