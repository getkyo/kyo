package kyo.test

import kyo.*
import zio.test.*

object KyoZioTestApiSpec extends ZIOSpecDefault with KyoZioTestApi:
    def spec: Spec[Any, Any] =
        suite("test")(
            test("sync")(
                runKyoSync:
                    val varEffect =
                        for
                            i  <- Var.get[Int]
                            _  <- Var.update[Int](_ + 1)
                            i2 <- Var.get[Int]
                            _  <- assertKyo(assertTrue(i2 == i + 1))
                            _  <- assertKyo(i2 == i + 1)
                        yield ()
                    Choice.run:
                        for
                            i <- Choice.evalSeq(Range(0, 100))
                            _ <- Var.run(i)(varEffect)
                        yield ()
                        end for
            ),
            test("async")(
                runKyoAsync:
                    val varEffect =
                        for
                            i  <- Var.get[Int]
                            _  <- Var.update[Int](_ + 1)
                            i2 <- Var.get[Int]
                            _  <- assertKyo(assertTrue(i2 == i + 1))
                            _  <- assertKyo(i2 == i + 1)
                        yield ()
                    Choice.run:
                        for
                            i <- Choice.evalSeq(Range(0, 100))
                            _ <- Var.run(i)(varEffect)
                        yield ()
                        end for
            )
        )
    end spec
end KyoZioTestApiSpec
