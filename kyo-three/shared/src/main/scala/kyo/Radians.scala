package kyo

/** An angle as an opaque `Double` of radians, shared by transforms (rotation) and cameras (field of
  * view), so a rotation or FOV cannot be confused with a raw number at a call site.
  *
  * Construct with [[Radians.rad]] (already in radians) or [[Radians.deg]] (degrees, converted on
  * construction). The `toDouble` extension recovers the underlying radian value for the FFI bridge.
  */
opaque type Radians = Double

object Radians:

    /** Wraps a value already expressed in radians. */
    def rad(value: Double): Radians = value

    /** Converts a degree value to radians. */
    def deg(value: Double): Radians = value * math.Pi / 180.0

    val zero: Radians = 0.0

    extension (r: Radians)
        /** The underlying radian value. */
        def toDouble: Double = r

        /** The angle expressed in degrees. */
        def toDegrees: Double = r * 180.0 / math.Pi
    end extension

    given CanEqual[Radians, Radians] = CanEqual.derived
end Radians
