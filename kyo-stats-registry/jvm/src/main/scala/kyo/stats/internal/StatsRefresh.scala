package kyo.stats.internal

import java.util.ServiceLoader
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import kyo.stats.internal.StatsRegistry

trait StatsRefresh {
    self: StatsRegistry.internal.type =>

    val refreshInterval = System.getProperty("kyo.stats.refreshIntervalMs", "1000").toInt

    val threadFactory = new ThreadFactory {
        def newThread(r: Runnable) = {
            val thread = new Thread
            thread.setName("kyo-stats-refresh")
            thread.setDaemon(true)
            thread
        }
    }

    Executors.newSingleThreadScheduledExecutor(threadFactory)
        .scheduleAtFixedRate(() => refresh(), refreshInterval, refreshInterval, TimeUnit.MILLISECONDS)

    ServiceLoader.load(classOf[StatsExporter]).iterator().forEachRemaining(exporters.add(_))
}
