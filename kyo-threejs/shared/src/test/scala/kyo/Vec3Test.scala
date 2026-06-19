package kyo

class Vec3Test extends kyo.test.Test[Any]:

    val tolerance = 1e-9

    "addition is component-wise" in {
        assert(Vec3(1, 2, 3) + Vec3(4, 5, 6) == Vec3(5, 7, 9))
    }

    "scalar multiplication scales every component" in {
        assert(Vec3(1, -2, 3) * 2.0 == Vec3(2, -4, 6))
    }

    "ofDegrees builds a radian Euler vector" in {
        val v = Vec3.ofDegrees(180, 0, 90)
        assert(math.abs(v.x - math.Pi) < tolerance)
        assert(math.abs(v.y - 0.0) < tolerance)
        assert(math.abs(v.z - math.Pi / 2) < tolerance)
    }

end Vec3Test
