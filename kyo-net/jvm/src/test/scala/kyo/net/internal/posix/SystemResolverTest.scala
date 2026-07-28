package kyo.net.internal.posix

import kyo.*
import kyo.net.NetDnsResolutionException
import kyo.net.Test

/** [[SystemResolver]]'s failure arms forward the real underlying exception as the [[NetDnsResolutionException]] cause instead of discarding
  * it into a hand-authored message string.
  *
  * The unresolvable host uses the RFC 2606 reserved `.invalid` TLD, which a conformant resolver answers with NXDOMAIN
  * (`java.net.UnknownHostException`), deterministically and without depending on a live network path to a specific server.
  */
class SystemResolverTest extends Test:

    "resolveRaw fails NetDnsResolutionException carrying the real UnknownHostException as its cause" in {
        import AllowUnsafe.embrace.danger
        SystemResolver.resolveRaw("nonexistent.invalid", PosixConstants.AF_INET).safe.get.map {
            case Result.Failure(ex: NetDnsResolutionException) =>
                assert(
                    ex.getCause.isInstanceOf[java.net.UnknownHostException],
                    s"getCause must be the real UnknownHostException, got ${ex.getCause}"
                )
            case other =>
                fail(s"resolving 'nonexistent.invalid' must fail NetDnsResolutionException, got $other")
        }
    }

end SystemResolverTest
