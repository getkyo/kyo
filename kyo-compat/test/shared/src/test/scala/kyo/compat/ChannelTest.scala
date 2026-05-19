package kyo.compat

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kyo.compat.*
import scala.concurrent.duration.*
import scala.util.Failure
import scala.util.Success

class ChannelTest extends CompatTest:

    "init succeeds with a positive capacity" in run {
        val c = CChannel.init[Int](4).flatMap(_ => CIO.defer { 42 })
        c.map(v => assert(v == 42))
    }

    "take returns the value most recently put" in run {
        val c =
            CChannel.init[Int](4).flatMap { ch =>
                ch.put(7).flatMap { _ =>
                    ch.take
                }
            }
        c.map(v => assert(v == 7))
    }

    "put blocks when the channel is full" in run {
        // Capacity 1: put #1 succeeds, put #2 must block. We bound the
        // would-block put with a 50ms timeout — expect None.
        val c =
            CChannel.init[Int](1).flatMap { ch =>
                ch.put(1).flatMap { _ =>
                    CIO.timeout(50.millis)(ch.put(2))
                }
            }
        c.map(r => assert(r == None))
    }

    "take blocks when the channel is empty" in run {
        val c =
            CChannel.init[Int](2).flatMap { ch =>
                CIO.timeout(50.millis)(ch.take)
            }
        c.map(r => assert(r == None))
    }

    "poll returns None when the channel is empty" in run {
        val c =
            CChannel.init[Int](2).flatMap { ch =>
                ch.poll
            }
        c.map(r => assert(r == None))
    }

    "take returns values in FIFO order" in run {
        val c =
            CChannel.init[Int](4).flatMap { ch =>
                ch.put(1).flatMap { _ =>
                    ch.put(2).flatMap { _ =>
                        ch.put(3).flatMap { _ =>
                            ch.take.flatMap { a =>
                                ch.take.flatMap { b =>
                                    ch.take.flatMap { d =>
                                        CIO.defer((a, b, d))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        c.map { case (a, b, d) =>
            assert(a == 1 && b == 2 && d == 3)
        }
    }

    "concurrent producer and consumer agree on the transferred value" in run {
        // Run a producer and consumer concurrently; verify the consumer
        // receives the value the producer put.
        val c =
            CChannel.init[Int](2).flatMap { ch =>
                val producer = ch.put(99)
                val consumer = ch.take
                CIO.zip(producer, consumer).flatMap { case (_, taken) =>
                    CIO.defer(taken)
                }
            }
        c.map(v => assert(v == 99))
    }

    "capacity-1 channel alternates put and take without blocking" in run {
        // Capacity 1 with sequential put/take/put/take must succeed for both
        // values without blocking (each take frees a slot for the next put).
        val c =
            CChannel.init[Int](1).flatMap { ch =>
                ch.put(10).flatMap { _ =>
                    ch.take.flatMap { a =>
                        ch.put(20).flatMap { _ =>
                            ch.take.flatMap { b =>
                                CIO.defer((a, b))
                            }
                        }
                    }
                }
            }
        c.map { case (a, b) =>
            assert(a == 10 && b == 20)
        }
    }
    "multiple concurrent producers and consumers transfer all values exactly once" in run {
        // 5 producers each put 10 items (0-9 tagged with producer id),
        // 5 consumers each take 10 items. Verify produced Set == consumed Set.
        val produced = new ConcurrentLinkedQueue[Int]()
        val consumed = new ConcurrentLinkedQueue[Int]()
        val c =
            CChannel.init[Int](20).flatMap { ch =>
                val producers = CIO.foreach(0 until 5) { prod =>
                    CFiber.init(CIO.foreach(0 until 10) { i =>
                        val item = prod * 10 + i
                        ch.put(item).flatMap { _ =>
                            CIO.defer { val _ = produced.add(item); () }
                        }
                    })
                }
                val consumers = CIO.foreach(0 until 5) { _ =>
                    CFiber.init(CIO.foreach(0 until 10) { _ =>
                        ch.take.flatMap { v =>
                            CIO.defer { val _ = consumed.add(v); () }
                        }
                    })
                }
                producers.flatMap { prodFibers =>
                    consumers.flatMap { consFibers =>
                        CIO.foreach(prodFibers.lower)(_.get).flatMap { _ =>
                            CIO.foreach(consFibers.lower)(_.get).flatMap { _ =>
                                CIO.defer(())
                            }
                        }
                    }
                }
            }
        c.map { _ =>
            import scala.jdk.CollectionConverters.*
            val prod = produced.asScala.toSet
            val cons = consumed.asScala.toSet
            assert(prod.size == 50, s"expected 50 produced, got ${prod.size}")
            assert(cons.size == 50, s"expected 50 consumed, got ${cons.size}")
            assert(prod == cons, s"produced set != consumed set")
        }
    }

    "poll vs take semantics: poll returns None on empty; after put, poll returns Some" in run {
        // empty chan.poll → None; put(7) then poll → Some(7); then poll again → None.
        val c =
            CChannel.init[Int](2).flatMap { ch =>
                ch.poll.flatMap { empty =>
                    ch.put(7).flatMap { _ =>
                        ch.poll.flatMap { some =>
                            ch.poll.flatMap { again =>
                                CIO.defer((empty, some, again))
                            }
                        }
                    }
                }
            }
        c.map { case (empty, some, again) =>
            assert(empty == None, s"poll on empty should return None, got: $empty")
            assert(some == Some(7), s"poll after put(7) should return Some(7), got: $some")
            assert(again == None, s"poll after consuming should return None, got: $again")
        }
    }

    "channel round-trip lift/lower is observably equivalent" in run {
        // CChannel.lift(CChannel.init(2).lower) — put and take work identically.
        val c =
            CChannel.init[Int](2).flatMap { original =>
                val lifted = CChannel.lift(original.lower)
                lifted.put(10).flatMap { _ =>
                    lifted.put(20).flatMap { _ =>
                        lifted.take.flatMap { a =>
                            lifted.take.flatMap { b =>
                                CIO.defer((a, b))
                            }
                        }
                    }
                }
            }
        c.map { case (a, b) =>
            assert(a == 10 && b == 20, s"expected (10, 20), got ($a, $b)")
        }
    }

end ChannelTest
