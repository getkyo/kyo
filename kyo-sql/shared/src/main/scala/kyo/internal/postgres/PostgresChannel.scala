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
  * Mirrors the [[kyo.internal.mysql.MysqlChannel]] cleanup-latch pattern. When a COPY operation is cancelled or fails mid-stream, the
  * cleanup fiber (which sends CopyFail/CopyDone and drains ReadyForQuery) races with any follow-up operation the caller might issue.
  * [[beginCleanup]] installs a [[Latch]] so that subsequent [[send]]/[[receive]] calls block until cleanup completes; [[endCleanup]] and
  * [[markCorrupted]] clear/close the latch.
  */
final class PostgresChannel(
    val conn: Connection,
    val marshallers: Marshallers,
    val unmarshallers: Unmarshallers,
    val reader: MessageReader,
    private val _corrupted: AtomicBoolean,
    private val _cleanupLatch: AtomicRef[Maybe[Latch]]
):

    /** Marks the channel as corrupted after a failed COPY cleanup attempt.
      *
      * Once corrupted, all subsequent send/receive operations on this channel fail immediately with a [[SqlConnectionException]]. Called by
      * [[exchange.CopyExchange]] when the error-path cleanup itself fails.
      */
    private[postgres] def markCorrupted()(using Frame): Unit < Sync = _corrupted.set(true)

    /** Registers a cleanup latch that blocks subsequent channel operations until the COPY cleanup finishes.
      *
      * Called by [[exchange.CopyExchange]] at the very start of a COPY operation. Ensures callers see either a clean channel or "unusable"
      * never stale protocol bytes from an in-flight cleanup.
      */
    private[postgres] def beginCleanup(latch: Latch)(using Frame): Unit < Sync = _cleanupLatch.set(Maybe.Present(latch))

    /** Clears the cleanup latch, signalling that the COPY cleanup is no longer in-flight.
      *
      * Must be called (together with [[Latch.release]]) by the cleanup fiber before returning. Always pair with [[beginCleanup]].
      */
    private[postgres] def endCleanup()(using Frame): Unit < Sync = _cleanupLatch.set(Maybe.Absent)

    /** Reads the next [[BackendMessage]] without checking the corruption/cleanup-latch guard.
      *
      * Used exclusively by [[exchange.CopyExchange]] on both the happy path and cleanup code. The cleanup latch is registered for the
      * duration of the COPY operation, so [[receive]] (which calls [[checkCorrupted]]) would deadlock waiting for the latch we hold. This
      * variant reads directly from the TCP stream, safe because [[exchange.CopyExchange]] is the sole reader during a COPY op.
      */
    private[postgres] def receiveSkipCheck(using Frame): BackendMessage < (Async & Abort[SqlException]) =
        reader.readOne(conn, unmarshallers)

    /** Serialises `msg` and writes it to the connection without checking the corruption/cleanup-latch guard.
      *
      * Used exclusively by [[exchange.CopyExchange]] on the happy path. The cleanup latch is registered for the duration of the COPY
      * operation, so [[send]] (which calls [[checkCorrupted]]) would deadlock. This variant marshals and writes directly, safe because
      * [[exchange.CopyExchange]] is the sole writer during a COPY op.
      */
    private[postgres] def sendSkipCheck[T <: FrontendMessage](msg: T)(using
        m: Marshaller[T]
    )(using Frame): Unit < (Async & Abort[SqlException]) =
        val buf = new PostgresBufferWriter
        m.write(msg, buf)
        val bytes = buf.toSpan
        Abort.run[Closed](conn.outbound.safe.put(bytes)).flatMap {
            case Result.Success(_) => ()
            case Result.Failure(_) => Abort.fail(SqlConnectionClosedException("writing (skip-check)"))
            case Result.Panic(t) =>
                Log.error(s"[kyo-sql] PostgresChannel: write panic (skip-check): ${t.getMessage}").andThen(
                    Abort.fail(SqlConnectionWritePanicException(t))
                )
        }
    end sendSkipCheck

    /** Fails immediately if the channel has been marked corrupted by a previous COPY cleanup failure.
      *
      * If a cleanup is currently in-flight (signalled by [[_cleanupLatch]]), blocks (uninterruptibly) until cleanup finishes, then
      * re-checks. This prevents the caller from racing with cleanup bytes on the wire.
      */
    private def checkCorrupted()(using Frame): Unit < (Async & Abort[SqlException]) =
        _cleanupLatch.get.flatMap {
            case Maybe.Present(latch) =>
                // A COPY cleanup is in-flight. Block until it releases the latch, then re-check.
                // Async.mask makes this wait uninterruptible so the caller cannot skip it.
                Async.mask { latch.await }.andThen(checkCorrupted())
            case Maybe.Absent =>
                _corrupted.get.flatMap { corrupted =>
                    if corrupted then
                        Abort.fail(SqlConnectionProtocolCorruptedException("COPY"))
                    else
                        (
                    )
                }
        }

    /** Serialises `msg` using the provided [[Marshaller]] and writes the resulting bytes to the connection. */
    def send[T <: FrontendMessage](msg: T)(using m: Marshaller[T])(using Frame): Unit < (Async & Abort[SqlException]) =
        checkCorrupted().andThen {
            val buf = new PostgresBufferWriter
            m.write(msg, buf)
            val bytes = buf.toSpan
            Abort.run[Closed](conn.outbound.safe.put(bytes)).flatMap {
                case Result.Success(_) => ()
                case Result.Failure(_) => Abort.fail(SqlConnectionClosedException("writing"))
                case Result.Panic(t) =>
                    Log.error(s"[kyo-sql] PostgresChannel: write panic: ${t.getMessage}").andThen(
                        Abort.fail(SqlConnectionWritePanicException(t))
                    )
            }
        }
    end send

    /** Reads the next [[BackendMessage]] from the connection. */
    def receive(using Frame): BackendMessage < (Async & Abort[SqlException]) =
        checkCorrupted().andThen(reader.readOne(conn, unmarshallers))

end PostgresChannel

object PostgresChannel:
    /** Creates a [[PostgresChannel]] over the given safe [[Connection]] with default marshallers and unmarshallers. */
    def apply(conn: Connection)(using Frame): PostgresChannel < Sync =
        AtomicBoolean.init(false).flatMap { corrupted =>
            AtomicRef.init[Maybe[Latch]](Maybe.Absent).map { cleanupLatch =>
                new PostgresChannel(
                    conn,
                    Marshallers.default,
                    Unmarshallers.default,
                    new MessageReader(),
                    corrupted,
                    cleanupLatch
                )
            }
        }
end PostgresChannel
