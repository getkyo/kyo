package kyo.internal

/** sqrt-area size scale (catalog #22/D7): circle AREA is proportional to the
  * data magnitude, so `radius = rMin + (rMax - rMin) * sqrt(t)` where
  * `t = clamp01((mag - magMin) / (magMax - magMin))`.
  *
  * When `magMax <= magMin` (degenerate extent: all magnitudes equal, or a
  * single row), `radius` returns `rMin` for any input without dividing by zero.
  * The caller builds this once from the full row set before mapping per-row.
  */
final private[kyo] case class SizeScale(magMin: Double, magMax: Double, rMin: Double, rMax: Double):

    /** Map a raw data magnitude to a circle radius. */
    def radius(mag: Double): Double =
        if magMax <= magMin then rMin
        else
            val t = math.max(0.0, math.min(1.0, (mag - magMin) / (magMax - magMin)))
            rMin + (rMax - rMin) * math.sqrt(t)

end SizeScale

private[kyo] object SizeScale:
    val DefaultRMin = 2.0
    val DefaultRMax = 20.0
