package kyo

import kyo.stats.internal.StatsRegistry

/** Pins every instrument name kyo-sql registers, and the store each one lands in.
  *
  * The names are the observable half of the metrics surface: a dashboard, an alert, and a recording rule all name them as strings, so renaming
  * one silently breaks whatever was watching it. [[SqlClientMetricsTest]] covers what the instruments count; this covers what they are called,
  * by reading the registry back under the exact path a scraper would find them at.
  *
  * Each presence leaf creates the instance, reads the registry, and then asserts through the instance again. The last step is not decoration:
  * the registry holds its instruments through a [[java.lang.ref.WeakReference]], so the instance has to outlive the lookup, and asserting that
  * two names resolve to two different instruments also catches the copy-paste where one field ends up registered under both.
  */
class SqlClientStatInstrumentTest extends Test:

    /** These metric names are a stable public contract already in use by consumers; the spellings are pinned and must not change. */
    private val stableCounterNames = Chunk(
        "connections_acquired",
        "connections_released",
        "connections_discarded",
        "queries_executed",
        "queries_failed",
        "retries_attempted"
    )

    private val stableHistogramNames = Chunk("query_duration_ms", "pool_acquire_wait_ms")

    /** Names the lease and reclaim work adds. */
    private val addedCounters = Chunk(
        "leases_acquired",
        "cancels_fired",
        "cancels_timed_out",
        "transactions_committed",
        "transactions_rolled_back"
    )

    private val addedHistograms = Chunk("lease_acquire_latency_ms")

    private val addedGauges = Chunk("leases_in_flight")

    private def counterNames(scope: List[String]): Set[String] =
        registered(StatsRegistry.internal.counters.map.keySet(), scope)

    private def histogramNames(scope: List[String]): Set[String] =
        registered(StatsRegistry.internal.histograms.map.keySet(), scope)

    private def gaugeNames(scope: List[String]): Set[String] =
        registered(StatsRegistry.internal.gauges.map.keySet(), scope)

    /** Every leaf name registered directly under `scope`. */
    private def registered(keys: java.util.Set[List[String]], scope: List[String]): Set[String] =
        val out  = Set.newBuilder[String]
        val iter = keys.iterator()
        while iter.hasNext do
            val path = iter.next()
            if path.length == scope.length + 1 && path.startsWith(scope) then out += path.last
        out.result()
    end registered

    "every stable counter name is registered under kyo.sql" in {
        val metrics = SqlClient.Metrics(metricsEnabled = true, metricsScope = Absent)
        val names   = counterNames(List("kyo", "sql"))
        val missing = stableCounterNames.filterNot(names.contains)
        assert(missing == Chunk.empty[String], s"counters missing from kyo.sql: $missing; registered: $names")
        assert(metrics.connectionsReleased ne metrics.connectionsDiscarded, "each name must resolve to its own counter")
    }

    "every stable histogram name is registered under kyo.sql" in {
        val metrics = SqlClient.Metrics(metricsEnabled = true, metricsScope = Absent)
        val names   = histogramNames(List("kyo", "sql"))
        val missing = stableHistogramNames.filterNot(names.contains)
        assert(missing == Chunk.empty[String], s"histograms missing from kyo.sql: $missing; registered: $names")
        assert(metrics.queryDurationMs ne metrics.poolAcquireWaitMs, "each name must resolve to its own histogram")
    }

    "every added counter name is registered under kyo.sql" in {
        val metrics = SqlClient.Metrics(metricsEnabled = true, metricsScope = Absent)
        val names   = counterNames(List("kyo", "sql"))
        val missing = addedCounters.filterNot(names.contains)
        assert(missing == Chunk.empty[String], s"counters missing from kyo.sql: $missing; registered: $names")
        assert(metrics.cancelsFired ne metrics.cancelsTimedOut, "each name must resolve to its own counter")
    }

    "lease_acquire_latency_ms is a histogram, and leases_in_flight is a gauge" in {
        // The store an instrument lands in is part of its contract: a scraper reads a gauge as a level and a
        // histogram as a distribution, so a name in the wrong store reports the wrong shape.
        val metrics    = SqlClient.Metrics(metricsEnabled = true, metricsScope = Absent)
        val histograms = histogramNames(List("kyo", "sql"))
        val gauges     = gaugeNames(List("kyo", "sql"))
        val counters   = counterNames(List("kyo", "sql"))
        assert(addedHistograms.forall(histograms.contains), s"histograms missing: $addedHistograms; registered: $histograms")
        assert(addedGauges.forall(gauges.contains), s"gauges missing: $addedGauges; registered: $gauges")
        assert(!counters.contains("lease_acquire_latency_ms"), "lease_acquire_latency_ms must not be a counter")
        assert(!counters.contains("leases_in_flight"), "leases_in_flight must not be a counter")
        assert(metrics.leasesInFlight ne metrics.leaseAcquireLatencyMs, "the gauge and the histogram must be two instruments")
    }

    "metricsScope moves every instrument under the prefix it names" in {
        val metrics = SqlClient.Metrics(metricsEnabled = true, metricsScope = Present("myapp.db"))
        val names   = counterNames(List("myapp", "db"))
        val missing = (stableCounterNames ++ addedCounters).filterNot(names.contains)
        assert(missing == Chunk.empty[String], s"counters missing from myapp.db: $missing; registered: $names")
        assert(gaugeNames(List("myapp", "db")).contains("leases_in_flight"), "the gauge must move with the rest")
        assert(metrics.transactionsCommitted ne metrics.transactionsRolledBack, "each name must resolve to its own counter")
    }

    "leases_in_flight reports how many leases are open right now" in {
        val m = SqlClient.Metrics(metricsEnabled = true, metricsScope = Present("kyo.sql.instr.gauge"))
        m.leasesInFlight.collect.flatMap { start =>
            m.recordLeaseAcquired(3L).andThen(m.recordLeaseAcquired(4L)).andThen {
                m.leasesInFlight.collect.flatMap { held =>
                    m.recordLeaseReleased.andThen(m.recordLeaseReleased).andThen {
                        m.leasesInFlight.collect.map { back =>
                            assert(start == 0.0d, s"a fresh Metrics must report no open leases, was $start")
                            assert(held == 2.0d, s"two acquired leases must read as 2, was $held")
                            assert(back == 0.0d, s"releasing both must bring it back to 0, was $back")
                        }
                    }
                }
            }
        }
    }

    "leases_acquired and lease_acquire_latency_ms record one entry per lease" in {
        val m = SqlClient.Metrics(metricsEnabled = true, metricsScope = Present("kyo.sql.instr.lease"))
        m.recordLeaseAcquired(5L).andThen(m.recordLeaseAcquired(7L)).andThen {
            m.leasesAcquired.get.map { acquired =>
                assert(acquired == 2L, s"leases_acquired must count both, was $acquired")
            }
        }
    }

    "cancels_fired and cancels_timed_out count the reclaim outcomes separately" in {
        val m = SqlClient.Metrics(metricsEnabled = true, metricsScope = Present("kyo.sql.instr.cancel"))
        m.recordCancelFired.andThen(m.recordCancelFired).andThen(m.recordCancelTimedOut).andThen {
            m.cancelsFired.get.flatMap { fired =>
                m.cancelsTimedOut.get.map { timedOut =>
                    assert(fired == 2L, s"cancels_fired must count every reclaim started, was $fired")
                    assert(timedOut == 1L, s"cancels_timed_out must count only the overruns, was $timedOut")
                }
            }
        }
    }

    "transactions_committed and transactions_rolled_back count the two transaction outcomes" in {
        val m = SqlClient.Metrics(metricsEnabled = true, metricsScope = Present("kyo.sql.instr.tx"))
        m.recordTransactionCommitted.andThen(m.recordTransactionRolledBack).andThen(m.recordTransactionRolledBack).andThen {
            m.transactionsCommitted.get.flatMap { committed =>
                m.transactionsRolledBack.get.map { rolledBack =>
                    assert(committed == 1L, s"transactions_committed must count the commit, was $committed")
                    assert(rolledBack == 2L, s"transactions_rolled_back must count both rollbacks, was $rolledBack")
                }
            }
        }
    }

    "every new instrument is a no-op when metrics are disabled" in {
        val m = SqlClient.Metrics(metricsEnabled = false, metricsScope = Absent)
        m.recordLeaseAcquired(1L).andThen(m.recordCancelFired).andThen(m.recordCancelTimedOut)
            .andThen(m.recordTransactionCommitted).andThen(m.recordTransactionRolledBack).andThen {
                m.leasesAcquired.get.flatMap { leases =>
                    m.cancelsFired.get.flatMap { fired =>
                        m.transactionsCommitted.get.flatMap { committed =>
                            m.leasesInFlight.collect.map { inFlight =>
                                assert(leases == 0L, s"leases_acquired must stay at 0 when disabled, was $leases")
                                assert(fired == 0L, s"cancels_fired must stay at 0 when disabled, was $fired")
                                assert(committed == 0L, s"transactions_committed must stay at 0 when disabled, was $committed")
                                assert(inFlight == 0.0d, s"leases_in_flight must read 0 when disabled, was $inFlight")
                            }
                        }
                    }
                }
            }
    }

end SqlClientStatInstrumentTest
