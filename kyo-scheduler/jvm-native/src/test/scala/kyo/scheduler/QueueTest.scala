package kyo.scheduler

import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AnyFreeSpec

class QueueTest extends AnyFreeSpec with NonImplicitAssertions {

    implicit val ord: Ordering[Integer] = Ordering.by[Integer, Int](_.intValue()).reverse

    "isEmpty" - {
        "when queue is empty" in {
            val queue = new Queue[Integer]()
            assert(queue.isEmpty())
        }

        "when queue is not empty" in {
            val queue = new Queue[Integer]()
            queue.add(1)
            assert(!queue.isEmpty())
        }
    }

    "size" - {
        "when queue is empty" in {
            val queue = new Queue[Integer]()
            assert(queue.size() == 0)
        }

        "when elements are added" in {
            val queue = new Queue[Integer]()
            queue.add(1)
            assert(queue.size() == 1)
            queue.add(2)
            assert(queue.size() == 2)
        }
    }

    "add and poll" - {
        "adding and polling elements" in {
            val queue = new Queue[Integer]()
            queue.add(1)
            queue.add(2)
            queue.add(3)
            assert(queue.poll() == 1)
            assert(queue.poll() == 2)
            assert(queue.poll() == 3)
            assert(queue.poll() == null)
        }
        "multiple elements" in {
            val queue = new Queue[Integer]()
            queue.add(3)
            queue.add(1)
            queue.add(4)
            queue.add(2)
            assert(queue.poll() == 1)
            assert(queue.poll() == 2)
            assert(queue.poll() == 3)
            assert(queue.poll() == 4)
            assert(queue.poll() == null)
        }
    }

    "offer" - {
        "offering elements" in {
            val queue = new Queue[Integer]()
            assert(queue.offer(1))
            assert(queue.offer(2))
            assert(queue.size() == 2)
        }

        "when queue is empty" in {
            val queue = new Queue[Integer]()
            assert(queue.offer(1))
            assert(queue.size() == 1)
            assert(queue.poll() == 1)
        }

        "multiple elements" in {
            val queue = new Queue[Integer]()
            assert(queue.offer(3))
            assert(queue.offer(1))
            assert(queue.offer(4))
            assert(queue.offer(2))
            assert(queue.size() == 4)
            assert(queue.poll() == 1)
            assert(queue.poll() == 2)
            assert(queue.poll() == 3)
            assert(queue.poll() == 4)
        }
    }

    "addAndPoll" - {
        "adding and polling elements" in {
            val queue = new Queue[Integer]()
            assert(queue.addAndPoll(1) == 1)
            queue.add(3)
            queue.add(2)
            assert(queue.addAndPoll(4) == 2)
            assert(queue.poll() == 3)
            assert(queue.poll() == 4)
        }

        "with empty and non-empty queue" in {
            val queue1 = new Queue[Integer]()
            val queue2 = new Queue[Integer]()
            queue2.add(1)
            queue2.add(2)
            assert(queue1.addAndPoll(3) == 3)
            assert(queue2.addAndPoll(3) == 1)
            assert(queue2.poll() == 2)
            assert(queue2.poll() == 3)
        }
        "multiple elements" in {
            val queue = new Queue[Integer]()
            queue.add(1)
            assert(queue.addAndPoll(2) == 1)
            assert(queue.addAndPoll(3) == 2)
            assert(queue.addAndPoll(4) == 3)
            assert(queue.poll() == 4)
            assert(queue.poll() == null)
        }
    }

