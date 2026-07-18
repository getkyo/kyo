package kyo.net.internal.posix

import kyo.*
import kyo.net.Test

/** Reproduce-before-fix guard for the offloaded hostname DNS resolution fix.
  *
  * Before the fix, `PosixTransport.encodeInet` resolved only numeric IPv4/IPv6 literals and the
  * well-known loopback NAMES. Any OTHER non-numeric hostname (including "localhost" routed through the
  * FULL system resolver rather than the loopback shortcut) had no resolution path, so a connect to it
  * failed `Closed("unresolvable address")` even though the host is perfectly resolvable via /etc/hosts.
  *
  * This test drives the shared resolution seam (`HostResolver`) directly so it does not depend on any
  * external network: it resolves "localhost" through the actual system resolver (which every POSIX host
  * answers from /etc/hosts) and asserts a loopback address comes back. JVM-placed because it exercises
  * the JVM system resolver (`java.net.InetAddress`); the cache/seam logic is covered cross-platform.
  *
  * `HostResolver.resolve` and `SystemResolver.resolveRaw` return different consumption shapes: `resolve` is Abort-native
  * (`Fiber.Unsafe[Resolved, Abort[NetDnsResolutionException]]`), so `.safe.get` yields the resolved value directly on success and aborts the
  * test on failure; `SystemResolver.resolveRaw` returns a `Fiber.Unsafe[Result[NetDnsResolutionException, Resolved], Any]` (its own,
  * non-Abort-native shape), so `.safe.get` yields the inner `Result` to pattern-match. Both are consumed via `.safe.get` at the test boundary
  * (`.safe.get` is the sanctioned consumption in test source).
  */
class HostResolverReproTest extends Test:

    "HostResolver resolves 'localhost' through the full system resolver (not the loopback shortcut)" in {
        import AllowUnsafe.embrace.danger
        HostResolver.resolve("localhost", PosixConstants.AF_INET).safe.get.map {
            case HostResolver.Resolved(family, addr) =>
                val isV4Loopback = family == PosixConstants.AF_INET && addr.length == 4 && (addr(0) & 0xff) == 127
                val isV6Loopback =
                    family == PosixConstants.AF_INET6 && addr.length == 16 && addr.take(15).forall(_ == 0) && addr(15) == 1
                assert(isV4Loopback || isV6Loopback, s"unexpected localhost resolution: family=$family addr=${addr.toSeq}")
        }
    }

    "SystemResolver.resolveRaw resolves 'localhost' on the JVM through InetAddress on a dedicated carrier" in {
        import AllowUnsafe.embrace.danger
        SystemResolver.resolveRaw("localhost", PosixConstants.AF_INET).safe.get.map {
            case Result.Success(HostResolver.Resolved(family, addr)) =>
                val isV4Loopback = family == PosixConstants.AF_INET && addr.length == 4 && (addr(0) & 0xff) == 127
                val isV6Loopback =
                    family == PosixConstants.AF_INET6 && addr.length == 16 && addr.take(15).forall(_ == 0) && addr(15) == 1
                assert(isV4Loopback || isV6Loopback, s"unexpected localhost resolution: family=$family addr=${addr.toSeq}")
            case other =>
                fail(s"SystemResolver.resolveRaw('localhost') should succeed on JVM, got $other")
        }
    }

end HostResolverReproTest
