package kyo.net

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.internal.posix.PosixConstants
import kyo.net.internal.posix.PosixShimBindings
import kyo.net.internal.posix.SockAddr
import kyo.net.internal.posix.SocketBindings

/** A TCP relay between a kyo client and a kyo server over raw posix sockets, so a test can act on the server's peer at the TCP level:
  * half-close it or reset it, which no kyo connection does on demand. The client connects to [[port]]; the relay accepts it, connects to the
  * server, and copies bytes both ways on two pump fibers, forwarding each direction's EOF as a `SHUT_WR`.
  *
  * The relayed sockets are non-blocking, and a pump that finds nothing to read or no room to write waits [[RawRelay.idle]] and tries again.
  * On JS and Wasm a blocking call holds one of libuv's four worker threads for its whole wait, so relays parked in blocking calls starve the
  * drivers' own polls; and the errno such a call reports is not reliably its own (a `recv` timed out by `SO_RCVTIMEO` reported errno 1 and
  * 17), while a synchronous call's is.
  *
  * Stopping a pump goes through `shutdown(SHUT_RD)`, which ends its reads without putting anything on the wire, and every fd is closed only
  * after both pumps have exited, so no pump can `send` on a closed and recycled descriptor.
  */
final class RawRelay private (sockets: SocketBindings, listenFd: Int, val port: Int):
    import AllowUnsafe.embrace.danger

    private val upstreamFd     = AtomicInt.Unsafe.init(-1)
    private val downstreamFd   = AtomicInt.Unsafe.init(-1)
    private val stopping       = AtomicBoolean.Unsafe.init(false)
    private val connected      = Promise.Unsafe.init[Unit, Any]()
    private val upstreamDone   = Promise.Unsafe.init[Unit, Any]()
    private val downstreamDone = Promise.Unsafe.init[Unit, Any]()

    /** Half-close the server's peer: the server reads EOF while the relay keeps carrying its writes back to the client. */
    def halfCloseUpstream()(using Frame): Unit < Async =
        connected.safe.get.andThen(Sync.defer(discard(sockets.shutdown(upstreamFd.get(), RawRelay.SHUT_WR))))

    /** Reset the server's peer: stop both pumps, then close the server-facing socket with `SO_LINGER {1, 0}`, so the server receives an RST
      * and no FIN.
      */
    def resetUpstream()(using Frame): Unit < Async =
        connected.safe.get.andThen {
            stopPumps().andThen(Sync.defer {
                val up = upstreamFd.getAndSet(-1)
                if up >= 0 then
                    RawRelay.setLingerZero(sockets, up)
                    discard(sockets.close(up))
            })
        }

    private def stopPumps()(using Frame): Unit < Async =
        Sync.defer {
            stopping.set(true)
            val up   = upstreamFd.get()
            val down = downstreamFd.get()
            if up >= 0 then discard(sockets.shutdown(up, PosixConstants.SHUT_RD))
            if down >= 0 then discard(sockets.shutdown(down, PosixConstants.SHUT_RD))
        }.andThen(upstreamDone.safe.get).andThen(downstreamDone.safe.get)

    private def release()(using Frame): Unit < Async =
        if !connected.done() then Sync.defer(discard(sockets.close(listenFd)))
        else
            stopPumps().andThen(Sync.defer {
                Seq(upstreamFd.getAndSet(-1), downstreamFd.getAndSet(-1)).filter(_ >= 0).foreach { fd =>
                    discard(sockets.shutdown(fd, PosixConstants.SHUT_RDWR))
                    discard(sockets.close(fd))
                }
            })

    private def pump(from: Int, to: Int, done: Promise.Unsafe[Unit, Any])(using Frame): Unit < Async =
        val buf = Buffer.alloc[Byte](RawRelay.chunk)
        Sync.ensure(Sync.defer { buf.close(); done.completeDiscard(Result.succeed(())) }) {
            Loop(()) { _ =>
                Sync.defer(sockets.recvNow(from, buf, RawRelay.chunk.toLong, 0)).map { got =>
                    val n = got.value
                    if n < 0 && RawRelay.retryable(got.errorCode) && !stopping.get() then
                        Async.sleep(RawRelay.idle).andThen(Loop.continue(()))
                    else if n <= 0 || stopping.get() then
                        if n == 0 && !stopping.get() then discard(sockets.shutdown(to, RawRelay.SHUT_WR))
                        Loop.done(())
                    else sendAll(to, buf, n).map(ok => if ok then Loop.continue(()) else Loop.done(()))
                    end if
                }
            }
        }
    end pump

    private def sendAll(fd: Int, buf: Buffer[Byte], n: Long)(using Frame): Boolean < Async =
        Loop(0L) { off =>
            if off >= n then Loop.done(true)
            else
                Sync.defer {
                    if off == 0L then sockets.sendNow(fd, buf, n, PosixConstants.MSG_NOSIGNAL)
                    else
                        val rest  = (n - off).toInt
                        val bytes = new Array[Byte](rest)
                        buf.copyToArray(bytes, 0, off.toInt, rest)
                        val tail = Buffer.alloc[Byte](rest)
                        tail.copyFromArray(bytes, 0, 0, rest)
                        try sockets.sendNow(fd, tail, rest.toLong, PosixConstants.MSG_NOSIGNAL)
                        finally tail.close()
                    end if
                }.map { sent =>
                    if sent.value > 0 then Loop.continue(off + sent.value)
                    else if sent.value < 0 && RawRelay.retryable(sent.errorCode) && !stopping.get() then
                        Async.sleep(RawRelay.idle).andThen(Loop.continue(off))
                    else Loop.done(false)
                }
        }
    end sendAll

    private def run(upstreamPort: Int)(using Frame): Unit < Async =
        RawRelay.acceptOne(sockets, listenFd).map { down =>
            discard(sockets.close(listenFd))
            RawRelay.connectTo(sockets, upstreamPort).map { up =>
                val shim = Ffi.load[PosixShimBindings]
                Seq(down, up).foreach(fd => require(shim.kyo_posix_set_nonblocking(fd) == 0, "set_nonblocking"))
                downstreamFd.set(down)
                upstreamFd.set(up)
                connected.completeDiscard(Result.succeed(()))
                Async.zip(pump(up, down, upstreamDone), pump(down, up, downstreamDone)).unit
            }
        }

