package kyo.stats.internal

abstract class StatsExporter {
    def counter(path: List[String], description: String, delta: Long): Unit
    def histogram(path: List[String], description: String, summary: Summary): Unit
    def gauge(path: List[String], description: String, currentValue: Double): Unit
}
