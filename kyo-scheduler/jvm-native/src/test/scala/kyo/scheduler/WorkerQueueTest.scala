package kyo.scheduler

import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AnyFreeSpec

class WorkerQueueTest extends AnyFreeSpec with NonImplicitAssertions {

    // A Task whose runtime() returns exactly the injected value (>= 1). Task.State.init is 1
    // and addRuntime(v) yields 1 + v, so addRuntime(runtime - 1) lands runtime() at `runtime`.
    // Lower runtime polls first.
    def task(runtime: Int): Task = {
        val t = TestTask()
        t.addRuntime(runtime - 1)
        t
    }

    "isEmpty" - {
        "when queue is empty" in {
            val queue = new WorkerQueue()
            assert(queue.isEmpty())
        }

        "when queue is not empty" in {
            val queue = new WorkerQueue()
            queue.add(task(1))
            assert(!queue.isEmpty())
        }
    }

    "size" - {
        "when queue is empty" in {
            val queue = new WorkerQueue()
            assert(queue.size() == 0)
        }

        "when elements are added" in {
            val queue = new WorkerQueue()
            queue.add(task(1))
            assert(queue.size() == 1)
            queue.add(task(2))
            assert(queue.size() == 2)
        }
    }

    "add and poll" - {
        "adding and polling elements" in {
            val queue = new WorkerQueue()
            val t1    = task(1)
            val t2    = task(2)
            val t3    = task(3)
            queue.add(t1)
            queue.add(t2)
            queue.add(t3)
            assert((queue.poll(): Task) eq t1)
            assert((queue.poll(): Task) eq t2)
            assert((queue.poll(): Task) eq t3)
            assert(queue.poll() == null)
        }
        "multiple elements" in {
            val queue = new WorkerQueue()
            val t3    = task(3)
            val t1    = task(1)
            val t4    = task(4)
            val t2    = task(2)
            queue.add(t3)
            queue.add(t1)
            queue.add(t4)
            queue.add(t2)
            assert((queue.poll(): Task) eq t1)
            assert((queue.poll(): Task) eq t2)
            assert((queue.poll(): Task) eq t3)
            assert((queue.poll(): Task) eq t4)
            assert(queue.poll() == null)
        }
    }

    "interrupt prioritization" - {
        "polls a runtime-reset task ahead of higher-runtime tasks" in {
            val queue  = new WorkerQueue()
            val victim = TestTask()
            victim.addRuntime(1000) // long-running, hence high runtime / low priority
            victim.resetRuntime()   // dropped to the minimum: highest scheduling priority

            val busy1 = TestTask() // runtime 1
            val busy2 = TestTask()

            queue.add(busy1)
            queue.add(victim)
            queue.add(busy2)

            assert(
                (queue.poll(): Task) eq victim,
                "the runtime-reset task should be the highest-priority element"
            )
        }
    }

    "offer" - {
        "offering elements" in {
            val queue = new WorkerQueue()
            assert(queue.offer(task(1)))
            assert(queue.offer(task(2)))
            assert(queue.size() == 2)
        }

        "when queue is empty" in {
            val queue = new WorkerQueue()
            val t1    = task(1)
            assert(queue.offer(t1))
            assert(queue.size() == 1)
            assert((queue.poll(): Task) eq t1)
        }

        "multiple elements" in {
            val queue = new WorkerQueue()
            val t3    = task(3)
            val t1    = task(1)
            val t4    = task(4)
            val t2    = task(2)
            assert(queue.offer(t3))
            assert(queue.offer(t1))
            assert(queue.offer(t4))
            assert(queue.offer(t2))
            assert(queue.size() == 4)
            assert((queue.poll(): Task) eq t1)
            assert((queue.poll(): Task) eq t2)
            assert((queue.poll(): Task) eq t3)
            assert((queue.poll(): Task) eq t4)
        }
    }

