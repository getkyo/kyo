package kyo

import scala.collection.mutable.ArrayBuffer

class WhileTest extends Test:

    "atomic counter" in run {
        defer {
            val counter = AtomicInt.init(0).now
            while counter.get.now < 3 do
                counter.incrementAndGet.now
                ()
            val result = counter.get.now
            assert(result == 3)
        }
    }

    "accumulating buffers" in run {
        val buff1 = new ArrayBuffer[Int]()
        val buff2 = new ArrayBuffer[Int]()
        var i     = 0

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

        defer {
            while i < 3 do
                IO(incrementA()).now
                IO(incrementB()).now
                ()
            end while
            assert(i == 4)
            assert(buff1.toList == List(1, 3))
            assert(buff2.toList == List(2, 4))
        }
    }

    "effectful condition" - {
        "simple condition" in run {
            defer {
                val counter = AtomicInt.init(0).now
                while counter.get.now < 5 do
                    counter.incrementAndGet.now
                    ()
                assert(counter.get.now == 5)
            }
        }

        "compound condition" in run {
            defer {
                val counter1 = AtomicInt.init(0).now
                val counter2 = AtomicInt.init(10).now
                while counter1.get.now < 5 && counter2.get.now > 5 do
                    counter1.incrementAndGet.now
                    counter2.decrementAndGet.now
                    ()
                end while
                val c1 = counter1.get.now
                val c2 = counter2.get.now
                assert(c1 == 5)
                assert(c2 == 5)
            }
        }
    }

    "nested effects" - {
        "in condition and body" in run {
            val results = ArrayBuffer[Int]()
            defer {
                val counter = AtomicInt.init(0).now
                while counter.get.now < 3 do
                    val current = counter.incrementAndGet.now
                    IO(results += current).now
                    ()
                end while
                val finalCount = counter.get.now
                assert(finalCount == 3)
                assert(results.toList == List(1, 2, 3))
            }
        }

        "with abort effect" in run {
            defer {
                val counter = AtomicInt.init(0).now
                val result = Abort.run {
                    defer {
                        while counter.get.now < 2 do
                            if counter.get.now >= 5 then
                                Abort.fail("Too high").now
                            counter.incrementAndGet.now
                            ()
                        end while
                        counter.get.now
                    }
                }.now
                assert(result == Result.succeed(2))
            }
        }
    }

    "complex control flow" - {
        "break using abort" in run {
            defer {
                val counter = AtomicInt.init(0).now
                val result = Abort.run {
                    defer {
                        while true do
                            val current = counter.incrementAndGet.now
                            if current >= 3 then
                                Abort.fail(current).now
                            ()
                        end while
                        -1
                    }
                }.now
                assert(result == Result.fail(3))
            }
        }

        "continue pattern" in run {
            val evens = ArrayBuffer[Int]()
            defer {
                val counter = AtomicInt.init(0).now
                while counter.get.now < 5 do
                    val current = counter.incrementAndGet.now
                    if IO(current % 2 == 1).now then
                        () // Skip odd numbers
                    else
                        IO { evens += current }.now
                        ()
                    end if
                end while
                val finalCount = counter.get.now
                assert(finalCount == 5)
                assert(evens.toList == List(2, 4))
            }
        }
    }

    "error handling" in run {
        val operations = ArrayBuffer[String]()
        defer {
            val result = Abort.run {
                defer {
                    val counter = AtomicInt.init(0).now
                    while counter.get.now < 5 do
                        val op = s"op${counter.get.now}"
                        IO { operations += op }.now
                        val current = counter.incrementAndGet.now
                        if current == 2 then
                            Abort.fail(s"Error at $current").now
                        ()
                    end while
                    counter.get.now
                }
            }.now
            assert(result == Result.fail("Error at 2"))
            assert(operations == ArrayBuffer("op0", "op1"))
        }
    }
end WhileTest
