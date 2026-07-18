package kyo.net.internal.posix

import kyo.*
import kyo.net.Test

/** Reproduce-before-fix guard for the Native `kyo_net_resolve` C shim's family handling (cross-platform DNS consistency with JVM).
  *
  * Forcing `hints.ai_family = family_hint` into `getaddrinfo` RESTRICTS the resolver to that family. With the plain-hostname hint (`AF_INET`, the family
  * `encodeInet` requests for any host without a ':') it would be RESTRICTED to A records, so a host that has only an AAAA record (a
  * v6-only host) would fail to resolve on Native even though JVM's `InetAddress.getByName` resolves it (JVM ignores the hint and returns the
  * resolver's first answer). The shim instead asks `getaddrinfo` with `AF_UNSPEC` and applies `family_hint` only as a preference among the results,
  * matching JVM.
  *
  * The v6-only case is exercised network-free with the IPv6 loopback LITERAL `::1` resolved under the `AF_INET` hint: a forced
  * `getaddrinfo("::1", ai_family = AF_INET)` rejects the v6 literal (a non-zero `EAI_*`), the exact symptom a v6-only host hits;
  * `AF_UNSPEC` accepts it and returns the v6 address. The symmetric `127.0.0.1` literal under the `AF_INET6` hint exercises the fallback (the
  * preferred family is absent, so the only available family is taken). Both use numeric literals so they are fully deterministic and depend on
  * no `/etc/hosts` entries or network. (Literals reach the resolver only here in the test; production encodes them on the synchronous fast path
  * before the resolver, but the shim binding's family behaviour is what this pins.)
  *
  * `SystemResolver.resolveRaw` returns a `Fiber.Unsafe`; results are consumed via `.safe.get` at the test boundary (`.safe.get` is the
  * sanctioned consumption in test source).
  */
class NativeResolverTest extends Test:

    private def v6Loopback(addr: Array[Byte]): Boolean =
        addr.length == 16 && addr.take(15).forall(_ == 0) && addr(15) == 1

    private def v4Loopback(addr: Array[Byte]): Boolean =
        addr.length == 4 && (addr(0) & 0xff) == 127

    "Native kyo_net_resolve shim" - {
        "resolves the IPv6 loopback literal under an AF_INET hint (v6-only host parity with JVM; a forced AF_INET would reject it)" in {
            import AllowUnsafe.embrace.danger
            // The v4 hint must NOT restrict resolution: ::1 has no A form, so a forced AF_INET would fail. AF_UNSPEC + preference resolves it.
            SystemResolver.resolveRaw("::1", PosixConstants.AF_INET).safe.get.map {
                case Result.Success(HostResolver.Resolved(family, addr)) =>
                    assert(family == PosixConstants.AF_INET6, s"expected AF_INET6 for ::1, got family=$family")
                    assert(v6Loopback(addr), s"expected ::1 bytes, got ${addr.toSeq}")
                case other =>
                    fail(s"::1 should resolve even under an AF_INET hint (v6-only parity), got $other")
            }
        }

        "resolves the IPv4 loopback literal under an AF_INET6 hint (preferred family absent: falls back to the only family)" in {
            import AllowUnsafe.embrace.danger
            // The v6 hint must NOT restrict resolution: 127.0.0.1 has no AAAA form, so a forced AF_INET6 would fail. The fallback takes the v4.
            SystemResolver.resolveRaw("127.0.0.1", PosixConstants.AF_INET6).safe.get.map {
                case Result.Success(HostResolver.Resolved(family, addr)) =>
                    assert(family == PosixConstants.AF_INET, s"expected AF_INET fallback for 127.0.0.1, got family=$family")
                    assert(v4Loopback(addr), s"expected 127.0.0.1 bytes, got ${addr.toSeq}")
                case other =>
                    fail(s"127.0.0.1 should resolve even under an AF_INET6 hint (fallback), got $other")
            }
        }

        "honours the hint as a preference: AF_INET on 127.0.0.1 returns v4, AF_INET6 on ::1 returns v6" in {
            import AllowUnsafe.embrace.danger
            for
                v4 <- SystemResolver.resolveRaw("127.0.0.1", PosixConstants.AF_INET).safe.get
                v6 <- SystemResolver.resolveRaw("::1", PosixConstants.AF_INET6).safe.get
            yield
                val HostResolver.Resolved(f4, a4) = v4.getOrElse(fail(s"127.0.0.1 under AF_INET failed: $v4"))
                val HostResolver.Resolved(f6, a6) = v6.getOrElse(fail(s"::1 under AF_INET6 failed: $v6"))
                assert(f4 == PosixConstants.AF_INET && v4Loopback(a4), s"expected v4 loopback, got ($f4, ${a4.toSeq})")
                assert(f6 == PosixConstants.AF_INET6 && v6Loopback(a6), s"expected v6 loopback, got ($f6, ${a6.toSeq})")
            end for
        }
    }

end NativeResolverTest
