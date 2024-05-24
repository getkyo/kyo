package kyo.stats.internal

case class Summary(
    p50: Double,
    p90: Double,
    p99: Double,
    p999: Double,
    p9999: Double,
    min: Double,
    max: Double,
    mean: Double,
    count: Long
)