    "addAndPoll" - {
        "adding and polling elements" in {
            val queue = new WorkerQueue()
            val t1    = task(1)
            // empty queue: addAndPoll returns the input UNCHANGED (not enqueued)
            assert((queue.addAndPoll(t1): Task) eq t1)
            val t3 = task(3)
            val t2 = task(2)
            queue.add(t3)
            queue.add(t2)
            val t4 = task(4)
            // head is the lowest runtime (t2); addAndPoll inserts t4, returns the old head
            assert((queue.addAndPoll(t4): Task) eq t2)
            assert((queue.poll(): Task) eq t3)
            assert((queue.poll(): Task) eq t4)
        }

        "with empty and non-empty queue" in {
            val queue1 = new WorkerQueue()
            val queue2 = new WorkerQueue()
            val q2a    = task(1)
            val q2b    = task(2)
            queue2.add(q2a)
            queue2.add(q2b)
            val t3 = task(3)
            assert((queue1.addAndPoll(t3): Task) eq t3)
            val t3b = task(3)
            assert((queue2.addAndPoll(t3b): Task) eq q2a)
            assert((queue2.poll(): Task) eq q2b)
            assert((queue2.poll(): Task) eq t3b)
        }
        "multiple elements" in {
            val queue = new WorkerQueue()
            val t1    = task(1)
            queue.add(t1)
            val t2 = task(2)
            assert((queue.addAndPoll(t2): Task) eq t1)
            val t3 = task(3)
            assert((queue.addAndPoll(t3): Task) eq t2)
            val t4 = task(4)
            assert((queue.addAndPoll(t4): Task) eq t3)
            assert((queue.poll(): Task) eq t4)
            assert(queue.poll() == null)
        }
    }

    "steal" - {
        "stealing elements" in {
            val queue1 = new WorkerQueue()
            val queue2 = new WorkerQueue()
            val t1     = task(1)
            val t2     = task(2)
            val t3     = task(3)
            val t4     = task(4)
            queue1.add(t1)
            queue1.add(t2)
            queue1.add(t3)
            queue1.add(t4)
            // head t1 returned; s = size() = 3; i = floor(3/2) = 1 -> one more (t2) moves to queue2
            assert((queue1.stealingBy(queue2): Task) eq t1)
            assert((queue1.poll(): Task) eq t3)
            assert((queue1.poll(): Task) eq t4)
            assert(queue1.poll() == null)
            assert((queue2.poll(): Task) eq t2)
            assert(queue2.poll() == null)
        }

        "when source queue becomes empty" in {
            val queue1 = new WorkerQueue()
            val queue2 = new WorkerQueue()
            val t1     = task(1)
            queue1.add(t1)
            // single element: head t1 returned, i goes negative -> nothing else moves; both empty
            assert((queue1.stealingBy(queue2): Task) eq t1)
            assert(queue1.isEmpty())
            assert(queue2.isEmpty())
        }

        "multiple elements" in {
            val queue1 = new WorkerQueue()
            val queue2 = new WorkerQueue()
            val t1     = task(1)
            val t2     = task(2)
            val t3     = task(3)
            val t4     = task(4)
            val t5     = task(5)
            queue1.add(t1)
            queue1.add(t2)
            queue1.add(t3)
            queue1.add(t4)
            queue1.add(t5)
            // head t1 returned; s = size() = 4; i = floor(4/2) = 2 -> t2,t3 move to queue2
            assert((queue1.stealingBy(queue2): Task) eq t1)
            assert(queue1.size() == 2)
            assert(queue2.size() == 2)
            assert((queue1.poll(): Task) eq t4)
            assert((queue1.poll(): Task) eq t5)
            assert(queue1.poll() == null)
            assert((queue2.poll(): Task) eq t2)
            assert((queue2.poll(): Task) eq t3)
            assert(queue2.poll() == null)
        }
    }

