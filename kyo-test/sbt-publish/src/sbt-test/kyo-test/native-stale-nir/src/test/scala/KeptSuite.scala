import kyo.test.Test

class KeptSuite extends Test[Any]:
    "uses the object that outlives its class" in {
        assert(Pair.next(1) == 2)
    }
end KeptSuite
