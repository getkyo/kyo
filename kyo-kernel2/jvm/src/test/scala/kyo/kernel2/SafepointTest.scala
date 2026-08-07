package kyo.kernel2

import kyo.test.Test
import language.implicitConversions

class SafepointTest extends Test[Any]:

    "depth slots are reclaimed from dead threads" in {
        var i = 0
        while i < 768 do
            val t = new Thread(() =>
                val _ = (1: Int < Any).map(_ + 1).eval
            )
            t.start()
            t.join()
            i += 1
        end while
        var fresh = false
        val probe = new Thread(() =>
            val _ = (1: Int < Any).map(_ + 1).eval
            fresh = Safepoint.owned
        )
        probe.start()
        probe.join()
        assert(fresh)
    }
end SafepointTest
