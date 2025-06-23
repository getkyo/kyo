package kyo.test

import kyo.*
import kyo.test.KyoScalatestApi
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.*

class KyoScalatestApiTest extends AnyFreeSpec with Matchers with KyoScalatestApi:

    "test" in runKyoSync:
        val varEffect =
            for
                i  <- Var.get[Int]
                _  <- Var.update[Int](_ + 1)
                i2 <- Var.get[Int]
                _  <- assertKyo(i2 shouldBe i + 1)
                _  <- assertKyo(i2 == i + 1)
            yield ()
        Choice.run:
            for
                i <- Choice.evalSeq(Range(0, 100))
                _ <- Var.run(i)(varEffect)
            yield ()
            end for
end KyoScalatestApiTest