    "drain" - {
        "draining elements" in {
            val queue = new WorkerQueue()
            queue.add(task(1))
            queue.add(task(2))
            queue.add(task(3))
            var sum = 0
            queue.drain(t => sum += t.runtime())
            assert(sum == 6)
            assert(queue.isEmpty())
        }

        "when queue is empty" in {
            val queue = new WorkerQueue()
            var sum   = 0
            queue.drain(t => sum += t.runtime())
            assert(sum == 0)
            assert(queue.isEmpty())
        }

        "multiple elements" in {
            val queue = new WorkerQueue()
            queue.add(task(1))
            queue.add(task(2))
            queue.add(task(3))
            queue.add(task(4))
            var sum = 0
            queue.drain(t => sum += t.runtime())
            assert(sum == 10)
            assert(queue.isEmpty())
        }
    }

    "ordering" - {
        "elements are polled in the correct order" in {
            val queue = new WorkerQueue()
            val t3    = task(3)
            val t1    = task(1)
            val t2    = task(2)
            queue.add(t3)
            queue.add(t1)
            queue.add(t2)
            assert((queue.poll(): Task) eq t1)
            assert((queue.poll(): Task) eq t2)
            assert((queue.poll(): Task) eq t3)
        }
    }

    "empty queue behavior" - {
        "polling from an empty queue" in {
            val queue = new WorkerQueue()
            assert(queue.poll() == null)
        }

        "stealing from an empty queue" in {
            val queue = new WorkerQueue()
            assert(queue.stealingBy(new WorkerQueue()) == null)
        }
    }

    "mixed" - {
        "add, poll, and steal" in {
            val queue1 = new WorkerQueue()
            val queue2 = new WorkerQueue()
            val t1     = task(1)
            val t2     = task(2)
            val t3     = task(3)
            queue1.add(t1)
            queue1.add(t2)
            queue1.add(t3)
            assert((queue1.poll(): Task) eq t1)
            // remaining [t2,t3]; head t2 returned; s = size() = 1; i = floor(1/2) = 0 -> none move
            assert((queue1.stealingBy(queue2): Task) eq t2)
            assert((queue1.poll(): Task) eq t3)
            assert(queue2.poll() == null)
        }

        "offer, addAndPoll, and drain" in {
            val queue = new WorkerQueue()
            val t1    = task(1)
            val t2    = task(2)
            assert(queue.offer(t1))
            assert(queue.offer(t2))
            val t3 = task(3)
            // head t1 returned; t3 inserted; queue now holds t2,t3 -> runtimes sum 5
            assert((queue.addAndPoll(t3): Task) eq t1)
            var sum = 0
            queue.drain(t => sum += t.runtime())
            assert(sum == 5)
            assert(queue.isEmpty())
        }

        "multiple queues with add, poll, and steal" in {
            val queue1 = new WorkerQueue()
            val queue2 = new WorkerQueue()
            val queue3 = new WorkerQueue()
            val t1     = task(1)
            val t2     = task(2)
            val t3     = task(3)
            val t4     = task(4)
            queue1.add(t1)
            queue1.add(t2)
            queue2.add(t3)
            queue2.add(t4)
            // queue1 [t1,t2]: head t1; s=1; i=0 -> none move; queue3 stays empty, queue1 left [t2]
            assert((queue1.stealingBy(queue3): Task) eq t1)
            // queue3 is now empty again? No: i=0 so nothing transferred, queue3 still empty
            // queue2 [t3,t4]: head t3; s=1; i=0 -> none move; queue3 still empty
            assert((queue2.stealingBy(queue3): Task) eq t3)
            assert(queue3.poll() == null)
            assert((queue1.poll(): Task) eq t2)
            assert((queue2.poll(): Task) eq t4)
        }

        "addAndPoll and offer" in {
            val queue = new WorkerQueue()
            val t1    = task(1)
            assert((queue.addAndPoll(t1): Task) eq t1)
            val t2 = task(2)
            assert(queue.offer(t2))
            val t3 = task(3)
            // head is t2 (only element); addAndPoll returns t2, inserts t3
            assert((queue.addAndPoll(t3): Task) eq t2)
        }

        "add, poll, and drain with runtime ordering" in {
            val queue = new WorkerQueue()
            val t4    = task(4)
            val t5    = task(5)
            val t6    = task(6)
            queue.add(t4)
            queue.add(t5)
            queue.add(t6)
            // lowest runtime polls first
            assert((queue.poll(): Task) eq t4)
            // the remaining multiset is {t5, t6}; drain order is array-order, so assert the sum
            var sum = 0
            queue.drain(t => sum += t.runtime())
            assert(sum == 11)
            assert(queue.isEmpty())
        }
    }

