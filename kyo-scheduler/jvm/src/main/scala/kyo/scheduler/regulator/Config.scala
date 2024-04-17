package kyo.scheduler.regulator

case class Config(
    collectWindowExp: Int,
    collectIntervalMs: Int,
    collectSamples: Int,
    regulateIntervalMs: Int,
    jitterUpperThreshold: Double,
    jitterLowerThreshold: Double,
    loadAvgTarget: Double,
    stepExp: Double
)
