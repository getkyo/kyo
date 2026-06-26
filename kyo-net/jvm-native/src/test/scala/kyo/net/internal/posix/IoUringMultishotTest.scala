package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.WriteResult

/** Verifies that sequential single-shot accepts lose no completion, and that the fused submit-and-wait enter loses no completion.
  *
  * Two leaves:
  *
  *   1. `sequentialAcceptsDeliverDistinctFds`: drives C sequential single-shot accept SQEs, verifying all C fds are delivered with
  *      distinct non-negative values. Each accept uses a fresh SQE and a fresh promise; after each CQE the test arms the next accept
  *      before connecting the next client (matching the production accept-loop pattern).
  *   2. `fusedEnterNoCompletionLost`: sends B payloads through the driver while the reap loop uses the fused submit-and-wait enter,
  *      verifying each echo delivers the correct bytes.
  *
  * Both leaves are Linux-only (io_uring real ring, podman). Cancel on macOS.
  */
class IoUringMultishotTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    private def withRealDriver[A](body: IoUringDriver => A < (Abort[Closed] & Async))(using Frame): A < (Abort[Closed] & Async) =
        val driver = IoUringDriver.init(kyo.net.TransportConfig.default)
        discard(driver.start())
        Sync.ensure(Sync.defer(driver.close()))(body(driver))
    end withRealDriver

    private def listenOnly()(using Frame, kyo.test.AssertScope): Int < Async =
        val server = sock.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
        val (a, l) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", 0).getOrElse(fail("encode failed"))
        Sync.ensure(Sync.defer(a.close())) {
            assert(sock.bind(server, a, l).value == 0)
            assert(sock.listen(server, 32).value == 0)
            Sync.defer(server)
        }
    end listenOnly

    private def connectTo(serverFd: Int)(using Frame, kyo.test.AssertScope): Int < Async =
        val out = Buffer.alloc[Byte](SockAddr.inet4Size)
        val ol  = Buffer.alloc[Int](1)
        ol.set(0, SockAddr.inet4Size)
        val port =
            try
                assert(sock.getsockname(serverFd, out, ol).value == 0)
                ((out.get(2) & 0xff) << 8) | (out.get(3) & 0xff)
            finally
                out.close()
                ol.close()
        val client   = sock.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
        val (ca, cl) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", port).getOrElse(fail("encode failed"))
        Sync.ensure(Sync.defer(ca.close()))(sock.connect(client, ca, cl).safe.get.map(r => assert(r.value == 0))).map(_ => client)
    end connectTo

    "IoUringMultishot" - {

        "sequentialAcceptsDeliverDistinctFds: C sequential accepts each use a fresh SQE and deliver distinct fds" in {
            PosixTestSockets.assumeUring()
            // Verifies the single-shot accept lifecycle: each connection uses one SQE and one CQE. After each CQE the accept loop
            // arms the next accept with a fresh promise (the production accept-loop pattern). All C fds must be non-negative and
            // distinct (no completion lost, no stale key).
            val C = 5
            withRealDriver { driver =>
                listenOnly().flatMap { serverFd =>
                    val serverH = PosixHandle.socket(serverFd, PosixHandle.DefaultReadBufferSize, Absent)
                    // Arm the first accept before any client connects.
                    val p0 = Promise.Unsafe.init[Int, Abort[Closed]]()
                    driver.awaitAccept(serverH, p0)
                    // Connect one client at a time and wait for its accept CQE before arming the next accept and connecting the next
                    // client. This matches the production accept-loop pattern: await fd, arm next accept, handle fd.
                    Loop(p0, Vector.empty[Int]) { (currentPromise, fds) =>
                        connectTo(serverFd).flatMap { clientFd =>
                            currentPromise.safe.get.map { fd =>
                                // Close this client once its connection has been accepted (the accepted server-side fd stays valid). Without this
                                // the per-iteration client fds were discarded and never closed, leaking one fd per accepted connection.
                                discard(sock.close(clientFd))
                                val acc = fds :+ fd
                                if acc.size >= C then Loop.done(acc)
                                else
                                    // Arm the next single-shot accept SQE before connecting the next client.
                                    val next = Promise.Unsafe.init[Int, Abort[Closed]]()
                                    driver.awaitAccept(serverH, next)
                                    Loop.continue(next, acc)
                                end if
                            }
                        }
                    }.map { fds =>
                        driver.closeHandle(serverH)
                        fds.foreach { fd =>
                            if fd >= 0 then discard(sock.close(fd))
                        }
                        // serverFd is already closed: closeHandle(serverH) -> closeNow closes the OS fd (readFd == writeFd) via claimFdClose. A
                        // raw sock.close(serverFd) here is a double-close: under concurrent cold load the freed number is recycled to another
                        // test's fresh fd and the stale second close lands on it (EBADF). closeHandle already releases the number.
                        assert(fds.forall(_ >= 0), s"all accepted fds must be >= 0: $fds")
                        assert(fds.distinct.size == C, s"all $C accepted fds must be distinct: $fds")
                    }
                }
            }
        }

        "fusedEnterNoCompletionLost: B round-trips through the fused submit-and-wait deliver the correct bytes" in {
            PosixTestSockets.assumeUring()
            // The fused kyo_uring_submit_and_wait_timeout replaces the prior separate flushSubmits + kyo_uring_wait_cqe_timeout.
            // B echo round-trips must all deliver the correct bytes, proving the fused enter loses no CQE.
            val B = 8
            withRealDriver { driver =>
                // Use B loopback pairs (each pair is an independent echo round-trip).
                Loop(0) { i =>
                    if i >= B then Loop.done(succeed)
                    else
                        PosixTestSockets.loopbackPair().flatMap { case (client, accepted) =>
                            val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                            val payload   = Span.fromUnsafe(Array.tabulate[Byte](16)(j => ((i * 16 + j) & 0xff).toByte))
                            val w         = driver.write(acceptedH, payload, 0)
                            assert(w == WriteResult.Done, s"write result=$w for round-trip $i")
                            val readPromise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                            // Peer sends first so the recv SQE sees data; the fused enter submits the recv and waits for its CQE in one call.
                            assert(
                                sock.sendNow(
                                    client,
                                    Buffer.fromArray[Byte](payload.toArray),
                                    payload.size.toLong,
                                    0
                                ).value == payload.size.toLong,
                                s"peer send failed for round-trip $i"
                            )
                            driver.awaitRead(acceptedH, readPromise)
                            readPromise.safe.get.map { outcome =>
                                val got = outcome match
                                    case ReadOutcome.Bytes(span) => span
                                    case other                   => fail(s"round-trip $i: expected ReadOutcome.Bytes, got $other")
                                driver.closeHandle(acceptedH)
                                discard(sock.close(client))
                                assert(
                                    got.toArray.toList == payload.toArray.toList,
                                    s"round-trip $i: got ${got.toArray.toList}, expected ${payload.toArray.toList}"
                                )
                                Loop.continue(i + 1)
                            }
                        }
                }
            }
        }
    }
end IoUringMultishotTest
