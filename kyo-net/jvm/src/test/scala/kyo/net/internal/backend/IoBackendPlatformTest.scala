package kyo.net.internal.backend

import kyo.*
import kyo.net.Test
import kyo.net.internal.posix.PosixConstants
import kyo.net.internal.tls.BoringSslProvider
import kyo.net.internal.tls.SslEngineProvider
import kyo.net.internal.tls.TlsProviderPlatform

/** JVM platform-registry probes. They confirm the real JVM `registered` lists and floor probes: the I/O registry now selects the
  * OS-appropriate posix backend (io_uring/epoll on Linux, kqueue on macOS/BSD) over the always-available NioIoDriver floor, while the TLS
  * registry selects BoringSslProvider (priority 30) when staged/loadable with the SSLEngine `jdk` provider (priority 10) as
  * the always-available floor.
  */
class IoBackendPlatformTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    "NioBackend is the always-available JVM I/O floor" in {
        assert(NioBackend.isAvailable)
        assert(NioBackend.name == "nio")
        assert(NioBackend.priority == 10)
    }

    "the JVM I/O registry selects the OS-appropriate posix backend over the NioBackend floor" in {
        // The Nio floor stays registered (priority 10, unconditionally available) so selection can never return Closed.
        val nioEntries = IoBackendPlatform.registered.filter(e => e.name == "nio")
        assert(nioEntries.size == 1, s"expected exactly one nio floor entry, got ${nioEntries.map(_.name)}")
        assert(nioEntries.head.priority == 10)
        assert(nioEntries.head.isAvailable)
        // On a posix host the highest-priority available entry is the OS-appropriate posix backend (kqueue on
        // macOS/BSD, io_uring/epoll on Linux); "nio" is selected only when forced or when no posix syscall is available.
        // A cell-isolation run (KYO_NET_ONLY=<backend>, bridged to -Dkyo.net.backend by kyo.net.Test) forces that backend instead of the
        // natural priority gradient; without accounting for it here, a KYO_NET_ONLY=epoll run on a host with io_uring available would
        // wrongly expect "io_uring", since natural selection never consults the isolation env var.
        val expected =
            sys.env.get("KYO_NET_ONLY").getOrElse {
                if PosixConstants.isMacOrBsd && KqueueBackend.isAvailable then "kqueue"
                else if PosixConstants.isLinux && IoUringBackend.isAvailable then "io_uring"
                else if PosixConstants.isLinux && EpollBackend.isAvailable then "epoll"
                else "nio"
            }
        assert(IoBackendPlatform.selected.name == expected, s"selected=${IoBackendPlatform.selected.name}, expected=$expected")
    }

    "SslEngineProvider is the always-available JVM TLS floor" in {
        assert(SslEngineProvider.isAvailable)
        assert(SslEngineProvider.name == "jdk")
        assert(SslEngineProvider.priority == 10)
    }

    "the JVM TLS registry selects BoringSslProvider when available, with the SSLEngine jdk floor as fallback" in {
        // BoringSslProvider (priority 30) sits above the SSLEngine floor: it is the primary JVM TLS
        // provider when staged/loadable, and SslEngineProvider (jdk, priority 10) is the always-available fallback.
        assert(TlsProviderPlatform.registered.contains(BoringSslProvider))
        assert(TlsProviderPlatform.registered.contains(SslEngineProvider))
        assert(BoringSslProvider.priority > SslEngineProvider.priority)
        // select returns the highest-priority available provider; BoringSSL (30) is primary, jdk (10) the floor.
        // Where BoringSSL is staged/loadable this resolves to "boringssl"; otherwise it falls back to "jdk".
        val expected = TlsProviderPlatform.registered.filter(_.isAvailable).maxBy(_.priority)
        assert(TlsProviderPlatform.selected.name == expected.name)
    }

end IoBackendPlatformTest
