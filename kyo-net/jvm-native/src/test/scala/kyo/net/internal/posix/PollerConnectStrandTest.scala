package kyo.net.internal.posix

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test

/** Deterministic stress repro for the connect write-readiness lost-wakeup (`NetConnectTimeoutException` in
  * `TransportHandshakeTimeoutTest`'s rapid stall+reap loop).
  *
  * Drives the REAL in-flight-connect path directly: a non-blocking `connect` to a real listener returns `EINPROGRESS`, so the
  * socket becomes writable only when the kernel TCP handshake COMPLETES, and the poll loop's `dispatchWritable` must wake the
  * connecting fiber on that completion. A dedicated thread `accept`s every connection so the listener's accept queue never
  * fills (so a strand is never a backlog artifact, always a dropped wakeup). Many in-flight connects run concurrently on
  * rapidly-recycled fds; every armed write-readiness promise MUST resolve, never hang. A stranded promise is caught by the
  * bounded `Async.timeout`. Observation is non-perturbing: only in-memory atomic counters, NO console logging in any hot path.
  *
  * Gate: `PosixTestSockets.assumePoller()` (a real epoll/kqueue fd; io_uring uses a different driver, the NIO floor a
  * different transport).
  */
class PollerConnectStrandTest extends Test:

    import AllowUnsafe.embrace.danger

    "every in-flight connect write-readiness resolves under concurrent rapid load (no dropped connect-completion wakeup)" in {
        PosixTestSockets.assumePoller()
        val sockets  = Ffi.load[SocketBindings]
        val shim     = Ffi.load[PosixShimBindings]
        val real     = PollerBackend.default()
        val pollerFd = real.create()
        val backend  = RecordingPollerBackend(real)
        val driver   = TestDrivers.forBackend(backend, pollerFd, sockets)
        discard(driver.start())

        val server   = sockets.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
        val (ba, bl) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", 0).getOrElse(???)
        assert(sockets.bind(server, ba, bl).value == 0, "bind failed")
        ba.close()
        assert(sockets.listen(server, 256).value == 0, "listen failed")
        val out = Buffer.alloc[Byte](SockAddr.inet4Size)
        val ol  = Buffer.alloc[Int](1)
        ol.set(0, SockAddr.inet4Size)
        assert(sockets.getsockname(server, out, ol).value == 0, "getsockname failed")
        val port = ((out.get(2) & 0xff) << 8) | (out.get(3) & 0xff)
        out.close()
        ol.close()

        // A dedicated accept thread drains the accept queue continuously so every connect's handshake completes (a strand is
        // therefore a dropped wakeup, never a full accept queue). Blocking accept on a dedicated thread is test scaffolding, not
        // production code. Accepted fds are closed immediately (the test only needs the connect side to become writable).
        val acceptStop = new AtomicBoolean(false)
        val accepted   = new AtomicInteger(0)
        val acceptor = new Thread(() =>
            import AllowUnsafe.embrace.danger
            val addr = Buffer.alloc[Byte](SockAddr.inet4Size)
            val alen = Buffer.alloc[Int](1)
            while !acceptStop.get() do
                alen.set(0, SockAddr.inet4Size)
                val a = sockets.acceptNow(server, addr, alen)
                if a.value >= 0 then
                    discard(accepted.incrementAndGet())
                    discard(sockets.close(a.value).poll())
                // else EAGAIN/EWOULDBLOCK: nothing pending right now; spin (test scaffolding, bounded by acceptStop).
            end while
            addr.close()
            alen.close()
        )
        // Non-blocking listener so acceptNow returns EAGAIN when idle instead of blocking the acceptor past acceptStop.
        assert(shim.kyo_posix_set_nonblocking(server) == 0, "set_nonblocking(server) failed")
        acceptor.setDaemon(true)
        acceptor.start()

        val tasks = 400
        val hungN = new AtomicInteger(0)

        def oneConnect(using Frame): Boolean < (Async & Abort[Closed]) =
            Sync.defer {
                val client = sockets.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
                assert(shim.kyo_posix_set_nonblocking(client) == 0, "set_nonblocking failed")
                val (ca, cl) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", port).getOrElse(???)
                val rc       = sockets.connect(client, ca, cl).safe.get
                rc.map { r =>
                    ca.close()
                    if r.value == 0 || (r.value < 0 && r.errorCode != PosixConstants.EINPROGRESS) then
                        discard(sockets.close(client).poll())
                        true // immediate-complete or immediate-error: a valid resolution, not a strand
                    else
                        val handle  = PosixHandle.socket(client, PosixHandle.DefaultReadBufferSize, Absent)
                        val promise = Promise.Unsafe.init[Unit, Abort[Closed]]()
                        driver.awaitConnect(handle, promise)
                        Abort.run[Timeout](Async.timeout(10.seconds)(Abort.run[Closed](promise.safe.get))).map { outcome =>
                            discard(sockets.close(client).poll())
                            val resolved = outcome.isSuccess // the writable promise resolved; Timeout = stranded (the bug)
                            if !resolved then discard(hungN.incrementAndGet())
                            resolved
                        }
                    end if
                }
            }

        Async.fill(tasks, 32)(oneConnect).map { results =>
            acceptStop.set(true)
            driver.close()
            discard(sockets.close(server).poll())
            val hung = results.count(_ == false)
            assert(
                hung == 0,
                s"$hung of $tasks in-flight connects were never woken on completion (dropped wakeup); accepted=${accepted.get()}"
            )
        }
    }

end PollerConnectStrandTest
