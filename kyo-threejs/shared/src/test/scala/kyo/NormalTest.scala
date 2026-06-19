package kyo

class NormalTest extends kyo.test.Test[Any]:

    "apply clamps the high end" in {
        assert(Normal(1.5) == Normal.one)
    }

    "apply clamps the low end" in {
        assert(Normal(-0.5) == Normal.zero)
    }

    "apply passes an in-range value through" in {
        assert(Normal(0.5) == Normal.half)
        assert(Normal(0.5).toDouble == 0.5)
    }

    "apply maps NaN to zero" in {
        assert(Normal(Double.NaN) == Normal.zero)
    }

end NormalTest
