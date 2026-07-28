package kyo.net.internal.posix

import kyo.*
import kyo.net.NetDnsResolutionException

/** The shared hostname-resolution seam used by [[PosixTransport.encodeInet]] for non-numeric, non-loopback hosts.
  *
  * It does two things: it caches resolved addresses (TTL-bounded) so repeated connects to the same host do not each take a blocking-pool
  * thread, and it delegates the actual system-resolver call to the per-platform [[SystemResolver]] (`java.net.InetAddress` on JVM, the
  * `kyo_net_resolve` C shim on Native, an inert stub on JS where the posix transport is never used). The system call is offloaded as a
  * blocking operation: the resolving fiber suspends while the carrier parks in the DNS syscall, and the scheduler's `BlockingMonitor` drains
  * that carrier's queue to other workers, so no carrier is permanently starved.
  *
  * Resolution returns one A or AAAA raw address bytes plus its family (`AF_INET` / `AF_INET6`), which the caller encodes directly into a
  * `sockaddr`. The family hint is advisory and keeps IPv4/IPv6 selection consistent with `encodeInet` (which dispatches `AF_INET6` only for a
  * host containing ':'): both system resolvers prefer a result of the hint's family when the host has one, but never RESTRICT resolution to it.
  * JVM's `InetAddress.getByName` ignores the hint and returns the resolver's first answer; the Native `getaddrinfo` shim asks with `AF_UNSPEC`
  * and then prefers the hint family among the results, falling back to the first A/AAAA of any family. So a v6-only host (only AAAA records)
  * resolves on both platforms rather than failing on Native, and the common plain-hostname case still prefers IPv4 when the host is dual-stack.
  */
private[net] object HostResolver:

    /** A resolved answer: the address family (`AF_INET` / `AF_INET6`) and the raw network-order address bytes (4 or 16). */
    final case class Resolved(family: Int, addr: Array[Byte])

    /** Upper bound on cached entries. `Cache.Unsafe` enforces this internally (bounded eviction), replacing the manual clear-on-full sweep
      * the raw `ConcurrentHashMap` needed.
      */
    val MaxEntries: Int = 1024

    // Unsafe: the cache is constructed once at object-init with no ambient AllowUnsafe; Cache.Unsafe.init needs the capability here. This
    // is the sanctioned Category-B (object-init, no per-instance construction site) unsafe boundary, not a per-call bridging site.
    private[posix] val cache: Cache.Unsafe[(String, Int), Resolved] =
        import AllowUnsafe.embrace.danger
        Cache.Unsafe.init(maxSize = MaxEntries, expireAfterWrite = kyo.net.dnsTtl().millis)

    /** Resolve `host` for `familyHint` through the platform [[SystemResolver]], caching successful answers. This is the production entry point
      * `encodeInet` calls; see [[resolveWith]] for the cache semantics.
      */
    def resolve(host: String, familyHint: Int)(using AllowUnsafe, Frame): Fiber.Unsafe[Resolved, Abort[NetDnsResolutionException]] =
        resolveWith(host, familyHint, SystemResolver.resolveRaw)

    /** Resolve `host` for `familyHint` through `raw`, caching successful answers in `cache`.
      *
      * A cache hit (entry present and not expired) returns the cached answer WITHOUT calling `raw`, so a repeated connect takes no
      * blocking-pool thread. A miss calls `raw`; on success the answer is cached, on failure nothing is cached (so a transient failure is
      * retried next time). `raw` and `cache` are parameters (rather than always the module's own `SystemResolver`/singleton `cache`) so
      * tests can drive the resolution deterministically with a call-counting fake resolver and, for `cache`, an isolated instance with a
      * short TTL / small maxSize / mock `Clock`. The production `cache` is fixed-TTL at construction (via the `dnsTtl` `StaticFlag`), so a
      * per-test TTL cannot be injected into the shared singleton.
      */
    def resolveWith(
        host: String,
        familyHint: Int,
        raw: (String, Int) => Fiber.Unsafe[Result[NetDnsResolutionException, Resolved], Any],
        cache: Cache.Unsafe[(String, Int), Resolved] = HostResolver.cache
    )(using AllowUnsafe, Frame): Fiber.Unsafe[Resolved, Abort[NetDnsResolutionException]] =
        val key = (host, familyHint)
        cache.get(key) match
            case Present(resolved) =>
                // Cache hit: already-complete fiber, no resolver call, no blocking thread.
                Fiber.Unsafe.fromResult(Result.succeed(resolved))
            case Absent =>
                // Miss: dispatch to raw (which spawns the blocking carrier) and complete out when raw completes.
                val out = Promise.Unsafe.init[Resolved, Abort[NetDnsResolutionException]]()
                raw(host, familyHint).onComplete {
                    case Result.Success(innerPending) =>
                        // innerPending: Result[NetDnsResolutionException, Resolved] < Any. On a well-formed raw resolver this is a pure
                        // value; .eval extracts it.
                        innerPending.eval match
                            case Result.Success(resolved) =>
                                discard(cache.add(key, resolved))
                                out.completeDiscard(Result.succeed(resolved))
                            case Result.Failure(e) =>
                                // Failure: pass through without caching.
                                out.completeDiscard(Result.fail(e))
                            case Result.Panic(e) =>
                                out.completeDiscard(Result.panic(e))
                    case Result.Failure(e) =>
                        // Defensive: raw fiber infrastructure failure (should not occur for a well-formed raw resolver: SystemResolver's
                        // three impls are all Fiber.Unsafe.init/fromResult, whose own Any failure channel carries no Abort, so a
                        // Result.Failure here is structurally unreachable). The match below still gives this arm a defensive
                        // typed-leaf-or-panic posture: a typed NetDnsResolutionException passes through, anything else panics.
                        e match
                            case ex: NetDnsResolutionException => out.completeDiscard(Result.fail(ex))
                            case t: Throwable                  => out.completeDiscard(Result.panic(t))
                            case other =>
                                out.completeDiscard(Result.panic(NetDnsResolutionException(
                                    host,
                                    s"unexpected non-typed fiber failure: $other"
                                )))
                    case Result.Panic(e) =>
                        out.completeDiscard(Result.panic(e))
                }
                out
        end match
    end resolveWith

end HostResolver