    "steal" - {
        "stealing elements" in {
            val queue1 = new Queue[Integer]()
            val queue2 = new Queue[Integer]()
            queue1.add(1)
            queue1.add(2)
            queue1.add(3)
            queue1.add(4)
            assert(queue1.stealingBy(queue2) == 1)
            assert(queue1.poll() == 3)
            assert(queue1.poll() == 4)
            assert(queue1.poll() == null)
            assert(queue2.poll() == 2)
            assert(queue2.poll() == null)
        }

        "when source queue becomes empty" in {
            val queue1 = new Queue[Integer]()
            val queue2 = new Queue[Integer]()
            queue1.add(1)
            assert(queue1.stealingBy(queue2) == 1)
            assert(queue1.isEmpty())
            assert(queue2.isEmpty())
        }

        "multiple elements" in {
            val queue1 = new Queue[Integer]()
            val queue2 = new Queue[Integer]()
            queue1.add(1)
            queue1.add(2)
            queue1.add(3)
            queue1.add(4)
            queue1.add(5)
            assert(queue1.stealingBy(queue2) == 1)
            assert(queue1.size() == 2)
            assert(queue2.size() == 2)
            assert(queue1.poll() == 4)
            assert(queue1.poll() == 5)
            assert(queue1.poll() == null)
            assert(queue2.poll() == 2)
            assert(queue2.poll() == 3)
            assert(queue2.poll() == null)
        }
    }

    "drain" - {
        "draining elements" in {
            val queue = new Queue[Integer]()
            queue.add(1)
            queue.add(2)
            queue.add(3)
            var sum = 0
            queue.drain(i => sum += i)
            assert(sum == 6)
            assert(queue.isEmpty())
        }

        "when queue is empty" in {
            val queue = new Queue[Integer]()
            var sum   = 0
            queue.drain(i => sum += i)
            assert(sum == 0)
            assert(queue.isEmpty())
        }

        "multiple elements" in {
            val queue = new Queue[Integer]()
            queue.add(1)
            queue.add(2)
            queue.add(3)
            queue.add(4)
            var sum = 0
            queue.drain(i => sum += i)
            assert(sum == 10)
            assert(queue.isEmpty())
        }
    }

    "ordering" - {
        "elements are polled in the correct order" in {
            val queue = new Queue[Integer]()
            queue.add(3)
            queue.add(1)
            queue.add(2)
            assert(queue.poll() == 1)
            assert(queue.poll() == 2)
            assert(queue.poll() == 3)
        }
    }

    "empty queue behavior" - {
        "polling from an empty queue" in {
            val queue = new Queue[Integer]()
            assert(queue.poll() == null)
        }

        "stealing from an empty queue" in {
            val queue = new Queue[Integer]()
            assert(queue.stealingBy(new Queue[Integer]()) == null)
        }
    }

    "mixed" - {
        "add, poll, and steal" in {
            val queue1 = new Queue[Integer]()
            val queue2 = new Queue[Integer]()
            queue1.add(1)
            queue1.add(2)
            queue1.add(3)
            assert(queue1.poll() == 1)
            assert(queue1.stealingBy(queue2) == 2)
            assert(queue1.poll() == 3)
            assert(queue2.poll() == null)
        }

        "offer, addAndPoll, and drain" in {
            val queue = new Queue[Integer]()
            assert(queue.offer(1))
            assert(queue.offer(2))
            assert(queue.addAndPoll(3) == 1)
            var sum = 0
            queue.drain(i => sum += i)
            assert(sum == 5)
            assert(queue.isEmpty())
        }

        "multiple queues with add, poll, and steal" in {
            val queue1 = new Queue[Integer]()
            val queue2 = new Queue[Integer]()
            val queue3 = new Queue[Integer]()
            queue1.add(1)
            queue1.add(2)
            queue2.add(3)
            queue2.add(4)
            assert(queue1.stealingBy(queue3) == 1)
            assert(queue2.stealingBy(queue3) == 3)
            assert(queue3.poll() == null)
            assert(queue1.poll() == 2)
            assert(queue2.poll() == 4)
        }

        "addAndPoll, offer, and toString" in {
            val queue = new Queue[Integer]()
            assert(queue.addAndPoll(1) == 1)
            assert(queue.offer(2))
            assert(queue.addAndPoll(3) == 2)
        }

        "add, poll, and drain with custom ordering" in {
            implicit val ord: Ordering[String] =
                Ordering.by[String, Int](_.length).reverse
            val queue = new Queue[String]()
            queue.add("apple")
            queue.add("banana")
            queue.add("pear")
            assert(queue.poll() == "pear")
            var result = ""
            queue.drain(s => result += s)
            assert(result == "applebanana")
        }
    }

