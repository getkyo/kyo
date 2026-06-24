package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi

/** Native system resolver: resolves a hostname through the `kyo_net_resolve` C shim, offloaded as a blocking operation.
  *
  * The shim wraps `getaddrinfo` (whose recursive `struct addrinfo` cannot be bound directly) and hands back the first A or AAAA raw address.
  * It is bound `@Ffi.blocking` via [[ResolveBindings]], so the Native downcall runs on a scheduler carrier the `BlockingMonitor` compensates
  * for (it drains the parked worker's queue), and the resolving fiber suspends rather than starving the scheduler. This is the Native half of
  * the [[HostResolver]] seam; JVM uses `java.net.InetAddress` instead.
  *
  * The out-parameters are a 16-byte address buffer (sized for the larger `AF_INET6` case) and a 1-element family buffer. On success the shim
  * returns 0 and fills both; the resolver then copies out only the family's address width (4 for `AF_INET`, 16 for `AF_INET6`). A non-zero
  * return is a `getaddrinfo` error, surfaced as `Closed`.
  */
private[net] object SystemResolver:

    private def bindings(using AllowUnsafe): ResolveBindings = Ffi.load[ResolveBindings]

    /** Resolve `host` for `familyHint` to `(family, rawAddrBytes)` or fail `Closed`. Spawns the `@Ffi.blocking kyo_net_resolve` shim on a
      * dedicated carrier via `Fiber.Unsafe.init`; on Native the shim completes inline (BlockingBridge runs the call synchronously), so the
      * result is extracted via `done()/poll()` inside the init body. Buffers are allocated and closed inside the init body's `finally` block.
      * The caller consumes the result via `onComplete`, never via `.safe.get`.
      */
    def resolveRaw(host: String, familyHint: Int)(using Frame): Fiber.Unsafe[Result[Closed, HostResolver.Resolved], Any] =
        import AllowUnsafe.embrace.danger // TODO take as implicit
        // Unsafe: @Ffi.blocking kyo_net_resolve runs inline on Native (BlockingBridge); extract via done()/poll(), no .safe.get.
        Fiber.Unsafe.init {
            val outAddr   = Buffer.alloc[Byte](16)
            val outFamily = Buffer.alloc[Int](1)
            try
                val rcFiber = bindings.kyo_net_resolve(host, familyHint, outAddr, outFamily)
                // @Ffi.blocking on Native: the shim completes inline via BlockingBridge.run; rcFiber.done() is always true here.
                val rc: Int = if rcFiber.done() then
                    rcFiber.poll() match
                        case Present(Result.Success(v)) => v.eval
                        case _                          => -1
                else -1 // should not occur on Native; treat as error
                if rc != 0 then
                    Result.fail(Closed("HostResolver", summon[Frame], s"resolve: getaddrinfo failed for $host (error $rc)"))
                else
                    val family = outFamily.get(0)
                    val width  = if family == PosixConstants.AF_INET6 then 16 else 4
                    val bytes  = Buffer.copyToArray[Byte](outAddr, 0, width)
                    Result.succeed((family, bytes))
                end if
            finally
                outAddr.close()
                outFamily.close()
            end try
        }
    end resolveRaw

end SystemResolver
