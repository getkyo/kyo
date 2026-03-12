package kyo.stats.internal

import java.util.ServiceLoader
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import kyo.AllowUnsafe

trait StatsRefresh {
    self: StatsRegistry.internal.type =>

    val refreshInterval = System.getProperty("kyo.stats.refreshIntervalMs", "1000").toInt

    val threadFactory = new ThreadFactory {
        def newThread(r: Runnable) = {
            val thread = new Thread(r)
            thread.setName("kyo-stats-refresh")
            thread.setDaemon(true)
            thread
        }
    }

    Executors.newSingleThreadScheduledExecutor(threadFactory)
        .scheduleAtFixedRate(() => refresh(), refreshInterval, refreshInterval, TimeUnit.MILLISECONDS)

    {
        given AllowUnsafe = AllowUnsafe.embrace.danger
        ServiceLoader.load(classOf[ExporterFactory]).iterator().forEachRemaining { factory =>
            factory.statsExporter().foreach { exporter =>
                val _ = exporters.add(exporter)
            }
        }
    }
}
