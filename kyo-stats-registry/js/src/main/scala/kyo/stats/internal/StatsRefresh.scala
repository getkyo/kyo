package kyo.stats.internal

import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

trait StatsRefresh {
    def refresh(): Unit
}
