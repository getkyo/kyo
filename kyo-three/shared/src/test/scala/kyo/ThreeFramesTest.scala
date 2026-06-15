package kyo

class ThreeFramesTest extends kyo.test.Test[Any]:

    "Raf is the default frame source" in {
        val raf: ThreeFrames = ThreeFrames.Raf
        assert(raf == ThreeFrames.Raf)
    }

    "Clock carries the interval" in {
        val interval = 16.millis
        val clk      = ThreeFrames.Clock(interval)
        assert(clk.interval == interval)
        assert(clk == ThreeFrames.Clock(interval))
    }

end ThreeFramesTest
