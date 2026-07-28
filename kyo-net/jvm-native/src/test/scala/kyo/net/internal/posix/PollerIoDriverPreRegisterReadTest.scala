package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Ffi
import kyo.net.Test

/** Data that arrives on a socket BEFORE the fd's first read registration must still be delivered.
  *
  * Edge-triggered registration (kqueue `EV_ADD | EV_CLEAR`, epoll `EPOLLET`) signals readiness on the empty->ready transition. When a peer's
  * bytes arrive before the fd is armed for reading, no transition follows the registration, so a poller that waited solely for a post-register
  * edge would strand the read (deterministic hang on kqueue; a fast peer whose request lands between `accept` and the first `awaitRead`).
  * [[PollerIoDriver]] probes once on a fresh or recycled read registration to drain any pre-existing bytes. This drives that pattern: write to
  * the client, THEN register a read on the peer, and assert every byte is delivered. The loop also covers recycled fds (a closed fd's number
  * reused by the next pair). `assumePoller()` cancels where neither epoll nor kqueue is available.
  */
class PollerIoDriverPreRegisterReadTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    private val transportConfig = kyo.net.NetConfig.default
    private val sock            = Ffi.load[SocketBindings]

    "a read registered after the peer's bytes already arrived is still delivered (no lost edge)" in {
        PosixTestSockets.assumePoller()
        val real = PollerIoDriver.init()
        val spy  = new RecordingIoDriver(real)
        discard(spy.start())
        Kyo.foreach(0 until 20) { i =>
            PosixTestSockets.loopbackPair().map { case (clientFd, peerFd) =>
                val clientH = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent)
                val peerH   = PosixHandle.socket(peerFd, PosixHandle.DefaultReadBufferSize, Absent)
                val payload = Array.tabulate[Byte](136)(j => (j % 7).toByte)
                // Send on the client BEFORE the first awaitRead on the peer: the bytes sit in the peer's socket buffer before the read is armed.
                discard(spy.write(clientH, Span.fromUnsafe(payload), 0))
                PosixTestSockets.drainPeer(spy, peerH, peerFd, payload.length).map { received =>
                    discard(sock.close(clientFd))
                    discard(sock.close(peerFd))
                    assert(received >= payload.length, s"iter $i: pre-registration data was lost, got $received of ${payload.length}")
                    ()
                }
            }
        }.map { _ =>
            spy.close()
            succeed
        }
    }

end PollerIoDriverPreRegisterReadTest
