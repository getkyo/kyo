package kyo

/** `object Three` mixes this trait in, so [[Vec3]] resolves as `Three.Vec3` to every consumer while
  * the case class and its companion stay in this file.
  */
private[kyo] trait ThreeVec3Ops:

    /** An immutable `(x, y, z)` spatial value used by position / scale, light and camera placement, and
      * geometry parameters: the universal spatial primitive of the module.
      *
      * The component-wise `+`, `-`, and scalar `*` operators compose vectors purely; the companion carries
      * the common constants and a [[Vec3.ofDegrees]] helper that builds a Euler-angle vector from degree
      * inputs.
      */
    final case class Vec3(x: Double, y: Double, z: Double) derives CanEqual:
        /** Component-wise addition. */
        def +(that: Vec3): Vec3 = Vec3(x + that.x, y + that.y, z + that.z)

        /** Component-wise subtraction. */
        def -(that: Vec3): Vec3 = Vec3(x - that.x, y - that.y, z - that.z)

        /** Scalar multiplication. */
        def *(s: Double): Vec3 = Vec3(x * s, y * s, z * s)
    end Vec3

    object Vec3:
        val zero: Vec3  = Vec3(0.0, 0.0, 0.0)
        val one: Vec3   = Vec3(1.0, 1.0, 1.0)
        val unitX: Vec3 = Vec3(1.0, 0.0, 0.0)
        val unitY: Vec3 = Vec3(0.0, 1.0, 0.0)
        val unitZ: Vec3 = Vec3(0.0, 0.0, 1.0)

        /** Builds a Euler-angle vector (in radians) from degree inputs. */
        def ofDegrees(x: Double, y: Double, z: Double): Vec3 =
            Vec3(Three.Radians.deg(x).toDouble, Three.Radians.deg(y).toDouble, Three.Radians.deg(z).toDouble)
    end Vec3
end ThreeVec3Ops
