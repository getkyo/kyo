package kyo.scheduler.regulator

import scala.concurrent.duration.Duration

case class Config(
    collectWindowExp: Int,
    collectInterval: Duration,
    collectSamples: Int,
    regulateInterval: Duration,
    jitterUpperThreshold: Double,
    jitterLowerThreshold: Double,
    loadAvgTarget: Double,
    stepExp: Double
)
