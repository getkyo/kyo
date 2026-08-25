package kyo.net.internal.backend

import kyo.*
import kyo.net.Test
import kyo.net.internal.BoringSslProvider
import kyo.net.internal.SslEngineProvider
import kyo.net.internal.TlsProviderPlatform
import kyo.net.internal.posix.PosixConstants

/** JVM platform-registry probes. They confirm the real JVM `registered` lists and floor probes: the I/O registry now selects the
  * OS-appropriate posix backend (io_uring/epoll on Linux, kqueue on macOS/BSD) over the always-available NioIoDriver floor, while the TLS
  * registry selects BoringSslProvider (priority 30) when staged/loadable with the SSLEngine `jdk` provider (priority 10) as
  * the always-available floor.
  */
class IoBackendPlatformTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    "NioBackend is the always-available JVM I/O floor" in {
        assert(NioBackend.probe == CapabilityOutcome.Available)
        assert(NioBackend.name == "nio")
        assert(NioBackend.priority == 10)
        assert(NioBackend.libraryIds.isEmpty, "the pure-JDK floor stages no native")
    }

    "the JVM I/O registry selects the OS-appropriate posix backend over the NioBackend floor" in {
        // The Nio floor stays registered (priority 10, unconditionally available) so selection can never fail. It registers itself, with no
        // wrapper in between, so there is exactly one entry carrying the name "nio".
        val nioEntries = IoBackendPlatform.registered.filter(e => e.name == "nio")
        assert(nioEntries.size == 1, s"expected exactly one nio floor entry, got ${nioEntries.map(_.name)}")
        assert(nioEntries.head.priority == 10)
        assert(nioEntries.head.probe.isAvailable)
        // On a posix host the highest-priority available entry is the OS-appropriate posix backend (kqueue on
        // macOS/BSD, io_uring/epoll on Linux); "nio" is selected only when forced or when no posix syscall is available.
        // A cell-isolation run (KYO_NET_ONLY=<backend>, bridged to -Dkyo.net.backend by kyo.net.Test) forces that backend instead of the
        // natural priority gradient; without accounting for it here, a KYO_NET_ONLY=epoll run on a host with io_uring available would
        // wrongly expect "io_uring", since natural selection never consults the isolation env var.
        val expected =
            sys.env.get("KYO_NET_ONLY").getOrElse {
                if PosixConstants.isMacOrBsd && KqueueBackend.probe.isAvailable then "kqueue"
                else if PosixConstants.isLinux && IoUringBackend.probe.isAvailable then "io_uring"
                else if PosixConstants.isLinux && EpollBackend.probe.isAvailable then "epoll"
                else "nio"
            }
        assert(IoBackendPlatform.selected.name == expected, s"selected=${IoBackendPlatform.selected.name}, expected=$expected")
    }

    "SslEngineProvider is the always-available JVM TLS floor" in {
        assert(SslEngineProvider.probe == CapabilityOutcome.Available)
        assert(SslEngineProvider.name == "jdk")
        assert(SslEngineProvider.priority == 10)
        assert(SslEngineProvider.libraryIds.isEmpty, "the pure-JDK floor stages no native")
    }

    "the JVM TLS registry selects BoringSslProvider when available, with the SSLEngine jdk floor as fallback" in {
        // BoringSslProvider (priority 30) sits above the SSLEngine floor: it is the primary JVM TLS
        // provider when staged/loadable, and SslEngineProvider (jdk, priority 10) is the always-available fallback.
        assert(TlsProviderPlatform.registered.contains(BoringSslProvider))
        assert(TlsProviderPlatform.registered.contains(SslEngineProvider))
        assert(BoringSslProvider.priority > SslEngineProvider.priority)
        // select returns the highest-priority available provider; BoringSSL (30) is primary, jdk (10) the floor.
        // Where BoringSSL is staged/loadable this resolves to "boringssl"; otherwise it falls back to "jdk".
        val expected = TlsProviderPlatform.registered.filter(_.probe.isAvailable).maxBy(_.priority)
        assert(TlsProviderPlatform.selected.name == expected.name)
    }

    "a BoringSSL that is not staged demotes with the library named, never as an unexplained failure" in {
        // The whole point of the structured outcome: on a host without the bundle the registry falls to the jdk floor AND says which native
        // was missing, rather than reporting a bare false the way the Boolean channel did.
        BoringSslProvider.probe match
            case CapabilityOutcome.Available => succeed
            case CapabilityOutcome.NotBundled(id, platform) =>
                assert(id == "kyonet_boringssl", s"the missing library must be named, got $id")
                assert(platform.nonEmpty, "the platform the library was missing for must be named")
                assert(TlsProviderPlatform.selected.name == "jdk", "an unstaged BoringSSL must fall to the jdk floor")
            case CapabilityOutcome.Unavailable(reason) =>
                assert(reason.contains("boringssl"), s"the reason must name the provider, got $reason")
            case other =>
                fail(s"BoringSSL probed with an unclassified outcome: ${other.describe}")
    }

end IoBackendPlatformTest
