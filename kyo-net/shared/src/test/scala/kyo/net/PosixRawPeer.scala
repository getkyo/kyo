package kyo.net

import kyo.*
import kyo.ffi.Buffer
import kyo.net.internal.posix.PosixConstants
import kyo.net.internal.posix.SocketBindings

/** A [[RawPeer]] over raw posix socket descriptors, each blocking call running as an `@Ffi.blocking` downcall. It reads `readFd` and writes
  * `writeFd`: one socket for a TCP peer, two for the far end of a split-fd connection. It owns both descriptors and closes them once.
  */
final private[net] class PosixRawPeer(sockets: SocketBindings, readFd: Int, writeFd: Int) extends RawPeer:

    import AllowUnsafe.embrace.danger

    private val closed                               = AtomicBoolean.Unsafe.init(false)
    @volatile private var ended: Maybe[RawPeer.Read] = Absent

    def send(bytes: Span[Byte])(using Frame): Unit < Async =
        val all = bytes.toArray
        Loop(0) { off =>
            if off >= all.length then Loop.done(())
            else
                val rest = all.length - off
                val buf  = Buffer.alloc[Byte](rest)
                buf.copyFromArray(all, off, 0, rest)
                Sync.ensure(Sync.defer(buf.close()))(sockets.send(writeFd, buf, rest.toLong, PosixConstants.MSG_NOSIGNAL).safe.get).map {
                    sent =>
                        if sent.value <= 0 then throw new java.io.IOException(s"raw peer send failed: errno ${sent.errorCode}")
                        else Loop.continue(off + sent.value.toInt)
                }
        }
    end send

    def recv()(using Frame): RawPeer.Read < Async =
        ended match
            case Present(end) => end
            case Absent       =>
                val buf = Buffer.alloc[Byte](PosixRawPeer.chunk)
                Sync.ensure(Sync.defer(buf.close())) {
                    sockets.recv(readFd, buf, PosixRawPeer.chunk.toLong, 0).safe.get.map { got =>
                        val n = got.value
                        if n > 0 then
                            val bytes = new Array[Byte](n.toInt)
                            buf.copyToArray(bytes, 0, 0, n.toInt)
                            RawPeer.Read.Bytes(Span.fromUnsafe(bytes))
                        else
                            val end = if n == 0 then RawPeer.Read.Fin else RawPeer.Read.Reset(s"errno ${got.errorCode}")
                            ended = Present(end)
                            end
                        end if
                    }
                }

    def shutdownWr()(using Frame): Unit < Async = Sync.defer(discard(sockets.shutdown(writeFd, PosixRawPeer.SHUT_WR)))

    def reset()(using Frame): Unit < Async =
        Sync.defer {
            val linger = Buffer.alloc[Byte](8)
            linger.setIntAt(0, 1)
            linger.setIntAt(4, 0)
            try discard(sockets.setsockopt(writeFd, PosixConstants.SOL_SOCKET, PosixRawPeer.SO_LINGER, linger, 8))
            finally linger.close()
        }.andThen(close())

    def close()(using Frame): Unit < Async =
        Sync.defer {
            if closed.compareAndSet(false, true) then
                discard(sockets.close(writeFd))
                if readFd != writeFd then discard(sockets.close(readFd))
        }

    /** The scope's release: `shutdown` first wakes a `recv` still blocked on a descriptor, so the close cannot land under it. */
    def dispose()(using Frame): Unit < Async =
        Sync.defer {
            if closed.compareAndSet(false, true) then
                Seq(readFd, writeFd).distinct.foreach { fd =>
                    discard(sockets.shutdown(fd, PosixConstants.SHUT_RDWR))
                    discard(sockets.close(fd))
                }
        }

end PosixRawPeer

private[net] object PosixRawPeer:

    private val chunk   = 64 * 1024
    private val SHUT_WR = 1
    // SO_LINGER: 0x0080 on macOS/BSD (socket.h), 13 on Linux (asm-generic/socket.h).
    private val SO_LINGER: Int = if PosixConstants.isMacOrBsd then 0x0080 else 13

    /** A peer over `readFd` and `writeFd`, disposed when the enclosing scope closes. */
    def init(sockets: SocketBindings, readFd: Int, writeFd: Int)(using Frame): PosixRawPeer < (Sync & Scope) =
        val peer = new PosixRawPeer(sockets, readFd, writeFd)
        Scope.ensure(peer.dispose()).andThen(peer)

end PosixRawPeer
