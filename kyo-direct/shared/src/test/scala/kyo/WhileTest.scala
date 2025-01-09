package kyo

import kyo.TestSupport.*
import org.scalatest.Assertions
import org.scalatest.freespec.AnyFreeSpec
import scala.collection.mutable.ArrayBuffer

class WhileTest extends AnyFreeSpec with Assertions:

    "with atomic" in {
        runLiftTest(3) {
            val i = AtomicInt.init(0).now
            while i.get.now < 3 do
                i.incrementAndGet.now
                ()
            i.get.now
        }
    }
    "double in tuple - strange case" in {
        var i     = 0
        val buff1 = new ArrayBuffer[Int]()
        val buff2 = new ArrayBuffer[Int]()
        def incrementA() =
            i += 1
            buff1 += i
            i
        end incrementA
        def incrementB() =
            i += 1
            buff2 += i
            i
        end incrementB
        val out =
            defer {
                while i < 3 do
                    IO(incrementA()).now
                    IO(incrementB()).now
                    ()
                end while
                i
            }
        for
            a <- out
        yield (
            assert(a == 4 && buff1.toList == List(1, 3) && buff2.toList == List(2, 4))
        )
    }
end WhileTest
