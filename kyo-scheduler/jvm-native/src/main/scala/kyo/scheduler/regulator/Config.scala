package kyo.scheduler.regulator

import scala.concurrent.duration.Duration

/** Configuration parameters controlling regulator behavior.
  *
  * The configuration determines how regulators collect and analyze timing measurements, and how aggressively they respond to detected
  * issues. These parameters balance responsiveness against stability.
  *
  * @param collectWindow
  *   Size of the moving window used for standard deviation calculation
  * @param collectInterval
  *   Interval between probe measurements
  * @param regulateInterval
  *   Interval between regulation adjustments
  * @param jitterUpperThreshold
  *   High standard deviation threshold that triggers load reduction
  * @param jitterLowerThreshold
  *   Low standard deviation threshold that allows load increase
  * @param loadAvgTarget
  *   Target load level - load must meet this for increases
  * @param stepExp
  *   Controls how quickly consecutive adjustments escalate
  * @param calibrationProbes
  *   Number of idle probes measured once at startup to establish the platform's probe noise floor; when the floor exceeds the configured
  *   jitter thresholds, the effective thresholds are raised above it (see Regulator.calibrate). 0 disables calibration.
  */
case class Config(
    collectWindow: Int,
    collectInterval: Duration,
    regulateInterval: Duration,
    jitterUpperThreshold: Double,
    jitterLowerThreshold: Double,
    loadAvgTarget: Double,
    stepExp: Double,
    calibrationProbes: Int = 0
)