    "concurrency" - {
        class TestExecutor(exec: ExecutorService) {
            def apply[A](v: => A): Future[A] =
                exec.submit(() => v)
        }
        def withExecutor[A](f: TestExecutor => A): A = {
            val executor = Executors.newFixedThreadPool(4)
            try f(new TestExecutor(executor))
            finally executor.shutdown()
        }

        "add and poll" in withExecutor { executor =>
            val queue = new Queue[Integer]()
            val futures = (1 to 1000).map { i =>
                executor(queue.add(i))
            } ++ (1 to 1000).map { _ =>
                executor(while (queue.poll() == null) {})
            }
            futures.foreach(_.get())
            assert(queue.isEmpty())
        }

        "offer" in withExecutor { executor =>
            val queue = new Queue[Integer]()
            val futures = (1 to 1000).map { i =>
                executor {
                    while (!queue.offer(i)) {}
                }
            }
            futures.foreach(_.get())
            assert(queue.size() == 1000)
        }

        "addAndPoll" in withExecutor { executor =>
            val queue = new Queue[Integer]()
            val futures = (1 to 1000).map { i =>
                executor(queue.addAndPoll(i))
            }
            futures.foreach(_.get())
            assert(queue.isEmpty())
        }

        "steal" in withExecutor { executor =>
            val queue1 = new Queue[Integer]()
            val queue2 = new Queue[Integer]()
            val stolen = new Queue[Integer]()
            (1 to 1000).foreach(queue1.add(_))
            val futures = (1 to 500).map { _ =>
                executor {
                    val v = queue1.stealingBy(queue2)
                    if (v != null) stolen.add(v)
                }
            }
            futures.foreach(_.get())
            assert(queue1.size() + queue2.size() + stolen.size() == 1000)
        }

        "drain" in withExecutor { executor =>
            val queue = new Queue[Integer]()
            (1 to 1000).foreach(queue.add(_))
            val sum = new AtomicInteger(0)
            val futures = (1 to 4).map { _ =>
                executor {
                    queue.drain { i =>
                        sum.addAndGet(i)
                        ()
                    }
                }
            }
            futures.foreach(_.get())
            assert(sum.get() == (1 to 1000).sum)
            assert(queue.isEmpty())
        }

        "add and steal" in withExecutor { executor =>
            val queue1 = new Queue[Integer]()
            val queue2 = new Queue[Integer]()
            val stolen = new Queue[Integer]()
            val futures = (1 to 1000).map { i =>
                executor {
                    queue1.add(i)
                    val v = queue1.stealingBy(queue2)
                    if (v != null)
                        stolen.add(v)
                }
            }
            futures.foreach(_.get())
            val sum = new AtomicInteger(0)
            def add(i: Int): Unit = {
                sum.addAndGet(i)
                ()
            }
            queue1.drain(add(_))
            queue2.drain(add(_))
            stolen.drain(add(_))
            assert(sum.get() == (1 to 1000).sum)
        }

        "multiple concurrent operations" in withExecutor { executor =>
            val queue         = new Queue[Integer]()
            val polled        = new Queue[Integer]()
            val polls         = new Queue[Integer]()
            val rejected      = new Queue[Integer]()
            val numOperations = 1000
            val futures = (1 to numOperations).map { i =>
                executor {
                    val operation = i % 4
                    operation match {
                        case 0 =>
                            queue.add(i)
                        case 1 =>
                            if (!queue.offer(i))
                                rejected.add(i)
                        case 2 =>
                            val item = queue.addAndPoll(i)
                            polled.add(item)
                        case 3 =>
                            val item = queue.poll()
                            if (item != null)
                                polled.add(item)
                            polls.add(i)
                    }
                }
            }
            futures.foreach(_.get())

            assert(queue.size() + polled.size() + polls.size() + rejected.size() == numOperations)
            var sum = 0
            queue.drain(i => sum += i)
            polled.drain(i => sum += i)
            polls.drain(i => sum += i)
            rejected.drain(i => sum += i)
            assert(sum == (1 to numOperations).sum)
        }
    }
}
