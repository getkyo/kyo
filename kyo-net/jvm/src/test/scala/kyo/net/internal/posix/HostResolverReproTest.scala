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
  * Both `HostResolver.resolve` and `SystemResolver.resolveRaw` return a `Fiber.Unsafe`; results are
  * consumed via `.safe.get` at the test boundary (`.safe.get` is the sanctioned consumption in test source).
  */
class HostResolverReproTest extends Test:

    "HostResolver resolves 'localhost' through the full system resolver (not the loopback shortcut)" in {
        HostResolver.resolve("localhost", PosixConstants.AF_INET).safe.get.map {
            case Result.Success((family, addr)) =>
                // localhost resolves to a loopback literal: 127.0.0.1 (4 bytes, AF_INET) or ::1 (16 bytes, AF_INET6).
                val isV4Loopback = family == PosixConstants.AF_INET && addr.length == 4 && (addr(0) & 0xff) == 127
                val isV6Loopback =
                    family == PosixConstants.AF_INET6 && addr.length == 16 && addr.take(15).forall(_ == 0) && addr(15) == 1
                assert(isV4Loopback || isV6Loopback, s"unexpected localhost resolution: family=$family addr=${addr.toSeq}")
            case other =>
                fail(s"localhost should resolve via the system resolver, got $other")
        }
    }

    "SystemResolver.resolveRaw resolves 'localhost' on the JVM through InetAddress on a dedicated carrier" in {
        SystemResolver.resolveRaw("localhost", PosixConstants.AF_INET).safe.get.map {
            case Result.Success((family, addr)) =>
                val isV4Loopback = family == PosixConstants.AF_INET && addr.length == 4 && (addr(0) & 0xff) == 127
                val isV6Loopback =
                    family == PosixConstants.AF_INET6 && addr.length == 16 && addr.take(15).forall(_ == 0) && addr(15) == 1
                assert(isV4Loopback || isV6Loopback, s"unexpected localhost resolution: family=$family addr=${addr.toSeq}")
            case other =>
                fail(s"SystemResolver.resolveRaw('localhost') should succeed on JVM, got $other")
        }
    }

end HostResolverReproTest
