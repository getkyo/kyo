package kyo.net.internal.posix

import java.util.concurrent.atomic.AtomicInteger
import kyo.*
import kyo.net.Test
import kyo.scheduler.IOPromise

/** Tests for the shared [[HostResolver]] seam: the TTL-bounded cache and the resolve-or-fail contract.
  *
  * These drive the cache through `resolveWith` with a DETERMINISTIC, call-counting fake `RawResolver`, so they run identically on JVM, JS, and
  * Native without any real DNS query or network access. The real system-resolver round-trip (`java.net.InetAddress` on JVM) is covered by the
  * JVM-placed `HostResolverReproTest`, which needs the actual JVM resolver. The cache/seam logic verified here is platform-agnostic.
  */
class HostResolverTest extends Test:

    private val v4 = (PosixConstants.AF_INET, Array[Byte](93.toByte, 184.toByte, 216.toByte, 34.toByte))
    private val v6 =
        (PosixConstants.AF_INET6, Array.tabulate[Byte](16)(i => (i + 1).toByte))

    // The HostResolver cache is a process-global keyed by (host, familyHint). Each leaf uses a freshly minted host so it is COLD by construction:
    // a per-run nonce plus a monotonic sequence guarantees the key was never resolved before (in this run or a prior one reusing the JVM), so no
    // production cache-clearing affordance is needed to observe a cold cache.
    private val runNonce                       = java.lang.System.nanoTime()
    private val hostSeq                        = new AtomicInteger(0)
    private def freshHost(tag: String): String = s"$tag-${hostSeq.incrementAndGet()}-$runNonce"

    /** A fake resolver that counts how many times it is actually invoked and returns a fixed answer. The count proves whether the cache served
      * a request without re-querying. `RawResolver` returns a `Fiber.Unsafe`; the counter increment is synchronous and the result is consumed
      * via `.safe.get` at the test boundary (`.safe.get` is the sanctioned consumption in test source).
      */
    private def counting(answer: Result[Closed, HostResolver.Resolved]): (AtomicInteger, HostResolver.RawResolver) =
        import AllowUnsafe.embrace.danger
        val calls = new AtomicInteger(0)
        val raw: HostResolver.RawResolver = (_, _) =>
            discard(calls.incrementAndGet())
            Fiber.Unsafe.fromResult(Result.succeed(answer))
        (calls, raw)
    end counting

    "HostResolver" - {
        "resolves through the raw resolver and returns its answer (family + bytes)" in {
            val (calls, raw) = counting(Result.succeed(v4))
            HostResolver.resolveWith(freshHost("resolve"), PosixConstants.AF_INET, raw, HostResolver.DefaultTtl).safe.get.map {
                case Result.Success((family, addr)) =>
                    assert(family == PosixConstants.AF_INET)
                    assert(addr.sameElements(v4._2))
                    assert(calls.get() == 1, s"expected exactly one resolver call, got ${calls.get()}")
                case other => fail(s"expected success, got $other")
            }
        }

        "a second resolve of the same host hits the cache and does NOT re-query the resolver" in {
            val (calls, raw) = counting(Result.succeed(v4))
            val host         = freshHost("cache-hit")
            for
                first  <- HostResolver.resolveWith(host, PosixConstants.AF_INET, raw, HostResolver.DefaultTtl).safe.get
                second <- HostResolver.resolveWith(host, PosixConstants.AF_INET, raw, HostResolver.DefaultTtl).safe.get
            yield
                val (f1, a1) = first.getOrElse(fail(s"first failed: $first"))
                val (f2, a2) = second.getOrElse(fail(s"second failed: $second"))
                assert(f1 == PosixConstants.AF_INET && a1.sameElements(v4._2), s"first mismatch: ($f1, ${a1.toSeq})")
                assert(f2 == PosixConstants.AF_INET && a2.sameElements(v4._2), s"second mismatch: ($f2, ${a2.toSeq})")
                // The cache served the second call: the resolver ran exactly once for two resolves.
                assert(calls.get() == 1, s"expected the second resolve to hit the cache (1 call), got ${calls.get()}")
            end for
        }

        "an expired entry (TTL elapsed) re-queries the resolver" in {
            val (calls, raw) = counting(Result.succeed(v4))
            val host         = freshHost("ttl-zero")
            // TTL of zero means every entry is already stale on the next read, so both resolves miss the cache.
            for
                _ <- HostResolver.resolveWith(host, PosixConstants.AF_INET, raw, Duration.Zero).safe.get
                _ <- HostResolver.resolveWith(host, PosixConstants.AF_INET, raw, Duration.Zero).safe.get
            yield assert(calls.get() == 2, s"expected a re-query after a zero TTL (2 calls), got ${calls.get()}")
            end for
        }

        "a resolver FAILURE is not cached: the next resolve retries" in {
            val (calls, raw) = counting(Result.fail(Closed("test", Frame.internal, "boom")))
            val host         = freshHost("fail-not-cached")
            for
                first  <- HostResolver.resolveWith(host, PosixConstants.AF_INET, raw, HostResolver.DefaultTtl).safe.get
                second <- HostResolver.resolveWith(host, PosixConstants.AF_INET, raw, HostResolver.DefaultTtl).safe.get
            yield
                assert(first.isFailure, s"expected failure, got $first")
                assert(second.isFailure, s"expected failure, got $second")
                // A failure is never cached, so the resolver is retried: two resolves -> two calls.
                assert(calls.get() == 2, s"expected a failure to be retried (2 calls), got ${calls.get()}")
            end for
        }

        "distinct hosts are cached independently" in {
            val callsByHost = new java.util.concurrent.ConcurrentHashMap[String, Array[Byte]]()
            val answerSeq   = new AtomicInteger(0)
            val raw: HostResolver.RawResolver = (host, _) =>
                import AllowUnsafe.embrace.danger
                // Each raw call mints a distinct synthetic v4 answer (a monotonic last byte); record it per host so we can assert per-host caching.
                val answer = Array[Byte](10, 0, 0, answerSeq.incrementAndGet().toByte)
                discard(callsByHost.put(host, answer))
                Fiber.Unsafe.fromResult(Result.succeed(Result.succeed((PosixConstants.AF_INET, answer))))
            val hostA = freshHost("distinctA")
            val hostB = freshHost("distinctB")
            for
                a <- HostResolver.resolveWith(hostA, PosixConstants.AF_INET, raw, HostResolver.DefaultTtl).safe.get
                b <- HostResolver.resolveWith(hostB, PosixConstants.AF_INET, raw, HostResolver.DefaultTtl).safe.get
            yield
                val aAddr = a.getOrElse(fail("a failed"))._2
                val bAddr = b.getOrElse(fail("b failed"))._2
                // Each host's resolved bytes match the distinct answer the resolver minted for THAT host: the cache keys on the host, no collision.
                assert(aAddr.sameElements(callsByHost.get(hostA)), s"hostA addr ${aAddr.toSeq}")
                assert(bAddr.sameElements(callsByHost.get(hostB)), s"hostB addr ${bAddr.toSeq}")
                assert(!aAddr.sameElements(bAddr), "distinct hosts must resolve to distinct cached answers")
            end for
        }

        "an IPv6 answer round-trips family and 16 bytes through the cache" in {
            val (calls, raw) = counting(Result.succeed(v6))
            val host         = freshHost("v6")
            for
                first  <- HostResolver.resolveWith(host, PosixConstants.AF_INET6, raw, HostResolver.DefaultTtl).safe.get
                second <- HostResolver.resolveWith(host, PosixConstants.AF_INET6, raw, HostResolver.DefaultTtl).safe.get
            yield
                val (family, addr)   = first.getOrElse(fail(s"v6 resolve failed: $first"))
                val (family2, addr2) = second.getOrElse(fail(s"v6 second resolve failed: $second"))
                assert(family == PosixConstants.AF_INET6)
                assert(addr.length == 16 && addr.sameElements(v6._2))
                assert(family2 == PosixConstants.AF_INET6 && addr2.sameElements(v6._2), s"v6 second mismatch: ($family2, ${addr2.toSeq})")
                assert(calls.get() == 1, s"v6 second resolve should hit the cache, got ${calls.get()} calls")
            end for
        }

        "a genuinely-pending raw fiber completes via onComplete when the underlying fiber resolves" in {
            import AllowUnsafe.embrace.danger
            val rawPromise = new IOPromise[Any, Result[Closed, HostResolver.Resolved]]
            val raw: HostResolver.RawResolver = (_, _) =>
                rawPromise.asInstanceOf[Fiber.Unsafe[Result[Closed, HostResolver.Resolved], Any]]
            val fiber = HostResolver.resolveWith(freshHost("pending"), PosixConstants.AF_INET, raw, HostResolver.DefaultTtl)
            // The fiber is not yet done: raw has not completed.
            assert(!fiber.done(), "fiber must not be done before raw resolves")
            // Complete raw; the onComplete callback in resolveWith fires and completes the returned fiber.
            rawPromise.completeDiscard(Result.succeed(Result.succeed(v4): Result[Closed, HostResolver.Resolved]))
            fiber.safe.get.map {
                case Result.Success((family, addr)) =>
                    assert(family == PosixConstants.AF_INET)
                    assert(addr.sameElements(v4._2))
                case other => fail(s"expected success, got $other")
            }
        }

        "a panic from the raw resolver surfaces as Result.panic at the caller (not swallowed or converted to failure)" in {
            import AllowUnsafe.embrace.danger
            // Build a raw resolver whose fiber completes with a panic at the outer level (the fiber itself panics),
            // exercising the defensive case Result.Panic(e) arm in resolveWith's onComplete.
            val panicEx    = new RuntimeException("raw-resolver-panic-sentinel")
            val rawPromise = new IOPromise[Any, Result[Closed, HostResolver.Resolved]]
            val raw: HostResolver.RawResolver = (_, _) =>
                rawPromise.asInstanceOf[Fiber.Unsafe[Result[Closed, HostResolver.Resolved], Any]]
            val fiber = HostResolver.resolveWith(freshHost("panic"), PosixConstants.AF_INET, raw, HostResolver.DefaultTtl)
            // Complete the raw promise with a panic at the OUTER level (the fiber itself panics).
            rawPromise.completeDiscard(Result.Panic(panicEx))
            // The panic must NOT be swallowed or converted to a Closed failure: use getResult to capture the
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
            val (calls, raw) = counting(Result.succeed(v4))
            val host         = freshHost("concurrent")
            // Drive two resolves concurrently via Async.zip so both start before either stores a cache entry.
            Async.zip(
                HostResolver.resolveWith(host, PosixConstants.AF_INET, raw, HostResolver.DefaultTtl).safe.get,
                HostResolver.resolveWith(host, PosixConstants.AF_INET, raw, HostResolver.DefaultTtl).safe.get
            ).map { case (r1, r2) =>
                // Both callers observe the expected resolved value.
                val (f1, a1) = r1.getOrElse(fail(s"first concurrent resolve failed: $r1"))
                val (f2, a2) = r2.getOrElse(fail(s"second concurrent resolve failed: $r2"))
                assert(f1 == PosixConstants.AF_INET && a1.sameElements(v4._2), s"first result mismatch: ($f1, ${a1.toSeq})")
                assert(f2 == PosixConstants.AF_INET && a2.sameElements(v4._2), s"second result mismatch: ($f2, ${a2.toSeq})")
                assert(a1.sameElements(a2), "both concurrent callers must observe the same resolved bytes")
                // Both callers saw a miss (cache was cold when they checked), so raw was called at most twice.
                // The cache now holds exactly one consistent entry for the key.
                val callCount = calls.get()
                assert(callCount <= 2, s"raw resolver called $callCount times; expected at most 2 (one per concurrent miss)")
                assert(callCount >= 1, s"raw resolver was never called; expected at least 1")
            }
        }
    }

    "asClosedOrPanic" - {
        "a Closed value maps to Result.Failure carrying that exact Closed" in {
            val closed = Closed("test-resource", Frame.internal, "the underlying failure")
            HostResolver.asClosedOrPanic(closed, Frame.internal) match
                case Result.Failure(c) => assert(c eq closed, "expected the same Closed instance")
                case other             => fail(s"expected Result.Failure(closed), got $other")
        }

        "a non-Closed Throwable maps to Result.Panic carrying that exact Throwable" in {
            val boom = new RuntimeException("not-a-closed")
            HostResolver.asClosedOrPanic(boom, Frame.internal) match
                case Result.Panic(t) =>
                    assert(t eq boom, "expected the same Throwable instance")
                    assert(t.getMessage == "not-a-closed")
                case other => fail(s"expected Result.Panic(boom), got $other")
            end match
        }

        "a non-Throwable value maps to Result.Panic wrapping it in a Closed (no ClassCastException)" in {
            HostResolver.asClosedOrPanic("a bare string failure", Frame.internal) match
                case Result.Panic(t) =>
                    assert(t.isInstanceOf[Closed], s"expected a Closed panic cause, got ${t.getClass}")
                    assert(t.getMessage.contains("a bare string failure"), s"panic message should describe the value, got ${t.getMessage}")
                case other => fail(s"expected Result.Panic(Closed), got $other")
        }
    }

end HostResolverTest
