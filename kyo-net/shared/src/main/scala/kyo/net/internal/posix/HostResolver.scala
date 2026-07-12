package kyo.net.internal.posix

import java.util.concurrent.ConcurrentHashMap
import kyo.*
import kyo.scheduler.IOPromise

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
    type Resolved = (Int, Array[Byte]) // TODO use a proper case class

    /** A raw system resolver: resolve `host` for `familyHint` to a [[Resolved]] or fail `Closed`. The underlying call blocks on a dedicated
      * carrier (spawned via `Fiber.Unsafe.init`), so the result is a `Fiber.Unsafe` that can be consumed via `onComplete` without re-entering
      * the effect system. The seam is a function so tests can substitute a deterministic, call-counting resolver without a real DNS query.
      */
    type RawResolver = (String, Int) => Fiber.Unsafe[
        Result[Closed, Resolved],
        Any
    ] // TODO can we remove this kijnd of alias? why the fuck would the fiber return a Reusult instead of trackign Abort?

    /** Default cache TTL: 30 seconds. Long enough that a burst of connects to the same host resolves once, short enough that a changed DNS
      * record is picked up promptly. A successful resolution is cached for this long; failures are NOT cached (a transient resolver hiccup
      * should not pin a host as unresolvable).
      */
    val DefaultTtl: Duration = 30.seconds // TODO make this a StaticFlag

    /** Upper bound on cached entries. When inserting into a full cache the whole cache is cleared first (a simple, allocation-free bound that
      * never lets the map grow without limit); 1024 distinct hosts is far beyond any normal client's working set, so the clear is rare.
      */
    val MaxEntries: Int = 1024

    /** One cached resolution: the resolved family, the raw address bytes, and the monotonic-nanos deadline after which it is stale. */
    final private case class Entry(family: Int, addr: Array[Byte], expiresAtNanos: Long)

    // Concurrent-collection audit: a raw ConcurrentHashMap caching DNS resolutions by (host, familyHint). kyo has no concurrent-map
    // type, and the resolver runs in the unsafe Fiber tier (it returns Fiber.Unsafe and reads the monotonic clock without suspension), so an
    // effect-based map does not fit. Retained as a documented no-equivalent exception; entries expire by their monotonic-nanos deadline.
    // TODO this can produce a leak? can we use kyo.Cache?
    private val cache = new ConcurrentHashMap[(String, Int), Entry]() // TODO Why is this not using kyo.Cache?

    /** Resolve `host` for `familyHint` through the platform [[SystemResolver]], caching successful answers. This is the production entry point
      * `encodeInet` calls; see [[resolveWith]] for the cache semantics.
      */
    def resolve(host: String, familyHint: Int)(using Frame): Fiber.Unsafe[Result[Closed, Resolved], Any] =
        resolveWith(host, familyHint, SystemResolver.resolveRaw, DefaultTtl)

    /** Resolve `host` for `familyHint` through `raw`, caching successful answers for `ttl`.
      *
      * A cache hit (entry present and not past its deadline) returns the cached answer WITHOUT calling `raw`, so a repeated connect takes no
      * blocking-pool thread. A miss (or an expired entry) calls `raw`; on success the answer is cached with a fresh deadline, on failure
      * nothing is cached (so a transient failure is retried next time). The `ttl` and `raw` parameters exist so tests can drive the cache
      * deterministically with a call-counting fake resolver.
      */
    def resolveWith(
        host: String,
        familyHint: Int,
        raw: RawResolver,
        ttl: Duration
    )(using Frame): Fiber.Unsafe[Result[Closed, Resolved], Any] =
        // Unsafe: the resolver works entirely in the unsafe Fiber/Clock tier (it returns a Fiber.Unsafe, reads the monotonic clock, and consults
        // the unsafe cache) but its caller threads only a Frame, so the bridge supplies the AllowUnsafe these unsafe ops require; no I/O escapes.
        import AllowUnsafe.embrace.danger // TODO take the AllowUnsafe implicit in the method signature
        val key    = (host, familyHint)
        val cached = cache.get(key)
        if cached != null && Clock.live.unsafe.nowMonotonic().toNanos < cached.expiresAtNanos then
            // Cache hit: already-complete fiber, no resolver call, no blocking thread.
            Fiber.Unsafe.fromResult(Result.succeed(Result.succeed((cached.family, cached.addr)): Result[Closed, Resolved]))
        else
            // Miss or stale: dispatch to raw (which spawns the blocking carrier) and complete out when raw completes.
            val out = new IOPromise[Any, Result[Closed, Resolved]] // TODO why not a unsafe Promise?
            raw(host, familyHint).onComplete {
                case Result.Success(innerPending) =>
                    // innerPending: Result[Closed, Resolved] < Any. On a well-formed raw resolver this is a pure value; .eval extracts it.
                    innerPending.eval match
                        case success @ Result.Success((family: Int, addr: Array[Byte])) =>
                            store(key, family, addr, ttl)
                            out.completeDiscard(Result.succeed(success))
                        case other =>
                            // Failure or panic: pass through without caching.
                            out.completeDiscard(Result.succeed(other))
                case Result.Failure(e) =>
                    // Defensive: raw fiber infrastructure failure (should not occur for a well-formed raw resolver). A Closed surfaces as a
                    // resolver failure exactly as before; any other (unreachable) value becomes a clean Panic rather than a ClassCastException.
                    out.completeDiscard(Result.succeed(HostResolver.asClosedOrPanic(e, summon[Frame])))
                case Result.Panic(e) =>
                    out.completeDiscard(Result.succeed(Result.panic(e)))
            }
            out.asInstanceOf[Fiber.Unsafe[Result[Closed, Resolved], Any]]
        end if
    end resolveWith

    /** Store a successful resolution with a deadline `ttl` from now. Clears the cache first when it is at the bound, so it never grows past
      * [[MaxEntries]].
      */
    private def store(key: (String, Int), family: Int, addr: Array[Byte], ttl: Duration)(using AllowUnsafe): Unit =
        if cache.size() >= MaxEntries && !cache.containsKey(key) then cache.clear()
        val expiresAt = Clock.live.unsafe.nowMonotonic().toNanos + ttl.toNanos
        discard(cache.put(key, Entry(family, addr, expiresAt)))
    end store

    /** Map a value off a `Fiber.Unsafe[..., Any]` failure channel to a `Result[Closed, Nothing]`. The posix transport's resolver and
      * connect/listen fibers all carry an `Any` failure channel (an `IOPromise` infrastructure failure, never an application error for a
      * well-formed fiber), so a `Result.Failure(value)` from one of them holds an untyped value. A `Closed` is the only failure those fibers
      * ever produce on a reachable path, so it becomes `Result.fail(closed)` exactly as before. Any other value is unreachable today, but a
      * blind `asInstanceOf[Closed]` would turn it into a `ClassCastException`; instead route it to a clean `Result.panic` (the value itself
      * when it is a `Throwable`, otherwise wrapped in a `Closed` describing the unexpected failure), mirroring each site's adjacent Panic arm.
      */
    private[posix] def asClosedOrPanic(value: Any, frame: Frame): Result[Closed, Nothing] =
        given Frame = frame
        value match
            case closed: Closed => Result.fail(closed)
            case t: Throwable   => Result.panic(t)
            case other          => Result.panic(Closed("HostResolver", frame, s"unexpected non-Closed fiber failure: $other"))
        end match
    end asClosedOrPanic

end HostResolver
