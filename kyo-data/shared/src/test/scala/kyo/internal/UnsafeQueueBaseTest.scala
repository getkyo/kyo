package kyo.internal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kyo.*
import kyo.AllowUnsafe.embrace.danger

/** Base test class for all UnsafeQueue implementations. Each concrete test class extends this with the appropriate queue factory.
  *
  * This base holds all tests for all platforms. The thread-based concurrency stress tests are gated `.notJs` (compile-excluded on JS) because
  * they use `java.lang.Thread`, `CountDownLatch`, and real multithreading which is meaningless on single-threaded Scala.js.
  */
abstract class UnsafeQueueBaseTest extends kyo.test.Test[Any]:

    // The queue concurrency stress tests spawn real producer/consumer threads and block on CountDownLatch.await; run a suite's
    // leaves sequentially so a blocking leaf does not starve the bounded worker pool shared with other leaves.
    override def config = super.config.sequential

    def queueName: String
    def isBounded: Boolean
    def nProducers: Int
    def nConsumers: Int
    def testSizes: Seq[Int]
    def makeQueue[A](size: Int): UnsafeQueue[A]

    // ---- Helpers ----

    protected def fill[A](q: UnsafeQueue[A], values: Seq[A])(using kyo.test.AssertScope): Unit =
        values.foreach(v => assert(q.offer(v)))

    protected def pollAll[A](q: UnsafeQueue[A]): Seq[A] =
        val buf = Seq.newBuilder[A]
        var r   = q.poll()
        while r.isDefined do
            buf += r.get
            r = q.poll()
        buf.result()
    end pollAll

    protected def drainAll[A](q: UnsafeQueue[A]): Seq[A] =
        val buf = Seq.newBuilder[A]
        q.drain(v => buf += v)
        buf.result()
    end drainAll

    // ---- A. Sequential Core API ----

    s"$queueName" - {
        for cap <- testSizes do
            s"capacity=$cap" - {

                "offerPollFIFO" in {
                    val n  = if isBounded then cap else cap * 3
                    val q  = makeQueue[Int](cap)
                    val xs = 0 until n
                    fill(q, xs)
                    assert(pollAll(q) == xs)
                }

                "offerPollReferenceIdentity" in {
                    val q   = makeQueue[AnyRef](cap)
                    val obj = new Object
                    assert(q.offer(obj))
                    val r = q.poll()
                    assert(r.isDefined)
                    assert(r.get eq obj)
                }

                "sizeMatchesOffers" in {
                    val q = makeQueue[Int](cap)
                    val n = if isBounded then cap else cap * 2
                    for i <- 0 until n do
                        assert(q.size() == i, s"size mismatch at i=$i")
                        q.offer(i)
                    assert(q.size() == n)
                }

                "sizeMatchesPolls" in {
                    val q = makeQueue[Int](cap)
                    val n = if isBounded then cap else cap * 2
                    fill(q, 0 until n)
                    for i <- 0 until n do
                        assert(q.size() == n - i, s"size mismatch at i=$i")
                        q.poll()
                    assert(q.size() == 0)
                }

                "isEmptyOnFreshQueue" in {
                    val q = makeQueue[Int](cap)
                    assert(q.isEmpty())
                    assert(q.size() == 0)
                }

                "isEmptyAfterOfferPoll" in {
                    val q = makeQueue[Int](cap)
                    q.offer(1)
                    q.poll()
                    assert(q.isEmpty())
                }

                "notEmptyAfterOffer" in {
                    val q = makeQueue[Int](cap)
                    q.offer(1)
                    assert(!q.isEmpty())
                }

                "pollEmptyReturnsAbsent" in {
                    val q = makeQueue[Int](cap)
                    assert(q.poll().isEmpty)
                }

                "peekEmptyReturnsAbsent" in {
                    val q = makeQueue[Int](cap)
                    assert(q.peek().isEmpty)
                }

                "peekDoesNotRemove" in {
                    val q = makeQueue[Int](cap)
                    q.offer(42)
                    assert(q.peek() == Maybe(42))
                    assert(q.size() == 1)
                    assert(q.poll() == Maybe(42))
                }

                "peekReturnsSameElementRepeatedly" in {
                    val q = makeQueue[Int](cap)
                    q.offer(99)
                    for _ <- 0 until 10 do
                        assert(q.peek() == Maybe(99))
                    assert(q.size() == 1)
                }

                "peekReturnsFrontElement" in {
                    val q = makeQueue[Int](cap)
                    q.offer(1)
                    q.offer(2)
                    assert(q.peek() == Maybe(1))
                }

                "offerPollInterleavedOneByOne" in {
                    val q = makeQueue[Int](cap)
                    for i <- 0 until 1000 do
                        assert(q.offer(i))
                        assert(q.poll() == Maybe(i))
                    assert(q.isEmpty())
                }

                "offerPollAlternatingBatch" in {
                    val q    = makeQueue[Int](cap)
                    val n    = Math.min(10, if isBounded then cap else 10)
                    val half = n / 2
                    fill(q, 0 until n)
                    val first = (0 until half).map(_ => q.poll().get)
                    // Queue now has n - half items, so we can add half more
                    fill(q, n until n + half)
                    val rest = pollAll(q)
                    val all  = first ++ rest
                    assert(all == (0 until half) ++ (half until n) ++ (n until n + half))
                }

                // ---- B. Drain ----

                "drainEmptyReturnsZero" in {
                    val q     = makeQueue[Int](cap)
                    var count = 0
                    val r     = q.drain(_ => count += 1)
                    assert(r == 0)
                    assert(count == 0)
                }

                "drainAllFIFO" in {
                    val n = if isBounded then cap else cap * 3
                    val q = makeQueue[Int](cap)
                    fill(q, 0 until n)
                    assert(drainAll(q) == (0 until n))
                }

                "drainWithLimit" in {
                    val n = if isBounded then cap else 16
                    val q = makeQueue[Int](cap)
                    fill(q, 0 until n)
                    val limit = Math.min(5, n / 2)
                    val buf   = Seq.newBuilder[Int]
                    val r     = q.drain(v => buf += v, limit)
                    assert(r == limit)
                    assert(buf.result() == (0 until limit))
                    assert(q.size() == n - limit)
                }

                "drainWithLimitZero" in {
                    val q = makeQueue[Int](cap)
                    q.offer(1)
                    val r = q.drain(_ => fail("should not be called"), 0)
                    assert(r == 0)
                    assert(q.size() == 1)
                }

                "drainWithLimitOne" in {
                    val q = makeQueue[Int](cap)
                    fill(q, 0 until 3)
                    val buf = Seq.newBuilder[Int]
                    val r   = q.drain(v => buf += v, 1)
                    assert(r == 1)
                    assert(buf.result() == Seq(0))
                }

                "drainWithLimitExceedingSize" in {
                    val n = if isBounded then cap else 5
                    val q = makeQueue[Int](cap)
                    fill(q, 0 until n)
                    val buf = Seq.newBuilder[Int]
                    val r   = q.drain(v => buf += v, n + 100)
                    assert(r == n)
                    assert(buf.result() == (0 until n))
                    assert(q.isEmpty())
                }

                "drainWithLimitExactlySize" in {
                    val n = if isBounded then cap else 8
                    val q = makeQueue[Int](cap)
                    fill(q, 0 until n)
                    val buf = Seq.newBuilder[Int]
                    val r   = q.drain(v => buf += v, n)
                    assert(r == n)
                    assert(buf.result() == (0 until n))
                    assert(q.isEmpty())
                }

                "drainThenPoll" in {
                    val n = if isBounded then cap else 10
                    val q = makeQueue[Int](cap)
                    fill(q, 0 until n)
                    val limit = Math.min(3, n / 2)
                    val buf   = Seq.newBuilder[Int]
                    q.drain(v => buf += v, limit)
                    val rest = pollAll(q)
                    val all  = buf.result() ++ rest
                    assert(all == (0 until n))
                }

                "drainMultipleTimes" in {
                    val n = if isBounded then cap else 20
                    val q = makeQueue[Int](cap)
                    fill(q, 0 until n)
                    val all   = Seq.newBuilder[Int]
                    val chunk = Math.max(1, n / 4)
                    var total = 0
                    while total < n do
                        val before = total
                        val r      = q.drain(v => all += v, chunk)
                        total += r
                        if r == 0 && total < n then fail(s"drain stalled at $total/$n")
                    end while
                    assert(all.result() == (0 until n))
                }

                "drainCallbackReceivesCorrectValues" in {
                    val q    = makeQueue[AnyRef](cap)
                    val n    = if isBounded then cap else 5
                    val objs = (0 until n).map(_ => new Object)
                    objs.foreach(q.offer(_))
                    val received = Seq.newBuilder[AnyRef]
                    q.drain(v => received += v)
                    val res = received.result()
                    assert(res.size == n)
                    for i <- 0 until n do
                        assert(res(i) eq objs(i))
                }

                // ---- C. Bounded-only ----

                if isBounded then

                    "capacityIsPowerOfTwo" in {
                        for n <- Seq(3, 5, 7, 10, 100) do
                            val q = makeQueue[Int](n)
                            val c = q.capacity
                            assert((c & (c - 1)) == 0, s"capacity $c is not power of 2 for requested $n")
                            assert(c >= n, s"capacity $c < requested $n")
                    }

                    "capacityExactPowerOfTwo" in {
                        for n <- Seq(4, 8, 16, 64) do
                            val q = makeQueue[Int](n)
                            assert(q.capacity == n, s"capacity ${q.capacity} != $n")
                    }

                    "offerRejectsAtCapacity" in {
                        val q = makeQueue[Int](cap)
                        fill(q, 0 until q.capacity)
                        assert(!q.offer(999))
                        assert(q.size() == q.capacity)
                    }

                    "offerRejectsRepeatedlyAtCapacity" in {
                        val q = makeQueue[Int](cap)
                        fill(q, 0 until q.capacity)
                        for _ <- 0 until 10 do
                            assert(!q.offer(999))
                    }

                    "isFullAtCapacity" in {
                        val q = makeQueue[Int](cap)
                        fill(q, 0 until q.capacity)
                        assert(q.isFull())
                    }

                    "notFullBelowCapacity" in {
                        val q = makeQueue[Int](cap)
                        fill(q, 0 until q.capacity - 1)
                        assert(!q.isFull())
                    }

                    "notFullAfterPollFromFull" in {
                        val q = makeQueue[Int](cap)
                        fill(q, 0 until q.capacity)
                        q.poll()
                        assert(!q.isFull())
                    }

                    "offerSucceedsAfterPollFromFull" in {
                        val q = makeQueue[Int](cap)
                        fill(q, 0 until q.capacity)
                        q.poll()
                        assert(q.offer(999))
                        assert(q.size() == q.capacity)
                    }

                    "wrapAroundFIFO" in {
                        val q = makeQueue[Int](cap)
                        fill(q, 0 until q.capacity)
                        val expected = (0 until q.capacity * 3).toBuffer
                        val received = scala.collection.mutable.Buffer[Int]()
                        for i <- q.capacity until q.capacity * 3 do
                            received += q.poll().get
                            assert(q.offer(i))
                        received ++= pollAll(q)
                        assert(received.toSeq == expected.toSeq)
                    }

                    "fillDrainRepeat" in {
                        val q = makeQueue[Int](cap)
                        for round <- 0 until 10 do
                            val start = round * q.capacity
                            fill(q, start until start + q.capacity)
                            val drained = drainAll(q)
                            assert(drained == (start until start + q.capacity), s"round $round failed")
                        end for
                    }

                    "partialFillDrainRepeat" in {
                        val q    = makeQueue[Int](cap)
                        val half = q.capacity / 2
                        for round <- 0 until 20 do
                            val start = round * half
                            fill(q, start until start + half)
                            assert(drainAll(q) == (start until start + half))
                        end for
                    }

                    "sizeAccuracyAtEveryFillLevel" in {
                        val q = makeQueue[Int](cap)
                        for i <- 0 to q.capacity do
                            if i < q.capacity then discard(q.offer(i))
                            assert(q.size() == Math.min(i + (if i < q.capacity then 1 else 0), q.capacity))
                        val q2 = makeQueue[Int](cap)
                        for i <- 0 until q2.capacity do
                            q2.offer(i)
                            assert(q2.size() == i + 1)
                        for i <- 0 until q2.capacity do
                            q2.poll()
                            assert(q2.size() == q2.capacity - i - 1)
                    }
                end if

                // ---- D. Unbounded-only ----

                if !isBounded then

                    "offerAlwaysSucceeds" in {
                        val q = makeQueue[Int](cap)
                        for i <- 0 until 10000 do
                            assert(q.offer(i))
                    }

                    "isFullAlwaysFalse" in {
                        val q = makeQueue[Int](cap)
                        assert(!q.isFull())
                        for i <- 0 until 100 do q.offer(i)
                        assert(!q.isFull())
                    }

                    "capacityIsMaxValue" in {
                        val q = makeQueue[Int](cap)
                        assert(q.capacity == Int.MaxValue)
                    }

                    "offerBeyondChunkSize" in {
                        val q = makeQueue[Int](cap)
                        val n = cap * 2
                        fill(q, 0 until n)
                        assert(pollAll(q) == (0 until n))
                    }

                    "offerManyChunks" in {
                        val q = makeQueue[Int](cap)
                        val n = cap * 10
                        fill(q, 0 until n)
                        assert(pollAll(q) == (0 until n))
                    }

                    "offerExactlyChunkSizeBoundary" in {
                        val q = makeQueue[Int](cap)
                        // Exact boundary
                        fill(q, 0 until cap)
                        assert(pollAll(q) == (0 until cap))
                        // One past boundary
                        fill(q, 0 until cap + 1)
                        assert(pollAll(q) == (0 until cap + 1))
                    }

                    "interleavedOfferPollAcrossChunks" in {
                        val q = makeQueue[Int](cap)
                        fill(q, 0 until cap - 1)
                        assert(pollAll(q) == (0 until cap - 1))
                        fill(q, 0 until cap + 1)
                        assert(pollAll(q) == (0 until cap + 1))
                    }

                    "drainAcrossChunkBoundaries" in {
                        val q = makeQueue[Int](cap)
                        val n = cap * 3
                        fill(q, 0 until n)
                        assert(drainAll(q) == (0 until n))
                    }
                end if

                // ---- K. Edge Cases ----

                "freshQueueAllMethodsConsistent" in {
                    val q = makeQueue[Int](cap)
                    assert(q.size() == 0)
                    assert(q.isEmpty())
                    assert(q.poll().isEmpty)
                    assert(q.peek().isEmpty)
                    assert(q.drain(_ => fail("should not drain")) == 0)
                    assert(q.drain(_ => fail("should not drain"), 10) == 0)
                }

                "singleElementLifecycle" in {
                    val q = makeQueue[Int](cap)
                    assert(q.isEmpty())
                    q.offer(42)
                    assert(!q.isEmpty())
                    assert(q.size() == 1)
                    assert(q.peek() == Maybe(42))
                    assert(q.poll() == Maybe(42))
                    assert(q.isEmpty())
                    assert(q.size() == 0)
                }

                "offerSameValueRepeatedly" in {
                    val q   = makeQueue[AnyRef](cap)
                    val obj = new Object
                    val n   = if isBounded then q.capacity else 100
                    for _ <- 0 until n do q.offer(obj)
                    for _ <- 0 until n do
                        val r = q.poll()
                        assert(r.isDefined)
                        assert(r.get eq obj)
                    end for
                }

                "offerPollSingleRepeated" in {
                    val q = makeQueue[Int](cap)
                    for i <- 0 until 10000 do
                        assert(q.offer(i))
                        assert(q.poll() == Maybe(i))
                }

                // ---- L. Usage Pattern Tests ----

                "tracePoolPattern" in {
                    if isBounded then
                        val q     = makeQueue[Array[Int]](cap)
                        val local = new Array[Array[Int]](4)

                        // Borrow pattern: drain to local array
                        for _ <- 0 until 3 do
                            val arr = new Array[Int](8)
                            q.offer(arr)
                        var borrowed = 0
                        q.drain(
                            { arr =>
                                if borrowed < local.length then
                                    local(borrowed) = arr
                                    borrowed += 1
                            },
                            local.length
                        )
                        assert(borrowed == 3)

                        // Release pattern: offer back
                        for i <- 0 until borrowed do
                            q.offer(local(i))
                        discard(assert(q.size() == 3))
                    else succeed("unbounded queues skip the borrow/trace pattern: not applicable for this configuration")
                }

                "channelClosePattern" in {
                    if !isBounded then
                        val q = makeQueue[Int](cap)
                        fill(q, 0 until 100)
                        val closed = Seq.newBuilder[Int]
                        q.drain(v => closed += v)
                        assert(closed.result() == (0 until 100))
                        discard(assert(q.isEmpty()))
                    else succeed("bounded queues skip the drain-on-close pattern: not applicable for this configuration")
                }

            } // capacity
    }         // queueName

    // ---- Concurrency helpers (JVM + Native only, not linked on JS) ----

    // Each worker runs a FIXED op count and self-terminates, so the soak is bounded by operations, not wall time. `check` runs after every
    // thread is joined (Thread.join is happens-before), and the join ceiling is only a hang canary the following `!isAlive` assert catches.
    protected val iterationsPerThread = 100000
    protected val joinTimeoutMs       = 10000L

    protected def concurrentTest(body: CountDownLatch => Seq[Thread])(check: => Unit)(using kyo.test.AssertScope): Unit =
        val start   = new CountDownLatch(1)
        val threads = body(start)
        threads.foreach(_.start())
        start.countDown()
        threads.foreach(_.join(joinTimeoutMs))
        threads.foreach(t => assert(!t.isAlive, s"Thread ${t.getName} did not terminate"))
        check
    end concurrentTest

    protected def thread(name: String, start: CountDownLatch)(body: => Unit): Thread =
        val t = new Thread(
            () =>
                start.await()
                var i = 0
                while i < iterationsPerThread do
                    body
                    i += 1
            ,
            name
        )
        t.setDaemon(true)
        t
    end thread

    // ---- E-J. Concurrent tests (gated .notJs: no-emit on JS) ----

    "concurrent".notJs - {
        s"$queueName" - {
            for cap <- testSizes do
                s"capacity=$cap" - {

                    // ---- E. Concurrent — Memory Visibility ----

                    "happensBefore_poll" in {
                        val q        = makeQueue[Array[Int]](cap)
                        val failure  = new AtomicBoolean(false)
                        val observed = new AtomicLong(0)
                        concurrentTest { start =>
                            val producers = (0 until nProducers).map { pid =>
                                thread(s"producer-$pid", start) {
                                    val arr = new Array[Int](1)
                                    arr(0) = 42
                                    if !q.offer(arr) then Thread.`yield`()
                                }
                            }
                            val consumers = (0 until nConsumers).map { cid =>
                                thread(s"consumer-$cid", start) {
                                    q.poll() match
                                        case Maybe.Present(arr) =>
                                            if arr(0) != 42 then failure.set(true)
                                            discard(observed.incrementAndGet())
                                        case _ => Thread.`yield`()
                                }
                            }
                            producers ++ consumers
                        } {
                            assert(!failure.get(), "Saw uninitialized value through the queue")
                            assert(observed.get() > 0L, "consumers observed no published value")
                        }
                    }

                    "happensBefore_peek" in {
                        val q        = makeQueue[Array[Int]](cap)
                        val failure  = new AtomicBoolean(false)
                        val observed = new AtomicLong(0)
                        concurrentTest { start =>
                            val producers = (0 until nProducers).map { pid =>
                                thread(s"producer-$pid", start) {
                                    val arr = new Array[Int](1)
                                    arr(0) = 42
                                    if !q.offer(arr) then Thread.`yield`()
                                }
                            }
                            val consumers = (0 until nConsumers).map { cid =>
                                thread(s"consumer-$cid", start) {
                                    q.peek() match
                                        case Maybe.Present(arr) =>
                                            if arr(0) != 42 then failure.set(true)
                                            discard(observed.incrementAndGet())
                                        case _ =>
                                    end match
                                    q.poll()
                                    Thread.`yield`()
                                }
                            }
                            producers ++ consumers
                        } {
                            assert(!failure.get(), "Saw uninitialized value through the queue")
                            assert(observed.get() > 0L, "consumers observed no published value")
                        }
                    }

                    // ---- F. Concurrent — Size/State Invariants ----

                    "sizeNeverNegative" in {
                        val q        = makeQueue[Int](cap)
                        val failure  = new AtomicBoolean(false)
                        val observed = new AtomicLong(0)
                        concurrentTest { start =>
                            val producers = (0 until nProducers).map { pid =>
                                thread(s"producer-$pid", start) {
                                    if !q.offer(pid) then Thread.`yield`()
                                }
                            }
                            val consumers = (0 until nConsumers).map { cid =>
                                thread(s"consumer-$cid", start) {
                                    q.poll()
                                    Thread.`yield`()
                                }
                            }
                            val observer = thread("observer", start) {
                                if q.size() < 0 then failure.set(true)
                                discard(observed.incrementAndGet())
                            }
                            producers ++ consumers :+ observer
                        } {
                            assert(!failure.get(), "size observed negative")
                            assert(observed.get() > 0L, "observer never sampled size")
                        }
                    }

                    if isBounded then
                        "sizeNeverExceedsCapacity" in {
                            val q        = makeQueue[Int](cap)
                            val failure  = new AtomicBoolean(false)
                            val observed = new AtomicLong(0)
                            concurrentTest { start =>
                                val producers = (0 until nProducers).map { pid =>
                                    thread(s"producer-$pid", start) {
                                        q.offer(pid)
                                        Thread.`yield`()
                                    }
                                }
                                val consumers = (0 until nConsumers).map { cid =>
                                    thread(s"consumer-$cid", start) {
                                        q.poll()
                                        Thread.`yield`()
                                    }
                                }
                                val observer = thread("observer", start) {
                                    if q.size() > q.capacity then failure.set(true)
                                    discard(observed.incrementAndGet())
                                }
                                producers ++ consumers :+ observer
                            } {
                                assert(!failure.get(), "size exceeded capacity")
                                assert(observed.get() > 0L, "observer never sampled size")
                            }
                        }
                    end if

                    // ---- G. Concurrent — isEmpty/poll consistency ----

                    "pollAfterIsEmpty" in {
                        val q = makeQueue[Int](cap)
                        concurrentTest { start =>
                            val producers = (0 until nProducers).map { pid =>
                                thread(s"producer-$pid", start) {
                                    q.offer(pid)
                                    Thread.`yield`()
                                }
                            }
                            val checker = thread("checker", start) {
                                // Concurrent isEmpty/poll can race, so don't assert strictly during the soak.
                                if !q.isEmpty() then discard(q.poll())
                            }
                            producers :+ checker
                        } {
                            discard(pollAll(q))
                            assert(q.isEmpty(), "queue not empty after full drain")
                            assert(q.size() == 0, s"size ${q.size()} not 0 after full drain")
                        }
                    }

                    // ---- H. Concurrent — Ordering and Data Integrity ----

                    "noDataLoss" in {
                        val q        = makeQueue[Long](cap)
                        val offered  = new AtomicLong(0)
                        val consumed = new AtomicLong(0)
                        concurrentTest { start =>
                            val producers = (0 until nProducers).map { pid =>
                                thread(s"producer-$pid", start) {
                                    // Count only SUCCESSFUL offers so conservation holds for a bounded queue.
                                    if q.offer(1L) then discard(offered.incrementAndGet())
                                    else Thread.`yield`()
                                }
                            }
                            val consumers = (0 until nConsumers).map { cid =>
                                thread(s"consumer-$cid", start) {
                                    q.poll() match
                                        case Maybe.Present(_) => discard(consumed.incrementAndGet())
                                        case _                => Thread.`yield`()
                                }
                            }
                            producers ++ consumers
                        } {
                            val remaining = pollAll(q).size
                            assert(
                                consumed.get() + remaining.toLong == offered.get(),
                                s"lost data: consumed=${consumed.get()} + remaining=$remaining != offered=${offered.get()}"
                            )
                        }
                    }

                    "perProducerFIFO" in {
                        if nProducers > 1 && nConsumers == 1 then
                            val q        = makeQueue[Long](cap)
                            val failure  = new AtomicBoolean(false)
                            val observed = new AtomicLong(0)
                            val counters = Array.fill(nProducers)(new AtomicLong(0))
                            val lastSeen = Array.fill(nProducers)(0L)
                            concurrentTest { start =>
                                val producers = (0 until nProducers).map { pid =>
                                    thread(s"producer-$pid", start) {
                                        val v = pid.toLong * 1000000 + counters(pid).incrementAndGet()
                                        if !q.offer(v) then Thread.`yield`()
                                    }
                                }
                                val consumer = thread("consumer", start) {
                                    q.poll() match
                                        case Maybe.Present(v) =>
                                            val pid = (v / 1000000).toInt
                                            val seq = v % 1000000
                                            if pid >= 0 && pid < nProducers then
                                                if seq <= lastSeen(pid) then failure.set(true)
                                                lastSeen(pid) = seq
                                            discard(observed.incrementAndGet())
                                        case _ => Thread.`yield`()
                                }
                                producers :+ consumer
                            } {
                                assert(!failure.get(), "per-producer FIFO order violated")
                                assert(observed.get() > 0L, "consumer observed no values")
                            }
                        else
                            succeed("per-producer FIFO requires multi-producer+single-consumer: not applicable for this configuration")
                    }

                    // ---- I. Concurrent — Contention ----

                    "highContention" in {
                        val q = makeQueue[Int](if isBounded then Math.min(cap, 8) else cap)
                        concurrentTest { start =>
                            val producers = (0 until nProducers).map { pid =>
                                thread(s"producer-$pid", start) {
                                    if !q.offer(pid) then Thread.`yield`()
                                }
                            }
                            val consumers = (0 until nConsumers).map { cid =>
                                thread(s"consumer-$cid", start) {
                                    q.poll()
                                    Thread.`yield`()
                                }
                            }
                            producers ++ consumers
                        } {
                            discard(pollAll(q))
                            assert(q.isEmpty(), "queue not empty after full drain")
                            assert(q.size() == 0, s"size ${q.size()} not 0 after full drain")
                        }
                    }

                    "singleElementPingPong" in {
                        if isBounded then
                            val q = makeQueue[Int](4)
                            concurrentTest { start =>
                                val producer = thread("producer", start) {
                                    if !q.offer(1) then Thread.`yield`()
                                }
                                val consumer = thread("consumer", start) {
                                    q.poll()
                                    Thread.`yield`()
                                }
                                Seq(producer, consumer)
                            } {
                                discard(pollAll(q))
                                assert(q.isEmpty(), "queue not empty after full drain")
                                assert(q.size() == 0, s"size ${q.size()} not 0 after full drain")
                            }
                        else succeed("single-element ping-pong requires a bounded queue: not applicable for unbounded")
                    }

                    // ---- J. Concurrent — Drain ----

                    "concurrentDrainNoLoss" in {
                        val q       = makeQueue[Int](cap)
                        val offered = new AtomicLong(0)
                        val drained = new AtomicLong(0)
                        concurrentTest { start =>
                            val producers = (0 until nProducers).map { pid =>
                                thread(s"producer-$pid", start) {
                                    if q.offer(pid) then discard(offered.incrementAndGet())
                                    else Thread.`yield`()
                                }
                            }
                            val consumer = thread("drainer", start) {
                                discard(drained.addAndGet(q.drain(_ => (), Math.max(1, cap / 4)).toLong))
                            }
                            producers :+ consumer
                        } {
                            val remaining = pollAll(q).size
                            assert(
                                drained.get() + remaining.toLong == offered.get(),
                                s"lost data: drained=${drained.get()} + remaining=$remaining != offered=${offered.get()}"
                            )
                        }
                    }

                } // capacity
        }         // queueName
    }             // concurrent group

end UnsafeQueueBaseTest
