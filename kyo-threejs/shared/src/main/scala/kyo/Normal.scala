package kyo

/** `object Three` mixes this trait in, so [[Normal]] resolves as `Three.Normal` to every consumer
  * while the opaque type and its companion stay in this file.
  */
private[kyo] trait ThreeNormalOps:

    /** A clamped `[0, 1]` opaque `Double` for opacity, metalness, roughness, and intensity fractions.
      *
      * Construction clamps: [[Normal.apply]] maps any input into `[0, 1]`, so a material or light cannot be
      * built with an out-of-range fraction. `NaN` clamps to `zero`. The `toDouble` extension recovers the
      * underlying value for the FFI bridge.
      */
    opaque type Normal = Double

    object Normal:

        /** Clamps any input into `[0, 1]`; `NaN` maps to `0`. */
        def apply(value: Double): Normal = clamp(value)

        val zero: Normal = 0.0
        val half: Normal = 0.5
        val one: Normal  = 1.0

        private def clamp(v: Double): Double =
            if java.lang.Double.isNaN(v) then 0.0
            else if v < 0.0 then 0.0
            else if v > 1.0 then 1.0
            else v

        extension (n: Normal)
            /** The underlying `[0, 1]` value. */
            def toDouble: Double = n
        end extension

        given CanEqual[Normal, Normal] = CanEqual.derived
    end Normal
end ThreeNormalOps
