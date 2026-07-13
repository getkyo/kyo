package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.NetException
import kyo.net.NetStdioAlreadyOpenException
import kyo.net.Test
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.WriteResult

/** Tests the `PosixHandle`-backed [[PosixTransport]] stdio factory: the split-fd handle, the process-wide single-stdio CAS, the
  * no-close-of-fd-0/1 teardown, and the driver-selection matrix (epoll + regular file is the one fallback cell).
  *
  * The driver-selection matrix leaves use a real temp file fd (for S_IFREG) or a real socket fd (for non-regular-file types) and real
  * `Ffi.load[SocketBindings].fstat`. The no-close leaf uses `RecordingSocketBindings` over real bindings. The stdio CAS test and the
  * selection-matrix leaves use a real `PollerIoDriver.init(transportConfig)`: the CAS test never invokes the driver (only the `stdioClaimed` CAS is
  * exercised), and the selection predicate is a pure function over `.label` + `backendIsEpoll` + real fstat, so no driver behavior is
  * invoked.
  *
  * Gate: `assumePoller()` for the round-trip and no-close leaves. Selection matrix and no-close leaves do not need a poller gate (fstat
  * is always available); the round-trip leaf uses assumePoller().
  *
  * The real temp-file fd is obtained via the native `kyo_posix_open` shim (documented in `PosixTestSockets`); no reflection is used.
  */
