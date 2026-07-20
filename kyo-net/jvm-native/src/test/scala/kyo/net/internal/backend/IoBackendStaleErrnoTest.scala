package kyo.net.internal.backend

import kyo.*
import kyo.ffi.Ffi
import kyo.net.NetConfig
import kyo.net.Test
import kyo.net.internal.posix.SocketBindings

/** Cross-backend consistency guard for stale-errno init: every I/O backend must build a working transport even when a PRIOR syscall on the
  * calling thread has left a non-zero `errno`.
  *
  * A non-zero `errno` is the steady state of any real program: every non-blocking accept that returns `EAGAIN`, every `connect` that returns
  * `EINPROGRESS`, every failed `open`/`stat` leaves `errno` set. Creating a transport after any such call must behave identically on every
  * backend. It did not: the io_uring driver read the captured `errno` after `io_uring_queue_init` instead of the call's return value, and
  * liburing returns `0`/`-errno` directly WITHOUT setting the global `errno`. So a stale `errno` made io_uring init throw `Closed`, while the
  * epoll/kqueue/nio backends (which gate their `errno` reads on a negative syscall return, the correct POSIX idiom) were unaffected. The
  * result was a silent inconsistency: the exact same program created a transport on epoll but failed to on io_uring.
  *
  * This is the validated platform exception to the "no platform-specific tests" rule. The invariant ("a backend builds a working transport
  * regardless of the caller's `errno`") is asserted for EVERY backend the platform registers, so it is cross-backend, not io_uring-specific;
  * it lives under `jvm-native` only because the posix backends (io_uring/epoll/kqueue) and the JVM Nio floor exist only there. JS's Node
  * backend has no `liburing`/`queue_init` analog, so the invariant has no JS counterpart to assert.
  *
  * Determinism: a deliberately failing `socket(-1, -1, -1)` (EAFNOSUPPORT/EINVAL) dirties `errno` immediately before the synchronous
  * `entry.build`, which runs the driver init inline on this same thread. No syscall clears `errno` to 0 on success, so the dirtied `errno`
  * survives every internal `queue_init` to the result read: a buggy io_uring init would read it and throw here; a correct init reads the
  * return value (0) and builds. The other backends build regardless. Every available backend builds and round-trips.
  */
class IoBackendStaleErrnoTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    "every available I/O backend builds a working transport after a prior syscall left errno non-zero" - {
        IoBackendPlatform.registered.foreach { entry =>
            s"backend=${entry.name}" in {
                if !entry.isAvailable then cancel(s"backend ${entry.name} is not available on this host")
                else
                    // Dirty errno on THIS thread, then build synchronously: the driver init reads errno at queue_init and must ignore it.
                    val dirty = sock.socket(-1, -1, -1)
                    assert(
                        dirty.value < 0 && dirty.errorCode != 0,
                        s"precondition: socket(-1,-1,-1) must fail and set errno, got value=${dirty.value} errorCode=${dirty.errorCode}"
                    )
                    // Built inside ProcessSharedTransport.whileBuilding: this leaf builds a fresh, ad-hoc transport per available backend (the
                    // stale-errno precondition demands a brand new driver each time), never through NetPlatform's own single shared instance,
                    // so nothing else ever closes it. Marking its driver's cycle the same way NetPlatform.transport's construction does keeps it
                    // correctly excused from the end-of-run leak check, exactly like the one real process-shared transport, rather than tripping
                    // a false leaked-owned-transport report for a transport this test never had a way to close (transports have no close()).
                    val transport =
                        try kyo.net.internal.ProcessSharedTransport.whileBuilding(entry.build())
                        catch
                            case c: Closed =>
                                fail(
                                    s"building the ${entry.name} transport threw Closed on a stale errno (errorCode=${dirty.errorCode}) " +
                                        s"although the backend is available. Backend init must read the syscall RETURN value, not the captured " +
                                        s"errno: ${c.getMessage}"
                                )
                    // The transport built. Prove the backend actually works after the stale errno: bind an ephemeral port and connect to it.
                    Sync.defer(()).andThen {
                        for
                            listener <- transport.listen("127.0.0.1", 0, 128)(_ => ()).safe.get
                            port = listener.port
                            outcome <- Abort.run[Closed](transport.connect("127.0.0.1", port).safe.get)
                        yield
                            outcome match
                                case Result.Success(conn) => conn.close()
                                case _                    => ()
                            listener.close()
                            assert(port > 0, s"${entry.name}: listener must bind an ephemeral port, got $port")
                            assert(
                                outcome.isSuccess,
                                s"${entry.name}: connecting to its own listener must succeed after a stale errno, got $outcome"
                            )
                    }
                end if
            }
        }
    }

end IoBackendStaleErrnoTest
