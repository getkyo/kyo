package kyo.net.internal.posix

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import kyo.*

/** JVM system resolver: resolves a hostname through `java.net.InetAddress`, offloaded as a blocking operation.
  *
  * `InetAddress.getByName` is the canonical JVM resolver: it consults `/etc/hosts`, the platform resolver, and the JVM's own DNS cache, and it
  * is what the retired NIO `InetSocketAddress` path used, so its answers match what kyo-net produced before the unified transport. The call
  * blocks (it can hit the network), so it runs inside `Sync.defer` on the calling fiber's carrier: the carrier parks in the DNS syscall and the
  * scheduler's `BlockingMonitor` (which samples per-thread CPU time) detects the parked carrier, stops routing new work to it, and drains its
  * queue to other workers. So the resolving fiber suspends and no carrier is permanently starved, exactly as the `@Ffi.blocking` syscalls
  * behave. This is the JVM half of the [[HostResolver]] seam; Native uses the `kyo_net_resolve` C shim instead.
  *
  * The first resolved address is returned as `(family, rawBytes)`: `AF_INET` with the 4-byte `Inet4Address`, or `AF_INET6` with the 16-byte
  * `Inet6Address`. `getByName` already returns the resolver's first answer (it honors the platform's A-vs-AAAA ordering), so the family hint is
  * advisory here; the byte length of the returned address determines the family the caller encodes.
  */
private[net] object SystemResolver:

    /** Resolve `host` (for the advisory `familyHint`) to `(family, rawAddrBytes)` or fail `Closed`. Spawns `InetAddress.getByName` on a
      * dedicated blocking carrier via `Fiber.Unsafe.init`; the `BlockingMonitor` compensates so other work is not starved while the carrier
      * parks in the DNS syscall. The caller consumes the result via `onComplete`, never via `.safe.get`. An unknown host (or any resolver error)
      * fails `Closed` cleanly rather than throwing.
      */
    def resolveRaw(host: String, familyHint: Int)(using Frame): Fiber.Unsafe[Result[Closed, HostResolver.Resolved], Any] =
        import AllowUnsafe.embrace.danger
        // Unsafe: InetAddress.getByName blocks the init carrier; BlockingMonitor compensates (the sanctioned DNS block).
        Fiber.Unsafe.init {
            try
                val addr: InetAddress = InetAddress.getByName(host)
                val bytes             = addr.getAddress
                addr match
                    case _: Inet4Address =>
                        Result.succeed((PosixConstants.AF_INET, bytes))
                    case _: Inet6Address =>
                        Result.succeed((PosixConstants.AF_INET6, bytes))
                    case _ =>
                        // Neither v4 nor v6 (cannot normally happen for a resolved host): fail rather than mis-encode.
                        Result.fail(Closed("HostResolver", summon[Frame], s"resolve: unexpected address class for $host"))
                end match
            catch
                case _: UnknownHostException =>
                    Result.fail(Closed("HostResolver", summon[Frame], s"resolve: unknown host $host"))
                case e: SecurityException =>
                    Result.fail(Closed("HostResolver", summon[Frame], s"resolve: resolution of $host denied: ${e.getMessage}"))
                case scala.util.control.NonFatal(e) =>
                    // Any other resolver failure (e.g. a malformed host the JDK rejects) becomes Closed too, so resolution never
                    // surfaces as a panic that could strand the connect promise. Fatal errors (OOM, etc.) still propagate.
                    Result.fail(Closed("HostResolver", summon[Frame], s"resolve: failed to resolve $host: ${e.getMessage}"))
            end try
        }
    end resolveRaw

end SystemResolver
