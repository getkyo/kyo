package kyo.net.internal.transport

import kyo.*
import kyo.net.NetException

/** One driver [[IoDriverCancelContractTest]] checks, over a fresh handle per leaf. Each platform lists its cells in `CancelContractCells`. */
abstract class CancelCell:
    def name: String

    /** Why this cell cannot run on this host, if it cannot. */
    def unavailable(using AllowUnsafe): Maybe[String]

    /** The entry paths this driver has. A leaf that needs one the driver lacks cancels with the reason. */
    def supports: Set[CancelCell.Op]

    /** A started driver and one handle of `kind` on it, released when the scope closes. */
    def open(kind: CancelCell.Kind)(using Frame, AllowUnsafe): CancelCell.Subject < (Async & Scope)
end CancelCell

object CancelCell:

    enum Op derives CanEqual:
        case Read, Write, Upgrade, UpgradeWaiter, UpgradeProducer, Connect, Accept, ListenerClaim, CountsCloses

    enum Kind derives CanEqual:
        /** A connected handle whose peer this cell drives. */
        case Connected

        /** A handle whose connect is in flight against an address that never answers. */
        case Connecting

        /** A listening handle no client connects to. */
        case Listening

        /** A handle on which a read and a writable wait both stay parked until the call under test, whatever the kernel does meanwhile. A
          * loopback socket that refused a send keeps opening room while its bytes reach the peer, so a full connected socket is not one.
          */
        case Stalled
    end Kind

    /** A driver and one of its handles, seen through the [[IoDriver]] entry paths. */
    abstract class Subject:
        def awaitRead(p: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using AllowUnsafe, Frame): Unit
        def awaitWritable(p: Promise.Unsafe[Unit, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit
        def awaitConnect(p: Promise.Unsafe[Unit, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit
        def awaitAccept(p: Promise.Unsafe[Int, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit

        /** Parks the STARTTLS handshake's wait for the peer's next flight, as the handshake does, on cells with [[Op.UpgradeWaiter]]. */
        def parkUpgradeWaiter(p: Promise.Unsafe[Span[Byte], Abort[NetException]])(using AllowUnsafe, Frame): Unit =
            throw new IllegalStateException("this cell has no upgrade handoff waiter")

        /** Arms the STARTTLS handshake's producer read over `p`, as the handshake does, on cells with [[Op.UpgradeProducer]]. */
        def armUpgradeProducer(p: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
            throw new IllegalStateException("this cell has no upgrade producer read")

        /** Writes until the driver reports the handle cannot take more, so the next writable wait parks. The peer never reads. */
        def fillSendBuffer()(using AllowUnsafe, Frame): Unit < Async

        /** One write of `data` from its start, as the write pump makes it. */
        def write(data: Span[Byte])(using AllowUnsafe): WriteResult

        /** Sends `bytes` from the peer end of a connected handle. */
        def peerSend(bytes: Array[Byte])(using AllowUnsafe, Frame): Unit < Async

        def cancel()(using AllowUnsafe, Frame): Unit
        def detachForUpgrade()(using AllowUnsafe, Frame): Unit

        /** The connection's release of the handle: the one call that takes the release claim. */
        def release()(using AllowUnsafe, Frame): Unit

        def closeListener()(using AllowUnsafe, Frame): Unit

        /** Completes once the handle's descriptor has been closed, on cells that count closes. */
        def descriptorClosed(using AllowUnsafe, Frame): Unit < Async

        /** How many times the handle's descriptor has been closed, on cells that count closes. */
        def descriptorCloses(using AllowUnsafe): Int

        /** Closes the driver; completes once its loop has exited, so no driver work on the handle is still to come. */
        def stopDriver()(using AllowUnsafe, Frame): Unit < Async
    end Subject

    /** A subject over one driver and one handle of it, for a cell to complete with its peer and its descriptor accounting. */
    abstract class DriverSubject[H](val driver: IoDriver[H], val handle: H) extends Subject:
        def awaitRead(p: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
            driver.awaitRead(handle, p)
        def awaitWritable(p: Promise.Unsafe[Unit, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit =
            driver.awaitWritable(handle, p)
        def awaitConnect(p: Promise.Unsafe[Unit, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit =
            driver.awaitConnect(handle, p)
        def awaitAccept(p: Promise.Unsafe[Int, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit =
            driver.awaitAccept(handle, p)

        def fillSendBuffer()(using AllowUnsafe, Frame): Unit < Async =
            val chunk = Span.fromUnsafe(new Array[Byte](64 * 1024))
            Loop(0) { written =>
                driver.write(handle, chunk, 0) match
                    case WriteResult.Done     => Loop.continue(written + chunk.size)
                    case WriteResult.Error(_) =>
                        throw new IllegalStateException(s"a write failed after $written bytes while filling the send buffer")
                    case _ => Loop.done(())
            }
        end fillSendBuffer

        def write(data: Span[Byte])(using AllowUnsafe): WriteResult = driver.write(handle, data, 0)

        def cancel()(using AllowUnsafe, Frame): Unit           = driver.cancel(handle)
        def detachForUpgrade()(using AllowUnsafe, Frame): Unit = driver.detachForUpgrade(handle)
        def release()(using AllowUnsafe, Frame): Unit          = driver.release(handle, ReleaseMode.Graceful, Promise.Unsafe.init())
    end DriverSubject

end CancelCell