end RawRelay

object RawRelay:
    import AllowUnsafe.embrace.danger

    private val chunk   = 64 * 1024
    private val SHUT_WR = 1
    // SO_LINGER: 0x0080 on macOS/BSD (socket.h), 13 on Linux (asm-generic/socket.h).
    private val SO_LINGER: Int = if PosixConstants.isMacOrBsd then 0x0080 else 13

    /** How long a pump waits before trying a socket again that had nothing to read or no room to write. */
    val idle: Duration = 1.millis

    private def retryable(errno: Int): Boolean = errno == PosixConstants.EAGAIN || errno == PosixConstants.EINTR

    /** Whether raw posix sockets exist here: every posix host on every platform, through the same bindings the posix transport uses. */
    def available: Boolean =
        !PosixConstants.isWindows &&
            (try
                discard(Ffi.load[SocketBindings])
                true
            catch case _: Throwable => false)

    /** Start a relay that forwards the first connection to its [[RawRelay.port]] to `upstreamPort` on 127.0.0.1. The relay's sockets are
      * released when the enclosing scope closes.
      */
    def init(upstreamPort: Int)(using Frame): RawRelay < (Async & Scope) =
        val sockets          = Ffi.load[SocketBindings]
        val (listenFd, port) = listening(sockets)
        val relay            = new RawRelay(sockets, listenFd, port)
        // Unscoped: interrupting the accept parked on a blocking call would free its address buffers under the kernel's write; the relay
        // ends only through the shutdowns in release. The pumps' idle waits run on the live clock, so a caller's controlled clock cannot
        // freeze them.
        Scope.ensure(relay.release()).andThen(Fiber.initUnscoped(Clock.let(Clock.live)(relay.run(upstreamPort)))).andThen(relay)
    end init

    private def listening(sockets: SocketBindings): (Int, Int) =
        val fd     = sockets.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
        val (a, l) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", 0).getOrElse(throw new IllegalStateException("loopback"))
        try
            require(sockets.bind(fd, a, l).value == 0, "bind")
            require(sockets.listen(fd, 4).value == 0, "listen")
        finally a.close()
        end try
        val out = Buffer.alloc[Byte](SockAddr.inet4Size)
        val ol  = Buffer.alloc[Int](1)
        ol.set(0, SockAddr.inet4Size)
        try
            require(sockets.getsockname(fd, out, ol).value == 0, "getsockname")
            (fd, ((out.get(2) & 0xff) << 8) | (out.get(3) & 0xff))
        finally
            out.close()
            ol.close()
        end try
    end listening

    private def acceptOne(sockets: SocketBindings, listenFd: Int)(using Frame): Int < Async =
        val addr = Buffer.alloc[Byte](SockAddr.inet4Size)
        val len  = Buffer.alloc[Int](1)
        len.set(0, SockAddr.inet4Size)
        Sync.ensure(Sync.defer { addr.close(); len.close() }) {
            sockets.accept(listenFd, addr, len).safe.get.map(fd => noSigpipe(sockets, fd.value))
        }
    end acceptOne

    private def connectTo(sockets: SocketBindings, port: Int)(using Frame): Int < Async =
        val fd     = noSigpipe(sockets, sockets.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value)
        val (a, l) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", port).getOrElse(throw new IllegalStateException("loopback"))
        Sync.ensure(Sync.defer(a.close()))(sockets.connect(fd, a, l).safe.get).map { r =>
            require(r.value == 0, s"relay connect to $port failed: errno ${r.errorCode}")
            fd
        }
    end connectTo

    private def noSigpipe(sockets: SocketBindings, fd: Int): Int =
        if PosixConstants.isMacOrBsd then
            val one = Buffer.alloc[Byte](4)
            one.setIntAt(0, 1)
            try discard(sockets.setsockopt(fd, PosixConstants.SOL_SOCKET, PosixConstants.SO_NOSIGPIPE, one, 4))
            finally one.close()
        end if
        fd
    end noSigpipe

    private def setLingerZero(sockets: SocketBindings, fd: Int): Unit =
        val linger = Buffer.alloc[Byte](8)
        linger.setIntAt(0, 1)
        linger.setIntAt(4, 0)
        try discard(sockets.setsockopt(fd, PosixConstants.SOL_SOCKET, SO_LINGER, linger, 8))
        finally linger.close()
    end setLingerZero

end RawRelay
