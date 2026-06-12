package kyo.net.internal.posix

import java.util.concurrent.ConcurrentLinkedQueue
import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test

// This suite lives in jvm-native/src/test because PosixTransport's connectUnix path runs on JVM-posix and Native; JS uses the Node transport.

/** Reproduce-first guard for SIGPIPE suppression on a `connectUnix` client socket on macOS/BSD.
  *
  * `send(2)` to a peer-closed socket raises SIGPIPE (default disposition: kill the process) unless suppressed. On Linux every driver `send`
  * passes `MSG_NOSIGNAL`; on macOS/BSD `MSG_NOSIGNAL` is the 0 sentinel (`PosixConstants.scala:61`), so SIGPIPE suppression there depends
  * ENTIRELY on the per-socket `SO_NOSIGPIPE` option. `PosixTransport.prepareClientSocket` (`PosixTransport.scala:780-789`) sets
  * `SO_NOSIGPIPE` only inside the `if nodelay` branch:
  * {{{
  * if nodelay then
  *     setIntOpt(fd, IPPROTO_TCP, TCP_NODELAY, 1)
  *     if isMacOrBsd then setIntOpt(fd, SOL_SOCKET, SO_NOSIGPIPE, 1)  // gated on nodelay
  * }}}
  * `connectUnix` calls `connectImpl(..., nodelay = false, ...)` (`PosixTransport.scala:161-171`), so a Unix-domain CLIENT socket on macOS/BSD
  * gets NEITHER `MSG_NOSIGNAL` (0) NOR `SO_NOSIGPIPE`: a `send` to a peer-closed UDS then raises SIGPIPE. The gating conflates "disable Nagle"
  * (TCP-only) with "suppress SIGPIPE" (needed on every socket written to); the option should be set on macOS independent of `nodelay`.
  *
  * On JVM the JVM installs `SIG_IGN` for SIGPIPE globally, masking a real SIGPIPE death, so this asserts at the option-call level rather than
  * relying on a process kill: a delegating [[SocketBindings]] spy records every `setsockopt` tuple and the fd passed to `connect`, and the
  * assertion checks that `setsockopt(connectFd, SOL_SOCKET, SO_NOSIGPIPE)` was recorded for the connectUnix client fd on macOS.
  *
  * Anti-flakiness: no sleep. `connectUnix`'s `prepareClientSocket` runs synchronously before the connect resolves, so by the time the connect
  * fiber completes every socket option for that fd has already been recorded.
  */
