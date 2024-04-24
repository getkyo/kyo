package kyo.scheduler.regulator

import scala.concurrent.duration.Duration

case class Config(
    collectWindow: Int,
    collectInterval: Duration,
    regulateInterval: Duration,
    jitterUpperThreshold: Double,
    jitterLowerThreshold: Double,
    loadAvgTarget: Double,
    stepExp: Double
)
