package kyo

class RadiansTest extends kyo.test.Test[Any]:

    val tolerance = 1e-9

    "deg converts degrees to radians" in {
        val r = Radians.deg(180).toDouble
        assert(math.abs(r - math.Pi) < tolerance)
    }

    "rad is the identity on the underlying value" in {
        assert(Radians.rad(1.25).toDouble == 1.25)
    }

    "deg and rad round-trip through toDegrees" in {
        val deg = Radians.deg(90).toDegrees
        assert(math.abs(deg - 90.0) < tolerance)
    }

end RadiansTest
