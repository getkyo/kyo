package kyo

class NormalTest extends kyo.test.Test[Any]:

    "apply clamps the high end" in {
        assert(Three.Normal(1.5) == Three.Normal.one)
    }

    "apply clamps the low end" in {
        assert(Three.Normal(-0.5) == Three.Normal.zero)
    }

    "apply passes an in-range value through" in {
        assert(Three.Normal(0.5) == Three.Normal.half)
        assert(Three.Normal(0.5).toDouble == 0.5)
    }

    "apply maps NaN to zero" in {
        assert(Three.Normal(Double.NaN) == Three.Normal.zero)
    }

end NormalTest