class PosixTransportConnectUnixSigpipeTest extends Test:

    import AllowUnsafe.embrace.danger

    private val transportConfig = kyo.net.TransportConfig.default

    private def assumeKqueue(): Unit =
        if !PosixConstants.isMacOrBsd then
            cancel("SO_NOSIGPIPE is the macOS/BSD SIGPIPE-suppression mechanism; on Linux send() uses MSG_NOSIGNAL instead")

    /** A delegating [[SocketBindings]] that records every `setsockopt(fd, level, optname)` tuple and the fd handed to each `connect`. The real
      * syscall runs on every call; nothing is scripted (no injection). Used to observe which options `prepareClientSocket` set on the connectUnix
      * client fd.
      */
    final private class OptRecordingSockets(real: SocketBindings) extends SocketBindings:
        val setsockoptCalls: ConcurrentLinkedQueue[(Int, Int, Int)] = new ConcurrentLinkedQueue[(Int, Int, Int)]()
        val connectFds: ConcurrentLinkedQueue[Int]                  = new ConcurrentLinkedQueue[Int]()

        def setsockopt(fd: Int, level: Int, optname: Int, optval: Buffer[Byte], optlen: Int)(using AllowUnsafe): Ffi.WithError[Int] =
            discard(setsockoptCalls.add((fd, level, optname)))
            real.setsockopt(fd, level, optname, optval, optlen)
        end setsockopt

        def connect(fd: Int, addr: Buffer[Byte], addrlen: Int)(using AllowUnsafe): Fiber.Unsafe[Ffi.WithError[Int], Any] =
            discard(connectFds.add(fd))
            real.connect(fd, addr, addrlen)
        end connect

        def socket(domain: Int, `type`: Int, protocol: Int)(using AllowUnsafe): Ffi.WithError[Int] =
            real.socket(domain, `type`, protocol)
        def bind(fd: Int, addr: Buffer[Byte], addrlen: Int)(using AllowUnsafe): Ffi.WithError[Int] =
            real.bind(fd, addr, addrlen)
        def listen(fd: Int, backlog: Int)(using AllowUnsafe): Ffi.WithError[Int] =
            real.listen(fd, backlog)
        def getsockopt(fd: Int, level: Int, optname: Int, optval: Buffer[Byte], optlen: Buffer[Int])(using
            AllowUnsafe
        ): Ffi.WithError[Int] =
            real.getsockopt(fd, level, optname, optval, optlen)
        def getsockname(fd: Int, addr: Buffer[Byte], addrlen: Buffer[Int])(using AllowUnsafe): Ffi.WithError[Int] =
            real.getsockname(fd, addr, addrlen)
        def fstat(fd: Int, buf: Buffer[Byte])(using AllowUnsafe): Ffi.WithError[Int] =
            real.fstat(fd, buf)
        def shutdown(fd: Int, how: Int)(using AllowUnsafe): Int =
            real.shutdown(fd, how)
        def accept(fd: Int, addr: Buffer[Byte], addrlen: Buffer[Int])(using AllowUnsafe): Fiber.Unsafe[Ffi.WithError[Int], Any] =
            real.accept(fd, addr, addrlen)
        def recv(fd: Int, buf: Buffer[Byte], len: Long, flags: Int)(using AllowUnsafe): Fiber.Unsafe[Ffi.WithError[Long], Any] =
            real.recv(fd, buf, len, flags)
        def send(fd: Int, buf: Buffer[Byte], len: Long, flags: Int)(using AllowUnsafe): Fiber.Unsafe[Ffi.WithError[Long], Any] =
            real.send(fd, buf, len, flags)
        def sendNow(fd: Int, buf: Buffer[Byte], len: Long, flags: Int)(using AllowUnsafe): Ffi.WithError[Long] =
            real.sendNow(fd, buf, len, flags)
        def recvNow(fd: Int, buf: Buffer[Byte], len: Long, flags: Int)(using AllowUnsafe): Ffi.WithError[Long] =
            real.recvNow(fd, buf, len, flags)
        def acceptNow(fd: Int, addr: Buffer[Byte], addrlen: Buffer[Int])(using AllowUnsafe): Ffi.WithError[Int] =
            real.acceptNow(fd, addr, addrlen)
        def read(fd: Int, buf: Buffer[Byte], count: Long)(using AllowUnsafe): Fiber.Unsafe[Ffi.WithError[Long], Any] =
            real.read(fd, buf, count)
        def close(fd: Int)(using AllowUnsafe): Fiber.Unsafe[Int, Any] =
            real.close(fd)
    end OptRecordingSockets

    "PosixTransport connectUnix" - {

        "sets SO_NOSIGPIPE on the client socket on macOS/BSD (SIGPIPE suppression independent of nodelay)" in {
            assumeKqueue()
            // A unique short path under /tmp (well under the 108-byte sun_path limit). nanoTime gives uniqueness without java.util.UUID.
            val path      = s"/tmp/kyo-net-sigpipe-${java.lang.System.nanoTime()}.sock"
            val spy       = new OptRecordingSockets(Ffi.load[SocketBindings])
            val driver    = PollerIoDriver.init(transportConfig)
            val transport = TestTransports.forTesting(transportConfig, driver, spy, backendIsEpoll = false)
            discard(driver.start())
            Abort.run[Closed] {
                for
                    _      <- transport.listenUnix(path, 16)(_ => ()).safe.get
                    client <- transport.connectUnix(path).safe.get
                yield
                    // The connectUnix client fd is the LAST fd handed to connect (the listen path never calls connect). Record it before
                    // teardown so the assertion targets exactly that socket.
                    val connectFd = client.asInstanceOf[kyo.net.internal.transport.Connection[PosixHandle]].handle.readFd
                    client.close()
                    val calls = scala.jdk.CollectionConverters.IteratorHasAsScala(spy.setsockoptCalls.iterator()).asScala.toList
                    assert(
                        calls.contains((connectFd, PosixConstants.SOL_SOCKET, PosixConstants.SO_NOSIGPIPE)),
                        s"connectUnix client fd $connectFd was never opted out of SIGPIPE via setsockopt(SOL_SOCKET, SO_NOSIGPIPE) on macOS. " +
                            s"recorded setsockopt calls: $calls. prepareClientSocket gates SO_NOSIGPIPE behind nodelay=true, but connectUnix " +
                            "passes nodelay=false, so a send to a peer-closed UDS raises SIGPIPE (process kill on Native; masked on the JVM)."
                    )
                end for
            }.map { result =>
                Sync.defer(transport.close()).andThen(Sync.defer(driver.close())).andThen(Abort.get(result))
            }
        }
    }

end PosixTransportConnectUnixSigpipeTest
