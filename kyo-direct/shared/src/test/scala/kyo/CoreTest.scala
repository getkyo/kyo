package kyo

class CoreTest extends Test:

    "atomic operations" - {
        "AtomicInt" in run {
            defer {
                val counter = AtomicInt.init(0).now
                counter.incrementAndGet.now
                counter.incrementAndGet.now
                counter.decrementAndGet.now
                assert(counter.get.now == 1)
            }
        }

        "AtomicRef" in run {
            defer {
                val ref = AtomicRef.init("initial").now
                ref.set("updated").now
                assert(ref.get.now == "updated")
            }
        }
    }

    "clock operations" - {
        "sleep and timeout" in run {
            defer {
                val start = Clock.now.now
                Async.sleep(5.millis).now
                val elapsed = Clock.now.now - start
                assert(elapsed >= 5.millis)
            }
        }

        "deadline" in run {
            defer {
                val deadline = Clock.deadline(1.second).now
                assert(!deadline.isOverdue.now)
                assert(deadline.timeLeft.now <= 1.second)
            }
        }
    }

    // TODO Compiler crash because `Queue` is an opaque type without a type bound
    // "queue operations" - {
    //     "basic queue" in run {
    //         defer {
    //             val queue = Queue.init[Int](3).now
    //             assert(queue.offer(1).now)
    //             assert(queue.offer(2).now)
    //             assert(queue.poll.now.contains(1))
    //             assert(queue.size.now == 1)
    //         }
    //     }

    //     "unbounded queue" in run {
    //         defer {
    //             val queue = Queue.Unbounded.init[Int]().now
    //             queue.add(1).now
    //             queue.add(2).now
    //             queue.add(3).now
    //             assert(queue.drain.now == Chunk(1, 2, 3))
    //         }
    //     }
    // }

    "random operations" - {
        "basic random" in run {
            defer {
                val r1 = Random.nextInt(10).now
                val r2 = Random.nextInt(10).now
                assert(r1 >= 0 && r1 < 10)
                assert(r2 >= 0 && r2 < 10)
            }
        }

        "with seed" in run {
            defer {
                val results1 = Random.withSeed(42) {
                    defer {
                        val a = Random.nextInt(100).now
                        val b = Random.nextInt(100).now
                        (a, b)
                    }
                }.now

                val results2 = Random.withSeed(42) {
                    defer {
                        val a = Random.nextInt(100).now
                        val b = Random.nextInt(100).now
                        (a, b)
                    }
                }.now

                assert(results1 == results2)
            }
        }
    }

    "console operations" in run {
        Console.withOut {
            defer {
                Console.printLine("test output").now
            }
        }.map { case (output, _) =>
            assert(output.stdOut == "test output\n")
            assert(output.stdErr.isEmpty)
        }
    }

    "meter operations" - {
        "semaphore" in run {
            defer {
                val sem = Meter.initSemaphore(2).now
                assert(sem.availablePermits.now == 2)
                sem.run {
                    defer {
                        assert(sem.availablePermits.now == 1)
                    }
                }.now
                assert(sem.availablePermits.now == 2)
            }
        }

        "mutex" in run {
            defer {
                val mutex = Meter.initMutex.now
                assert(mutex.availablePermits.now == 1)
                mutex.run {
                    defer {
                        assert(mutex.availablePermits.now == 0)
                    }
                }.now
                assert(mutex.availablePermits.now == 1)
            }
        }
    }

    // TODO Compiler crash because `Queue` is an opaque type without a type bound
    // "channel operations" in run {
    //     defer {
    //         val channel = Channel.init[Int](2).now
    //         assert(channel.offer(1).now)
    //         assert(channel.offer(2).now)
    //         assert(!channel.offer(3).now) // Should be full
    //         assert(channel.poll.now.contains(1))
    //         assert(channel.poll.now.contains(2))
    //         assert(channel.poll.now.isEmpty)
    //     }
    // }

    "barrier operations" in run {
        defer {
            val barrier = Barrier.init(2).now
            assert(barrier.pending.now == 2)

            // Start two fibers that will wait at the barrier
            val fiber1 = Async.run {
                defer {
                    barrier.await.now
                    true
                }
            }.now

            val fiber2 = Async.run {
                defer {
                    barrier.await.now
                    true
                }
            }.now

            // Both fibers should complete successfully
            assert(fiber1.get.now)
            assert(fiber2.get.now)
            assert(barrier.pending.now == 0)
        }
    }

    "latch operations" in run {
        defer {
            val latch = Latch.init(2).now
            assert(latch.pending.now == 2)
            latch.release.now
            assert(latch.pending.now == 1)
            latch.release.now
            val awaited = Async.run {
                defer {
                    latch.await.now
                    true
                }
            }.now
            assert(awaited.get.now)
        }
    }
end CoreTest
