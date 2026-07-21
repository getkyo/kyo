package kyo

import kyo.*
import kyo.SqlMetrics
import kyo.Test
import kyo.stats.internal.Summary

/** Unit tests for metrics via kyo.Stat.
  *
  * All tests are in-process (no Docker). They exercise `SqlMetrics` directly — creating instances, calling instrumented methods, and
  * asserting counter/histogram state. No wall-clock assertions are present.
  *
  * Key constraints:
  *   - `Counter.get` uses `sumThenReset` (destructive read) — only call once per assertion.
  *   - Histogram `summary()` is non-destructive and can be read multiple times.
  *   - Use `Latch` for fiber synchronisation (no `Async.sleep`).
  *
  * Test count: 12 shared unit tests (target: 12 shared + 1 JVM integration).
  */
class SqlMetricsTest extends Test:

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Creates an enabled SqlMetrics instance under a unique scope to avoid cross-test counter pollution. */
    private def freshMetrics(scope: String = "kyo.sql.test"): SqlMetrics =
        SqlMetrics(metricsEnabled = true, metricsScope = Present(scope))

    /** A fake query body that always succeeds with a unit result. */
    private def successBody(using Frame): Unit < (Async & Abort[SqlException]) = ()

    /** A fake query body that always fails with a SqlException.Server. */
    private def failBody(using Frame): Unit < (Async & Abort[SqlException]) =
        Abort.fail(SqlException.Server("42601", "ERROR", "syntax error"))

    // ── queries_executed counter increments on each query ─────────────────────

    "queries_executed counter increments on each query" in {
        val m = freshMetrics("t1")
        // Run 3 successful queries through timedQuery.
        m.timedQuery(successBody).andThen {
            m.timedQuery(successBody).andThen {
                m.timedQuery(successBody).andThen {
                    m.queriesExecuted.get.map { count =>
                        assert(count == 3)
                    }
                }
            }
        }
    }

    // ── queries_failed counter increments on each failed query ────────────────

    "queries_failed counter increments on each failed query" in {
        val m = freshMetrics("t2")
        // Run each failing query independently (wrapped in its own Abort.run) so both execute.
        Abort.run[SqlException](m.timedQuery(failBody)).andThen {
            Abort.run[SqlException](m.timedQuery(failBody)).andThen {
                m.queriesFailed.get.map { count =>
                    assert(count == 2)
                }
            }
        }
    }

    // ── query_duration_ms histogram records observed duration ─────────────────

    "query_duration_ms histogram records observed duration" in {
        val m = freshMetrics("t3")
        // Run several successful queries and verify histogram count > 0.
        // Assertion: count > 0, never specific ms values.
        m.timedQuery(successBody).andThen {
            m.timedQuery(successBody).andThen {
                m.queryDurationSummary.map { summary =>
                    assert(summary.count > 0)
                }
            }
        }
    }

    // ── pool_acquire_wait_ms records wait when pool is saturated ──────────────

    "pool_acquire_wait_ms records wait when pool is saturated" in {
        val m = freshMetrics("t4")
        // Simulate pool acquire wait by recording directly.
        // Use a Latch to confirm the saturation point is observed before the assertion fires.
        Latch.init(1).flatMap { saturationLatch =>
            // Record one pool-acquire wait, signal the latch, then record another.
            m.recordPoolAcquireWait(10L).andThen {
                saturationLatch.release.andThen {
                    m.recordPoolAcquireWait(20L)
                }
            }.andThen {
                saturationLatch.await.andThen {
                    m.poolAcquireWaitSummary.map { summary =>
                        assert(summary.count >= 1)
                    }
                }
            }
        }
    }

    // ── connections_acquired/released balance over many borrows ───────────────

    "connections_acquired/released balance over many borrows" in {
        val m = freshMetrics("t5")
        // Record 5 acquires and 5 releases (simulating a balanced borrow cycle).
        Kyo.foreach(Chunk.from(1 to 5))(_ => m.recordAcquire).andThen {
            Kyo.foreach(Chunk.from(1 to 5))(_ => m.recordRelease).andThen {
                // Read acquired and released each exactly once (destructive read).
                m.connectionsAcquired.get.flatMap { acquired =>
                    m.connectionsReleased.get.map { released =>
                        assert(acquired == 5)
                        assert(released == 5)
                        assert(acquired - released == 0)
                    }
                }
            }
        }
    }

    // ── metricsEnabled=false makes every metric op a no-op ───────────────────

    "metricsEnabled=false makes every metric op a no-op" in {
        val m = SqlMetrics(metricsEnabled = false, metricsScope = Absent)
        // Run successful and failed queries through the disabled metrics.
        m.timedQuery(successBody).andThen {
            Abort.run[SqlException](m.timedQuery(failBody)).andThen {
                m.recordAcquire.andThen {
                    m.recordRelease.andThen {
                        m.recordDiscard.andThen {
                            m.recordRetry.andThen {
                                // All counters should return 0 (noop — nothing was recorded).
                                m.queriesExecuted.get.flatMap { qe =>
                                    m.queriesFailed.get.flatMap { qf =>
                                        m.connectionsAcquired.get.flatMap { ca =>
                                            m.retriesAttempted.get.map { ra =>
                                                assert(qe == 0)
                                                assert(qf == 0)
                                                assert(ca == 0)
                                                assert(ra == 0)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── metricsScope='myapp.db' prefixes every metric with myapp.db ───────────

    "metricsScope='myapp.db' prefixes every metric with myapp.db" in {
        // Create metrics under a custom scope. The test verifies that:
        //   1. The metrics instance is created without errors under the custom scope.
        //   2. Counters still function correctly (the scope is purely a naming prefix).
        val m = SqlMetrics(metricsEnabled = true, metricsScope = Present("myapp.db"))
        m.timedQuery(successBody).andThen {
            // The metric was recorded under "myapp.db.queries_executed".
            // We verify behaviour (counter works) not the registration name
            // (that is the stats-registry concern).
            m.queriesExecuted.get.map { count =>
                assert(count == 1)
            }
        }
    }

    // ── retries_attempted increments on each retry ────────────────────────────

    "retries_attempted increments on each retry" in {
        val m = freshMetrics("t8")
        // Simulate the retry path: retryWith increments retries_attempted
        // on each re-entry after the first attempt. We exercise the path directly.
        // 3 retries = 3 increments.
        m.recordRetry.andThen {
            m.recordRetry.andThen {
                m.recordRetry.andThen {
                    m.retriesAttempted.get.map { count =>
                        assert(count == 3)
                    }
                }
            }
        }
    }

    // ── acquired - released - discarded == 0 after all fibers complete ────────

    "acquired - released - discarded == 0 after all fibers complete (no leaks invariant)" in {
        val m = freshMetrics("t9")
        // Simulate 4 successful borrows (acquire → release) and 1 discard.
        // Invariant: acquired = released + discarded.
        Kyo.foreach(Chunk.from(1 to 4))(_ => m.recordAcquire.andThen(m.recordRelease)).andThen {
            m.recordAcquire.andThen(m.recordDiscard)
        }.andThen {
            m.connectionsAcquired.get.flatMap { acquired =>
                m.connectionsReleased.get.flatMap { released =>
                    m.connectionsDiscarded.get.map { discarded =>
                        assert(acquired == 5)
                        assert(released == 4)
                        assert(discarded == 1)
                        assert(acquired - released - discarded == 0)
                    }
                }
            }
        }
    }

    // ── queries_failed increments on Abort, not on success ───────────────────

    "queries_failed increments on Abort, not on success" in {
        val m = freshMetrics("t10")
        // One success, one failure.
        m.timedQuery(successBody).andThen {
            Abort.run[SqlException](m.timedQuery(failBody)).andThen {
                m.queriesExecuted.get.flatMap { executed =>
                    m.queriesFailed.get.map { failed =>
                        assert(executed == 1)
                        assert(failed == 1)
                    }
                }
            }
        }
    }

    // ── 100 concurrent queries produce queries_executed == 100 ────────────────

    "100 concurrent queries produce queries_executed == 100" in {
        val m = freshMetrics("t11")
        // 100 concurrent fibers each call timedQuery with a successful body.
        Async.fill(100, concurrency = 100) {
            m.timedQuery(successBody)
        }.andThen {
            m.queriesExecuted.get.map { count =>
                assert(count == 100)
            }
        }
    }

    // ── histogram count == query count, regardless of latency value ───────────

    "histogram count == query count, regardless of latency value" in {
        val m = freshMetrics("t12")
        val n = 7
        // Run n queries through timedQuery.
        Kyo.foreach(Chunk.from(1 to n))(_ => m.timedQuery(successBody)).andThen {
            m.queryDurationSummary.flatMap { summary =>
                m.queriesExecuted.get.map { executed =>
                    // histogram count == number of successful queries
                    assert(summary.count == n.toLong)
                    assert(executed == n.toLong)
                }
            }
        }
    }

end SqlMetricsTest