class PosixTransportTest extends Test:

    import AllowUnsafe.embrace.danger

    private val transportConfig = kyo.net.TransportConfig.default

    private def assumePoller(): Unit =
        if !(PosixConstants.isLinux || PosixConstants.isMacOrBsd) then
            cancel("PosixTransport stdio round-trip needs epoll (Linux) or kqueue (macOS/BSD)")

    private def sock = Ffi.load[SocketBindings]

    /** Build a transport with a real PollerIoDriver and real SocketBindings, using a real temp file fd so the selection predicate sees
      * a regular-file (S_IFREG) mode. Returns (transport, rawFd, driver) where `rawFd` must be released with
      * `PosixTestSockets.closeTempFd(rawFd)` after the caller finishes using it.
      *
      * The real PollerIoDriver provides `.label == "PollerIoDriver"` for the predicate; `backendIsEpoll` controls the fallback path.
      */
    private def transportForRegularFile(backendIsEpoll: Boolean): (PosixTransport, Int, PollerIoDriver) =
        val (tempFd, _) = PosixTestSockets.tempFileFd(Array.empty[Byte])
        val realSockets = Ffi.load[SocketBindings]
        val driver      = PollerIoDriver.init(transportConfig)
        val transport   = TestTransports.forTesting(transportConfig, driver, realSockets, backendIsEpoll)
        (transport, tempFd, driver)
    end transportForRegularFile

    /** Build a transport with a real PollerIoDriver and real SocketBindings, using a real socket fd so the selection predicate sees
      * a non-regular-file mode. `isRegularFile(fd)` returns false for a socket, so `pollable(fd)` returns true on any backend.
      */
    private def transportForSocket(backendIsEpoll: Boolean): (PosixTransport, Int, PollerIoDriver) =
        val socketFd  = sock.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
        val driver    = PollerIoDriver.init(transportConfig)
        val transport = TestTransports.forTesting(transportConfig, driver, Ffi.load[SocketBindings], backendIsEpoll)
        (transport, socketFd, driver)
    end transportForSocket

    "PosixTransport.stdio" - {
        "the stdio handle splits the fds to (readFd=0, writeFd=1)" in {
            val h = PosixHandle.stdio(PosixHandle.DefaultReadBufferSize)
            assert(h.readFd == 0, s"readFd=${h.readFd}")
            assert(h.writeFd == 1, s"writeFd=${h.writeFd}")
        }

        // Anti-flakiness: the stdioClaimed CAS is synchronous; the transport.stdio() call is pure wrt the CAS.
        // The real PollerIoDriver is never invoked in this test (no I/O, just the CAS).
        "a second concurrent stdio aborts NetStdioAlreadyOpenException (the stdioClaimed CAS)" in {
            // Use a transport with a real PollerIoDriver (unstarted; no driver method is invoked here).
            // The test exercises the stdioClaimed CAS, not fstat.
            val driver    = PollerIoDriver.init(transportConfig)
            val transport = TestTransports.forTesting(transportConfig, driver, Ffi.load[SocketBindings], backendIsEpoll = false)
            transport.stdio().safe.use { first =>
                Sync.Unsafe.defer(first.close()).andThen {
                    Abort.run[NetException](transport.stdio().safe.get).map { result =>
                        result match
                            case Result.Failure(_: NetStdioAlreadyOpenException) => ()
                            case other => fail(s"expected NetStdioAlreadyOpenException, got $other")
                        end match
                        driver.close()
                        succeed
                    }
                }
            }
        }

        "the connection it builds round-trips bytes through its pumps (the stdio Connection contract)" in {
            assumePoller()
            val driver = PollerIoDriver.init(transportConfig)
            discard(driver.start())
            val transport = TestTransports.forTesting(transportConfig, driver, Ffi.load[SocketBindings], backendIsEpoll = false)
            Sync.ensure(Sync.defer(driver.close())) {
                loopbackPair().map { case (client, accepted) =>
                    val writer = transport.openWith(PosixHandle.socket(client, PosixHandle.DefaultReadBufferSize, Absent), driver)
                    val reader = transport.openWith(PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent), driver)
                    writer.start()
                    reader.start()
                    val payload = Span.fromUnsafe(Array.tabulate[Byte](12)(i => (i + 1).toByte))
                    writer.outbound.safe.put(payload).andThen {
                        reader.inbound.safe.take.map { got =>
                            writer.close()
                            reader.close()
                            assert(got.toArray.toList == payload.toArray.toList, s"round-trip got ${got.toArray.toList}")
                        }
                    }
                }
            }
        }

        // Anti-flakiness: closeHandle is synchronous wrt the close decision; spy.closeCounts is immediately readable. No sleep.
        "closing a split-fd (stdio) handle never closes fds 0/1" in {
            assumePoller()
            val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
            val real     = PollerBackend.default()
            val pollerFd = real.create()
            val driver   = TestDrivers.forBackend(RecordingPollerBackend(real), pollerFd, spy)
            val handle   = PosixHandle.stdio(PosixHandle.DefaultReadBufferSize) // readFd=0, writeFd=1
            // closeHandle issues sockets.close ONLY when readFd == writeFd (a socket); the split stdio handle (0 != 1) skips that branch.
            driver.closeHandle(handle)
            // Free the real poller fd and scratch the recording backend allocated (the poll loop was never started).
            driver.close()
            assert(
                spy.closeCounts.getOrDefault(0, 0) == 0,
                s"stdio teardown must not close fd 0 (stdin), closeCounts=${spy.closeCounts}"
            )
            assert(
                spy.closeCounts.getOrDefault(1, 0) == 0,
                s"stdio teardown must not close fd 1 (stdout), closeCounts=${spy.closeCounts}"
            )
            succeed
        }

        // Anti-flakiness: fstat is synchronous; the selection predicate is a pure function. No async, no sleep.
        // Real temp file fd (S_IFREG) + backendIsEpoll=true -> non-pollable -> BlockingReaderDriver selected.
        "regular-file stdin under epoll selects the BlockingReaderDriver" in {
            val (transport, tempFd, driver) = transportForRegularFile(backendIsEpoll = true)
            try
                assert(!transport.pollable(tempFd), "regular file under epoll must be non-pollable")
                val selected = transport.selectDriver(tempFd)
                assert(selected.label == "BlockingReaderDriver", s"selected driver=${selected.label}, expected BlockingReaderDriver")
            finally
                PosixTestSockets.closeTempFd(tempFd)
                driver.close()
            end try
        }

        // Real temp file fd (S_IFREG) + backendIsEpoll=false -> pollable (kqueue streams regular files natively).
        "regular-file stdin under kqueue does NOT fall back" in {
            val (kqueue, tempFd, driver) = transportForRegularFile(backendIsEpoll = false)
            try
                assert(kqueue.pollable(tempFd), "regular file under kqueue must be pollable")
                assert(kqueue.selectDriver(tempFd).label == "PollerIoDriver", "kqueue must not fall back for regular files")
            finally
                PosixTestSockets.closeTempFd(tempFd)
                driver.close()
            end try
        }

        // The selection predicate short-circuits when the driver's label is not "PollerIoDriver": a regular file under epoll stays pollable
        // (no BlockingReaderDriver fallback) because the io_uring driver streams regular files natively. A RecordingIoDriver over a real
        // PollerIoDriver, relabeled "IoUringDriver", exercises this label branch; every behavioral method still delegates to the real driver.
        "regular-file stdin under a non-poller driver label does NOT fall back" in {
            val (tempFd, _) = PosixTestSockets.tempFileFd(Array.empty[Byte])
            val driver      = PollerIoDriver.init(transportConfig)
            val spy         = new RecordingIoDriver(driver)
            spy.labelOverride = Present("IoUringDriver")
            val transport = TestTransports.forTesting(transportConfig, spy, Ffi.load[SocketBindings], backendIsEpoll = true)
            try
                assert(transport.pollable(tempFd), "a regular file under a non-PollerIoDriver label must stay pollable")
                val selected = transport.selectDriver(tempFd)
                assert(
                    selected.label == "IoUringDriver",
                    s"non-poller label must not fall back to BlockingReaderDriver, got ${selected.label}"
                )
            finally
                PosixTestSockets.closeTempFd(tempFd)
                spy.close()
            end try
        }

        // Real socket fd (S_IFSOCK) -> isRegularFile=false -> pollable on every backend.
        "non-regular-file fds (socket/pipe/tty) are pollable on every backend" in {
            // A real socket fd has S_IFSOCK mode, not S_IFREG. isRegularFile returns false, so pollable returns true on any backend.
            val sockets = sock

            val epollDriver = PollerIoDriver.init(transportConfig)
            val socketFd1   = sockets.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
            val pipeEpoll   = TestTransports.forTesting(transportConfig, epollDriver, Ffi.load[SocketBindings], backendIsEpoll = true)
            try
                assert(pipeEpoll.pollable(socketFd1), "socket fd (non-regular) must be pollable under epoll")
                assert(pipeEpoll.selectDriver(socketFd1).label == "PollerIoDriver", "socket must not fall back under epoll")
            finally
                discard(sockets.close(socketFd1))
                epollDriver.close()
            end try

            val kqueueDriver = PollerIoDriver.init(transportConfig)
            val socketFd2    = sockets.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
            val kqueueEpoll  = TestTransports.forTesting(transportConfig, kqueueDriver, Ffi.load[SocketBindings], backendIsEpoll = false)
            try
                assert(kqueueEpoll.pollable(socketFd2), "socket fd must be pollable under kqueue")
                assert(kqueueEpoll.selectDriver(socketFd2).label == "PollerIoDriver", "socket must not fall back under kqueue")
            finally
                discard(sockets.close(socketFd2))
                kqueueDriver.close()
            end try
        }
    }

    /** Build a connected loopback socket pair; returns (clientFd, acceptedFd). */
    private def loopbackPair()(using Frame, kyo.test.AssertScope): (Int, Int) < Async =
        val server = sock.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
        val (a, l) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", 0).getOrElse(fail("encode failed"))
        Sync.ensure(Sync.defer(a.close())) {
            assert(sock.bind(server, a, l).value == 0)
            assert(sock.listen(server, 4).value == 0)
            val out = Buffer.alloc[Byte](SockAddr.inet4Size)
            val ol  = Buffer.alloc[Int](1)
            ol.set(0, SockAddr.inet4Size)
            val port =
                try
                    assert(sock.getsockname(server, out, ol).value == 0)
                    ((out.get(2) & 0xff) << 8) | (out.get(3) & 0xff)
                finally
                    out.close()
                    ol.close()
            val client   = sock.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
            val (ca, cl) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", port).getOrElse(fail("encode failed"))
            val connected =
                Sync.ensure(Sync.defer(ca.close()))(sock.connect(client, ca, cl).safe.get.map(r => assert(r.value == 0)))
            connected.andThen {
                val noAddr = Buffer.alloc[Byte](SockAddr.inet4Size)
                val noLen  = Buffer.alloc[Int](1)
                noLen.set(0, SockAddr.inet4Size)
                Sync.ensure(Sync.defer { noAddr.close(); noLen.close() }) {
                    sock.accept(server, noAddr, noLen).safe.get.map(_.value)
                }.map { accepted =>
                    sock.close(server).safe.get.map(_ => (client, accepted))
                }
            }
        }
    end loopbackPair

end PosixTransportTest