    "heap property" - {
        "polls in non-decreasing runtime order after a random add sequence" in {
            val queue    = new WorkerQueue()
            val runtimes = scala.util.Random.shuffle((1 to 200).toList)
            runtimes.foreach(r => queue.add(task(r)))
            var prev   = 0
            var polled = 0
            var t      = queue.poll()
            while (t != null) {
                val r = t.runtime()
                assert(r >= prev, s"poll order violated: $r after $prev")
                prev = r
                polled += 1
                t = queue.poll()
            }
            assert(polled == 200)
            assert(queue.isEmpty())
        }
    }

    "rebuild" - {
        "re-sorts after an in-place runtime key change" in {
            val queue  = new WorkerQueue()
            val victim = TestTask()
            victim.addRuntime(1000) // enqueued at HIGH runtime (low priority)

            queue.add(task(1))
            queue.add(task(2))
            queue.add(victim)
            queue.add(task(3))
            queue.add(task(4))

            // the victim sits deep in the heap; reset its key IN PLACE (no heap op)
            victim.resetRuntime() // runtime drops to 0

            // without rebuild, the victim stays at its old position; rebuild re-sifts it to root
            queue.rebuild()
            assert(
                (queue.poll(): Task) eq victim,
                "after rebuild the in-place-reset task should be the highest-priority element"
            )
        }

        "is a no-op on an empty queue" in {
            val queue = new WorkerQueue()
            queue.rebuild()
            assert(queue.isEmpty())
        }

        "is a no-op on a single-element queue" in {
            val queue = new WorkerQueue()
            val t1    = task(1)
            queue.add(t1)
            queue.rebuild()
            assert(queue.size() == 1)
            assert((queue.poll(): Task) eq t1)
        }
    }

    "stealing split keeps both queues valid heaps" in {
        val queue1 = new WorkerQueue()
        val queue2 = new WorkerQueue()
        val tasks  = (1 to 9).map(task)
        tasks.foreach(queue1.add)
        // head t1 returned; s = 8; i = 8 - ceil(4) = 4 -> t2..t5 move to queue2; queue1 keeps t6..t9
        val head = queue1.stealingBy(queue2)
        assert((head: Task) eq tasks(0))
        assert(queue1.size() == 4)
        assert(queue2.size() == 4)
        // both poll in non-decreasing runtime order (valid heaps)
        def pollAll(q: WorkerQueue): List[Int] = {
            val b = List.newBuilder[Int]
            var t = q.poll()
            while (t != null) {
                b += t.runtime()
                t = q.poll()
            }
            b.result()
        }
        val from1 = pollAll(queue1)
        val from2 = pollAll(queue2)
        assert(from1 == from1.sorted)
        assert(from2 == from2.sorted)
        assert((from1 ++ from2).sorted == List(2, 3, 4, 5, 6, 7, 8, 9))
    }

    "capacity growth follows the doubling schedule" in {
        // queueCapacity() default 8; adding past it doubles. A drain/add loop never grows
        // the backing array beyond the doubling schedule (zero per-op allocation aside from
        // amortized doubling). We assert behaviorally: the queue holds all elements correctly
        // across a grow-past-capacity sequence and polls them back in runtime order.
        val queue = new WorkerQueue()
        val n     = 100 // forces several doublings past the default capacity of 8
        (1 to n).foreach(r => queue.add(task(r)))
        assert(queue.size() == n)
        var prev = 0
        (1 to n).foreach { _ =>
            val t = queue.poll()
            assert(t != null)
            val r = t.runtime()
            assert(r >= prev)
            prev = r
        }
        assert(queue.isEmpty())
        // a fresh add after the loop still works (array did not corrupt under growth)
        val t = task(1)
        queue.add(t)
        assert((queue.poll(): Task) eq t)
    }

