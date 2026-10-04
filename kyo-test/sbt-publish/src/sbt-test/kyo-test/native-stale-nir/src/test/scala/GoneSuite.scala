import kyo.test.Test

class GoneSuite extends Test[Any]:
    "counts" in {
        assert(Helpers.counter.incrementAndGet() >= 1)
    }
end GoneSuite
