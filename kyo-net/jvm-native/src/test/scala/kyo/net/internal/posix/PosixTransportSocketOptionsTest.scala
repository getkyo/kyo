package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.TransportConfig
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.IoDriverPool

/** Real socket-option tests for [[TransportConfig.soRcvBuf]], [[TransportConfig.soSndBuf]], and TCP_QUICKACK.
  *
  * Every test uses real POSIX socket fds and real [[SocketBindings.getsockopt]] / [[SocketBindings.setsockopt]] calls. No mocks,
  * stubs, or behavioral spies. The witness is the real kernel-applied option value read back via getsockopt.
  *
  * Gate: [[PosixTestSockets.assumePoller]] cancels the suite where no epoll (Linux) or kqueue (macOS/BSD) is available.
  *
  * Anti-flakiness: all socket operations are synchronous; no sleep.
  */
class PosixTransportSocketOptionsTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    private def assumePoller(): Unit =
        PosixTestSockets.assumePoller()
        ()

    /** Read a 4-byte integer from a real socket option via getsockopt. Returns the kernel-reported value. */
    private def getIntOpt(sockets: SocketBindings, fd: Int, level: Int, optname: Int): Int =
        val opt = Buffer.alloc[Byte](4)
        val len = Buffer.alloc[Int](1)
        len.set(0, 4)
        try
            val rc = sockets.getsockopt(fd, level, optname, opt, len).value
            if rc != 0 then -1
            else
                (opt.get(0) & 0xff) |
                    ((opt.get(1) & 0xff) << 8) |
                    ((opt.get(2) & 0xff) << 16) |
                    ((opt.get(3) & 0xff) << 24)
            end if
        finally
            opt.close()
            len.close()
        end try
    end getIntOpt

    /** Build a real transport backed by one PollerIoDriver, connect a loopback client, run `body` with the real client fd, then close. */
    private def withRealClientFd[A](config: TransportConfig)(body: (SocketBindings, Int) => A < (Async & Abort[Closed] & Scope))(using
        Frame
    ): A < (Async & Abort[Closed] & Scope) =
        val sockets   = Ffi.load[SocketBindings]
        val driver    = PollerIoDriver.init(config)
        val pool      = IoDriverPool.init(Array[IoDriver[PosixHandle]](driver))
        val transport = PosixTransport.init(config, pool)
        pool.start()
        Abort.run[Closed] {
            transport.listen("127.0.0.1", 0, 4)(_ => ()).safe.get.map { listener =>
                transport.connect("127.0.0.1", listener.port).safe.get.map { conn =>
                    val fd = conn.asInstanceOf[kyo.net.internal.transport.Connection[PosixHandle]].handle.readFd
                    body(sockets, fd).map { result =>
                        conn.close()
                        listener.close()
                        result
                    }
                }
            }
        }.map { result =>
            Sync.defer(transport.close()).andThen(Abort.get(result))
        }
    end withRealClientFd

    // --- socketBufferOptionsApplied ---
    // When soRcvBuf=Present(65536) / soSndBuf=Present(32768) is set on a TransportConfig, the real connected client socket must have
    // the kernel-applied buffer size >= the requested size (the kernel may round up to the next power-of-two or similar).
    // Absent: the socket uses the kernel's default (which may differ from 65536/32768), i.e. no setsockopt is called.
    // Anti-flakiness: getsockopt is synchronous; the setsockopt is applied synchronously inside prepareClientSocket.
    "socketBufferOptionsApplied: Present soRcvBuf/soSndBuf applied via real getsockopt readback" in {
        assumePoller()
        val rcvReq = 65536
        val sndReq = 32768
        val config = TransportConfig.default.copy(soRcvBuf = Present(rcvReq), soSndBuf = Present(sndReq))
        withRealClientFd(config) { (sockets, fd) =>
            Sync.defer {
                val actualRcv = getIntOpt(sockets, fd, PosixConstants.SOL_SOCKET, PosixConstants.SO_RCVBUF)
                val actualSnd = getIntOpt(sockets, fd, PosixConstants.SOL_SOCKET, PosixConstants.SO_SNDBUF)
                assert(
                    actualRcv >= rcvReq,
                    s"SO_RCVBUF on real client fd=$fd: expected >= $rcvReq, got $actualRcv (kernel may round up)"
                )
                assert(
                    actualSnd >= sndReq,
                    s"SO_SNDBUF on real client fd=$fd: expected >= $sndReq, got $actualSnd (kernel may round up)"
                )
                succeed
            }
        }
    }

    // --- quickAckLinuxOnly ---
    // TCP_QUICKACK (value 12) is applied only on Linux via prepareClientSocket's `if isLinux` gate.
    // On macOS/BSD the gate is not entered (TCP_QUICKACK constant is the 0 sentinel).
    // Verified via: on Linux, real getsockopt(TCP_QUICKACK) returns 1 (set) after prepareClientSocket; on macOS, no error from the 0-sentinel
    // setsockopt call, and the round-trip completes correctly regardless.
    // Anti-flakiness: getsockopt is synchronous; no sleep.
    "quickAckLinuxOnly: TCP_QUICKACK applied only on Linux, correct behavior on macOS" in {
        assumePoller()
        withRealClientFd(TransportConfig.default) { (sockets, fd) =>
            Sync.defer {
                if PosixConstants.isLinux then
                    // On Linux, TCP_QUICKACK (level=IPPROTO_TCP=6, optname=12) should be set to 1 by prepareClientSocket.
                    val actual = getIntOpt(sockets, fd, PosixConstants.IPPROTO_TCP, PosixConstants.TCP_QUICKACK)
                    assert(
                        actual == 1,
                        s"TCP_QUICKACK on Linux client fd=$fd: expected 1 (set by prepareClientSocket), got $actual"
                    )
                else
                    // On macOS/BSD, TCP_QUICKACK constant is 0 (sentinel). The 0-sentinel path skips the setsockopt call inside
                    // the `if isLinux` gate, so no error should occur and the socket is fully functional.
                    // Verify the constant is 0 (macOS sentinels the option).
                    assert(
                        PosixConstants.TCP_QUICKACK == 0,
                        s"TCP_QUICKACK must be 0 on macOS/BSD (sentinel), got ${PosixConstants.TCP_QUICKACK}"
                    )
                end if
                succeed
            }
        }
    }

end PosixTransportSocketOptionsTest