    "concurrency" - {
        class TestExecutor(exec: ExecutorService) {
            def apply[A](v: => A): Future[A] =
                exec.submit(() => v)
        }
        def withExecutor[A](f: TestExecutor => A): A =
            f(new TestExecutor(TestExecutors.cached))

        "add and poll" in withExecutor { executor =>
            val queue = new WorkerQueue()
            val futures = (1 to 1000).map { i =>
                executor(queue.add(task(i)))
            } ++ (1 to 1000).map { _ =>
                executor(while (queue.poll() == null) {})
            }
            futures.foreach(_.get())
            assert(queue.isEmpty())
        }

        "offer" in withExecutor { executor =>
            val queue = new WorkerQueue()
            val futures = (1 to 1000).map { i =>
                executor {
                    while (!queue.offer(task(i))) {}
                }
            }
            futures.foreach(_.get())
            assert(queue.size() == 1000)
        }

        "addAndPoll" in withExecutor { executor =>
            val queue = new WorkerQueue()
            val futures = (1 to 1000).map { i =>
                executor(queue.addAndPoll(task(i)))
            }
            futures.foreach(_.get())
            assert(queue.isEmpty())
        }

        "steal" in withExecutor { executor =>
            val queue1 = new WorkerQueue()
            val queue2 = new WorkerQueue()
            val stolen = new WorkerQueue()
            (1 to 1000).foreach(i => queue1.add(task(i)))
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
            val queue = new WorkerQueue()
            (1 to 1000).foreach(i => queue.add(task(i)))
            val sum = new AtomicInteger(0)
            val futures = (1 to 4).map { _ =>
                executor {
                    queue.drain { t =>
                        sum.addAndGet(t.runtime())
                        ()
                    }
                }
            }
            futures.foreach(_.get())
            assert(sum.get() == (1 to 1000).sum)
            assert(queue.isEmpty())
        }

        "add and steal" in withExecutor { executor =>
            val queue1 = new WorkerQueue()
            val queue2 = new WorkerQueue()
            val stolen = new WorkerQueue()
            val futures = (1 to 1000).map { i =>
                executor {
                    queue1.add(task(i))
                    val v = queue1.stealingBy(queue2)
                    if (v != null)
                        stolen.add(v)
                }
            }
            futures.foreach(_.get())
            val sum = new AtomicInteger(0)
            def add(t: Task): Unit = {
                sum.addAndGet(t.runtime())
                ()
            }
            queue1.drain(add(_))
            queue2.drain(add(_))
            stolen.drain(add(_))
            assert(sum.get() == (1 to 1000).sum)
        }

        "multiple concurrent operations" in withExecutor { executor =>
            val queue         = new WorkerQueue()
            val polled        = new WorkerQueue()
            val polls         = new WorkerQueue()
            val rejected      = new WorkerQueue()
            val numOperations = 1000
            val futures = (1 to numOperations).map { i =>
                executor {
                    val operation = i % 4
                    operation match {
                        case 0 =>
                            queue.add(task(i))
                        case 1 =>
                            if (!queue.offer(task(i)))
                                rejected.add(task(i))
                        case 2 =>
                            val item = queue.addAndPoll(task(i))
                            polled.add(item)
                        case 3 =>
                            val item = queue.poll()
                            if (item != null)
                                polled.add(item)
                            polls.add(task(i))
                    }
                }
            }
            futures.foreach(_.get())

            assert(queue.size() + polled.size() + polls.size() + rejected.size() == numOperations)
            var sum = 0
            queue.drain(t => sum += t.runtime())
            polled.drain(t => sum += t.runtime())
            polls.drain(t => sum += t.runtime())
            rejected.drain(t => sum += t.runtime())
            assert(sum == (1 to numOperations).sum)
        }
    }
}
