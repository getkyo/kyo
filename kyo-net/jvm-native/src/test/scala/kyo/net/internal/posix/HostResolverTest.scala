package kyo.net.internal.posix

import java.util.concurrent.atomic.AtomicInteger
import kyo.*
import kyo.net.NetDnsResolutionException
import kyo.net.Test
import kyo.scheduler.IOPromise

/** Tests for the shared [[HostResolver]] seam: the TTL-bounded cache and the resolve-or-fail contract.
  *
  * These drive the cache through `resolveWith` with a DETERMINISTIC, call-counting fake raw resolver, so they run identically on JVM, JS, and
  * Native without any real DNS query or network access. The real system-resolver round-trip (`java.net.InetAddress` on JVM) is covered by the
  * JVM-placed `HostResolverReproTest`, which needs the actual JVM resolver. The cache/seam logic verified here is platform-agnostic.
  */
class HostResolverTest extends Test:

    private val v4 = HostResolver.Resolved(PosixConstants.AF_INET, Array[Byte](93.toByte, 184.toByte, 216.toByte, 34.toByte))
    private val v6 = HostResolver.Resolved(PosixConstants.AF_INET6, Array.tabulate[Byte](16)(i => (i + 1).toByte))

    // The HostResolver cache is a process-global keyed by (host, familyHint). Each leaf uses a freshly minted host so it is COLD by construction:
    // a per-run nonce plus a monotonic sequence guarantees the key was never resolved before (in this run or a prior one reusing the JVM), so no
    // production cache-clearing affordance is needed to observe a cold cache.
    private val runNonce                       = java.lang.System.nanoTime()
    private val hostSeq                        = new AtomicInteger(0)
    private def freshHost(tag: String): String = s"$tag-${hostSeq.incrementAndGet()}-$runNonce"

    /** A fake resolver that counts how many times it is actually invoked and returns a fixed answer. The count proves whether the cache served
      * a request without re-querying. The raw resolver returns a `Fiber.Unsafe`; the counter increment is synchronous and the result is
      * consumed via `.safe.get` at the test boundary (`.safe.get` is the sanctioned consumption in test source).
      */
    private def counting(answer: Result[NetDnsResolutionException, HostResolver.Resolved])
        : (AtomicInteger, (String, Int) => Fiber.Unsafe[Result[NetDnsResolutionException, HostResolver.Resolved], Any]) =
        import AllowUnsafe.embrace.danger
        val calls = new AtomicInteger(0)
        val raw: (String, Int) => Fiber.Unsafe[Result[NetDnsResolutionException, HostResolver.Resolved], Any] = (_, _) =>
            discard(calls.incrementAndGet())
            Fiber.Unsafe.fromResult(Result.succeed(answer))
        (calls, raw)
    end counting

    "HostResolver" - {
        "resolves through the raw resolver and returns its answer (family + bytes)" in {
            import AllowUnsafe.embrace.danger
            val (calls, raw) = counting(Result.succeed(v4))
            HostResolver.resolveWith(freshHost("resolve"), PosixConstants.AF_INET, raw).safe.get.map {
                case HostResolver.Resolved(family, addr) =>
                    assert(family == PosixConstants.AF_INET)
                    assert(addr.sameElements(v4.addr))
                    assert(calls.get() == 1, s"expected exactly one resolver call, got ${calls.get()}")
            }
        }

        "a second resolve of the same host hits the cache and does NOT re-query the resolver" in {
            import AllowUnsafe.embrace.danger
            val (calls, raw) = counting(Result.succeed(v4))
            val host         = freshHost("cache-hit")
            for
                first  <- HostResolver.resolveWith(host, PosixConstants.AF_INET, raw).safe.get
                second <- HostResolver.resolveWith(host, PosixConstants.AF_INET, raw).safe.get
            yield
                assert(first.family == PosixConstants.AF_INET && first.addr.sameElements(v4.addr), s"first mismatch: $first")
                assert(second.family == PosixConstants.AF_INET && second.addr.sameElements(v4.addr), s"second mismatch: $second")
                // The cache served the second call: the resolver ran exactly once for two resolves.
                assert(calls.get() == 1, s"expected the second resolve to hit the cache (1 call), got ${calls.get()}")
            end for
        }

        "a resolver FAILURE is not cached: the next resolve retries" in {
            import AllowUnsafe.embrace.danger
            val (calls, raw) = counting(Result.fail(NetDnsResolutionException("test", "boom")))
            val host         = freshHost("fail-not-cached")
            for
                first  <- Abort.run[NetDnsResolutionException](HostResolver.resolveWith(host, PosixConstants.AF_INET, raw).safe.get)
                second <- Abort.run[NetDnsResolutionException](HostResolver.resolveWith(host, PosixConstants.AF_INET, raw).safe.get)
            yield
                assert(first.isFailure, s"expected failure, got $first")
                assert(second.isFailure, s"expected failure, got $second")
                assert(calls.get() == 2, s"expected a failure to be retried (2 calls), got ${calls.get()}")
            end for
        }

        "distinct hosts are cached independently" in {
            val callsByHost = new java.util.concurrent.ConcurrentHashMap[String, Array[Byte]]()
            val answerSeq   = new AtomicInteger(0)
            val raw: (String, Int) => Fiber.Unsafe[Result[NetDnsResolutionException, HostResolver.Resolved], Any] = (host, _) =>
                import AllowUnsafe.embrace.danger
                // Each raw call mints a distinct synthetic v4 answer (a monotonic last byte); record it per host so we can assert per-host caching.
                val answer = Array[Byte](10, 0, 0, answerSeq.incrementAndGet().toByte)
                discard(callsByHost.put(host, answer))
                Fiber.Unsafe.fromResult(Result.succeed(Result.succeed(HostResolver.Resolved(PosixConstants.AF_INET, answer))))
            val hostA = freshHost("distinctA")
            val hostB = freshHost("distinctB")
            import AllowUnsafe.embrace.danger
            for
                a <- HostResolver.resolveWith(hostA, PosixConstants.AF_INET, raw).safe.get
                b <- HostResolver.resolveWith(hostB, PosixConstants.AF_INET, raw).safe.get
            yield
                // Each host's resolved bytes match the distinct answer the resolver minted for THAT host: the cache keys on the host, no collision.
                assert(a.addr.sameElements(callsByHost.get(hostA)), s"hostA addr ${a.addr.toSeq}")
                assert(b.addr.sameElements(callsByHost.get(hostB)), s"hostB addr ${b.addr.toSeq}")
                assert(!a.addr.sameElements(b.addr), "distinct hosts must resolve to distinct cached answers")
            end for
        }

        "an IPv6 answer round-trips family and 16 bytes through the cache" in {
            import AllowUnsafe.embrace.danger
            val (calls, raw) = counting(Result.succeed(v6))
            val host         = freshHost("v6")
            for
                first  <- HostResolver.resolveWith(host, PosixConstants.AF_INET6, raw).safe.get
                second <- HostResolver.resolveWith(host, PosixConstants.AF_INET6, raw).safe.get
            yield
                assert(first.family == PosixConstants.AF_INET6)
                assert(first.addr.length == 16 && first.addr.sameElements(v6.addr))
                assert(second.family == PosixConstants.AF_INET6 && second.addr.sameElements(v6.addr), s"v6 second mismatch: $second")
                assert(calls.get() == 1, s"v6 second resolve should hit the cache, got ${calls.get()} calls")
            end for
        }

        "a genuinely-pending raw fiber completes via onComplete when the underlying fiber resolves" in {
            import AllowUnsafe.embrace.danger
            val rawPromise = new IOPromise[Any, Result[NetDnsResolutionException, HostResolver.Resolved]]
            val raw: (String, Int) => Fiber.Unsafe[Result[NetDnsResolutionException, HostResolver.Resolved], Any] = (_, _) =>
                rawPromise.asInstanceOf[Fiber.Unsafe[Result[NetDnsResolutionException, HostResolver.Resolved], Any]]
            val fiber = HostResolver.resolveWith(freshHost("pending"), PosixConstants.AF_INET, raw)
            // The fiber is not yet done: raw has not completed.
            assert(!fiber.done(), "fiber must not be done before raw resolves")
            // Complete raw; the onComplete callback in resolveWith fires and completes the returned fiber.
            rawPromise.completeDiscard(Result.succeed(Result.succeed(v4): Result[NetDnsResolutionException, HostResolver.Resolved]))
            fiber.safe.get.map {
                case HostResolver.Resolved(family, addr) =>
                    assert(family == PosixConstants.AF_INET)
                    assert(addr.sameElements(v4.addr))
            }
        }

        "a panic from the raw resolver surfaces as Result.panic at the caller (not swallowed or converted to failure)" in {
            import AllowUnsafe.embrace.danger
            // Build a raw resolver whose fiber completes with a panic at the outer level (the fiber itself panics),
            // exercising the defensive case Result.Panic(e) arm in resolveWith's onComplete.
            val panicEx    = new RuntimeException("raw-resolver-panic-sentinel")
            val rawPromise = new IOPromise[Any, Result[NetDnsResolutionException, HostResolver.Resolved]]
            val raw: (String, Int) => Fiber.Unsafe[Result[NetDnsResolutionException, HostResolver.Resolved], Any] = (_, _) =>
                rawPromise.asInstanceOf[Fiber.Unsafe[Result[NetDnsResolutionException, HostResolver.Resolved], Any]]
            val fiber = HostResolver.resolveWith(freshHost("panic"), PosixConstants.AF_INET, raw)
            // Complete the raw promise with a panic at the OUTER level (the fiber itself panics).
            rawPromise.completeDiscard(Result.Panic(panicEx))
            // The panic must NOT be swallowed or converted to a failure: use getResult to capture the
            // panic as a Result value without turning it into an Abort.panic that escapes the test boundary.
            fiber.safe.getResult.map { result =>
                result match
                    case Result.Panic(ex) =>
                        assert(ex eq panicEx, s"expected the sentinel exception, got $ex")
                        assert(ex.getMessage == "raw-resolver-panic-sentinel")
                    case other =>
                        fail(s"expected Result.Panic(panicEx), got $other -- the panic was swallowed or converted")
                end match
            }
        }

        "concurrent resolves of the same uncached host each complete with the resolved value and a single cache entry is stored" in {
            // A counting resolver: both concurrent misses will each call it; the count is bounded by the number
            // of concurrent callers (2 here, since both see the cache empty before either stores an answer).
            import AllowUnsafe.embrace.danger
            val (calls, raw) = counting(Result.succeed(v4))
            val host         = freshHost("concurrent")
            // Drive two resolves concurrently via Async.zip so both start before either stores a cache entry.
            Async.zip(
                HostResolver.resolveWith(host, PosixConstants.AF_INET, raw).safe.get,
                HostResolver.resolveWith(host, PosixConstants.AF_INET, raw).safe.get
            ).map { case (r1, r2) =>
                // Both callers observe the expected resolved value.
                assert(r1.family == PosixConstants.AF_INET && r1.addr.sameElements(v4.addr), s"first result mismatch: $r1")
                assert(r2.family == PosixConstants.AF_INET && r2.addr.sameElements(v4.addr), s"second result mismatch: $r2")
                assert(r1.addr.sameElements(r2.addr), "both concurrent callers must observe the same resolved bytes")
                // Both callers saw a miss (cache was cold when they checked), so raw was called at most twice.
                // The cache now holds exactly one consistent entry for the key.
                val callCount = calls.get()
                assert(callCount <= 2, s"raw resolver called $callCount times; expected at most 2 (one per concurrent miss)")
                assert(callCount >= 1, s"raw resolver was never called; expected at least 1")
            }
        }

        "a DNS resolution failure surfaces NetDnsResolutionException on the typed Abort row (no Result[Closed, _] anywhere in the signature)" in {
            import AllowUnsafe.embrace.danger
            val host     = freshHost("dns-failure")
            val (_, raw) = counting(Result.fail(NetDnsResolutionException(host, "simulated failure")))
            Abort.run[NetDnsResolutionException](HostResolver.resolveWith(host, PosixConstants.AF_INET, raw).safe.get).map {
                case Result.Failure(e) =>
                    assert(e.host == host, s"expected host $host, got ${e.host}")
                case other =>
                    fail(s"expected a NetDnsResolutionException failure, got $other")
            }
        }

        "the Cache.Unsafe-backed cache respects expireAfterWrite (a within-TTL resolve hits the cache, a past-TTL resolve re-queries)" in {
            import AllowUnsafe.embrace.danger
            Clock.withTimeControl { tc =>
                for
                    clock <- Clock.get
                    testCache = Cache.Unsafe.init[(String, Int), HostResolver.Resolved](
                        maxSize = 8,
                        expireAfterWrite = 20.millis,
                        clock = clock.unsafe
                    )
                    (calls, raw) = counting(Result.succeed(v4))
                    host         = freshHost("ttl-eviction")
                    _ <- HostResolver.resolveWith(host, PosixConstants.AF_INET, raw, testCache).safe.get
                    _ <- HostResolver.resolveWith(host, PosixConstants.AF_INET, raw, testCache).safe.get
                    _ <- tc.advance(50.millis, Duration.Zero)
                    _ <- HostResolver.resolveWith(host, PosixConstants.AF_INET, raw, testCache).safe.get
                yield assert(
                    calls.get() == 2,
                    s"expected 2 raw calls (initial miss + post-TTL miss; the middle within-TTL call hit the cache), got ${calls.get()}"
                )
                end for
            }
        }

        "the Cache.Unsafe-backed cache is bounded: resolving more than maxSize distinct hosts never grows it past maxSize" in {
            import AllowUnsafe.embrace.danger
            val testCache =
                Cache.Unsafe.init[(String, Int), HostResolver.Resolved](maxSize = 3, expireAfterWrite = HostResolver.MaxEntries.seconds)
            val (_, raw) = counting(Result.succeed(v4))
            for
                _ <- HostResolver.resolveWith(freshHost("bound-1"), PosixConstants.AF_INET, raw, testCache).safe.get
                _ <- HostResolver.resolveWith(freshHost("bound-2"), PosixConstants.AF_INET, raw, testCache).safe.get
                _ <- HostResolver.resolveWith(freshHost("bound-3"), PosixConstants.AF_INET, raw, testCache).safe.get
                _ <- HostResolver.resolveWith(freshHost("bound-4"), PosixConstants.AF_INET, raw, testCache).safe.get
            yield assert(
                testCache.stats.entries <= 3,
                s"cache grew past maxSize=3: ${testCache.stats.entries} entries (stats: ${testCache.stats})"
            )
            end for
        }
    }

end HostResolverTest
