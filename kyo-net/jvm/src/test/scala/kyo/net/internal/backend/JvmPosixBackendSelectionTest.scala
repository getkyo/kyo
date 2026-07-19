package kyo.net.internal.backend

import kyo.*
import kyo.net.NetBackendUnavailableException
import kyo.net.NetConfig
import kyo.net.Test
import kyo.net.internal.posix.PosixConstants
import kyo.net.internal.posix.PosixTransport

/** JVM backend-selection tests for the unified transport wiring. They confirm the production `IoBackendPlatform.transport` selects the
  * OS-appropriate posix backend (kqueue on this macOS/BSD host, io_uring/epoll on Linux) over the always-available `NioBackend` floor, that
  * the built transport round-trips real bytes over loopback (proving the posix driver actually drives I/O), that forcing `nio` selects the
  * floor (which still round-trips as production), and that the Nio floor stays registered so selection never fails.
  *
  * The round-trip leaves drive a real loopback connect/listen/accept echo through the production-built transport, asserting the concrete
  * echoed bytes. The forced-nio leaf drives the same shared `IoBackend.select` over the real `IoBackendPlatform.registered` entries with a
  * directly constructed `forced` value (so it never mutates the live `kyo.net.backend` StaticFlag that the shared `NetPlatform.transport`
  * lazy-init reads, avoiding any cross-suite race), then builds and round-trips the selected floor entry directly.
  */
class JvmPosixBackendSelectionTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    private val transportConfig = NetConfig.default

    /** Drive a real loopback echo through `transport`: listen on an ephemeral port whose handler echoes one inbound chunk, connect, write the
      * payload, and read the echoed bytes back. Returns the bytes received by the client.
      */
    private def echoRoundTrip(transport: kyo.net.Transport, payload: Array[Byte])(using
        Frame
    ): Array[Byte] < (Async & Abort[kyo.net.NetException | Closed]) =
        for
            listener <- transport.listen("127.0.0.1", 0, 128) { serverConn =>
                discard(Sync.Unsafe.evalOrThrow {
                    Fiber.initUnscoped {
                        Abort.run[Closed] {
                            serverConn.inbound.safe.take.flatMap(bytes => serverConn.outbound.safe.put(bytes))
                        }.unit
                    }
                })
            }.safe.get
            port = listener.port
            conn     <- transport.connect("127.0.0.1", port).safe.get
            _        <- conn.outbound.safe.put(Span.from(payload))
            received <- conn.inbound.safe.take
        yield
            conn.close()
            listener.close()
            received.toArray
        end for
    end echoRoundTrip

    "the production transport selects the OS-appropriate posix backend and round-trips bytes" in {
        // The selected entry must be the OS-appropriate posix backend (kqueue on this macOS/BSD host), not the nio floor. A cell-isolation
        // run (KYO_NET_ONLY=<backend>, bridged to -Dkyo.net.backend by kyo.net.Test) forces that backend instead of the natural priority
        // gradient; without accounting for it here, a KYO_NET_ONLY=epoll run on a host with io_uring available would wrongly expect
        // "io_uring", since natural selection never consults the isolation env var.
        sys.env.get("KYO_NET_ONLY") match
            case Some(only) =>
                assert(IoBackendPlatform.selected.name == only, s"selected=${IoBackendPlatform.selected.name}, expected=$only")
            case None =>
                if PosixConstants.isMacOrBsd then
                    assert(KqueueBackend.isAvailable, "kqueue must be available on this macOS/BSD host")
                    assert(IoBackendPlatform.selected.name == "kqueue", s"selected=${IoBackendPlatform.selected.name}")
                else if PosixConstants.isLinux then
                    val expected = if IoUringBackend.isAvailable then "io_uring" else "epoll"
                    assert(IoBackendPlatform.selected.name == expected, s"selected=${IoBackendPlatform.selected.name}")
                else
                    cancel("no posix backend on this host; selection round-trip needs epoll/kqueue/io_uring")
                end if
        end match
        // The built production transport must use a PosixTransport (the unified posix path), not NioTransport.
        val unsafe = IoBackendPlatform.transport()
        assert(unsafe.isInstanceOf[PosixTransport], s"production transport is ${unsafe.getClass.getSimpleName}, expected PosixTransport")
        val payload = "posix-echo".getBytes
        echoRoundTrip(unsafe, payload).map { got =>
            assert(got.toList == payload.toList, s"round-trip got ${new String(got)}")
        }
    }

    "forcing nio selects the floor over an available posix backend, and the floor still round-trips as production" in {
        // Drive the SAME shared IoBackend.select the production registry uses, over the real registered entries, with a directly
        // constructed forced value so the live kyo.net.backend StaticFlag (read by the shared NetPlatform.transport lazy-init) is never
        // mutated. On this posix host a posix backend would win unforced; forcing "nio" must pick the floor instead, proving the
        // -Dkyo.net.backend -> forced-selection wiring works.
        val forced = IoBackend.select[Entry, NetBackendUnavailableException](
            IoBackendPlatform.registered,
            _.name,
            _.priority,
            _.isAvailable,
            forced = Present("nio"),
            onUnavailable = NetBackendUnavailableException(_)
        ).getOrThrow
        assert(forced.name == "nio", s"forced selected=${forced.name}")
        // The forced floor must build the NioTransport, not a PosixTransport, and that floor must round-trip as production.
        val unsafe = forced.build()
        assert(!unsafe.isInstanceOf[PosixTransport], "forced-nio transport is a PosixTransport, expected NioTransport")
        val payload = "nio-floor-echo".getBytes
        echoRoundTrip(unsafe, payload).map { got =>
            assert(got.toList == payload.toList, s"forced-nio round-trip got ${new String(got)}")
        }
    }

    "the NioBackend floor stays registered so selection can never fail" in {
        val nioEntries = IoBackendPlatform.registered.filter(_.name == "nio")
        assert(nioEntries.size == 1, s"expected exactly one nio floor entry, got ${IoBackendPlatform.registered.map(_.name)}")
        assert(nioEntries.head.priority == 10)
        assert(nioEntries.head.isAvailable, "the nio floor must be unconditionally available")
    }

end JvmPosixBackendSelectionTest
