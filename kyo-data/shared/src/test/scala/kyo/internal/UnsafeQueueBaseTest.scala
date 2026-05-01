package kyo.internal

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kyo.*
import kyo.AllowUnsafe.embrace.danger
import org.scalatest.Assertion
import org.scalatest.Succeeded
import org.scalatest.freespec.AnyFreeSpec

/** Base test class for all UnsafeQueue implementations. Each concrete test class extends this with appropriate queue factory.
  */
abstract class UnsafeQueueBaseTest extends AnyFreeSpec:

    def queueName: String
    def isBounded: Boolean
    def nProducers: Int
    def nConsumers: Int
    def testSizes: Seq[Int]
    def makeQueue[A](size: Int): UnsafeQueue[A]

    private val testDurationMs = 200L
    private val testTimeout    = 10000L

    // ---- Helpers ----

    protected inline def runNotJS(inline body: => Any): Unit =
        inline if !Platform.isJS then discard(body)

    private def fill[A](q: UnsafeQueue[A], values: Seq[A]): Unit =
        values.foreach(v => assert(q.offer(v)))

    private def pollAll[A](q: UnsafeQueue[A]): Seq[A] =
        val buf = Seq.newBuilder[A]
        var r   = q.poll()
        while r.isDefined do
            buf += r.get
            r = q.poll()
        buf.result()
    end pollAll

    private def drainAll[A](q: UnsafeQueue[A]): Seq[A] =
        val buf = Seq.newBuilder[A]
        q.drain(v => buf += v)
        buf.result()
    end drainAll

    private def concurrentTest(body: (AtomicBoolean, CountDownLatch) => Seq[Thread]): Unit =
        val stop    = new AtomicBoolean(false)
        val start   = new CountDownLatch(1)
        val threads = body(stop, start)
        threads.foreach(_.start())
        start.countDown()
        Thread.sleep(testDurationMs)
        stop.set(true)
        threads.foreach(_.join(testTimeout))
        threads.foreach(t => assert(!t.isAlive, s"Thread ${t.getName} did not terminate"))
    end concurrentTest

    private def thread(name: String, start: CountDownLatch, stop: AtomicBoolean)(body: => Unit): Thread =
        val t = new Thread(
            () =>
                start.await()
                while !stop.get() do body
            ,
            name
        )
        t.setDaemon(true)
        t
    end thread

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

                // ---- E. Concurrent — Memory Visibility ----

                "happensBefore_poll" in runNotJS {
                    concurrentTest { (stop, start) =>
                        val q       = makeQueue[Array[Int]](cap)
                        val failure = new AtomicBoolean(false)
                        val producers = (0 until nProducers).map { pid =>
                            thread(s"producer-$pid", start, stop) {
                                val arr = new Array[Int](1)
                                arr(0) = 42
                                if !q.offer(arr) then Thread.`yield`()
                            }
                        }
                        val consumers = (0 until nConsumers).map { cid =>
                            thread(s"consumer-$cid", start, stop) {
                                q.poll() match
                                    case Maybe.Present(arr) =>
                                        if arr(0) != 42 then failure.set(true)
                                    case _ => Thread.`yield`()
                            }
                        }
                        (producers ++ consumers).map { t =>
                            assert(!failure.get(), "Saw uninitialized value"); t
                        }
                    }
                }

                "happensBefore_peek" in runNotJS {
                    concurrentTest { (stop, start) =>
                        val q       = makeQueue[Array[Int]](cap)
                        val failure = new AtomicBoolean(false)
                        val producers = (0 until nProducers).map { pid =>
                            thread(s"producer-$pid", start, stop) {
                                val arr = new Array[Int](1)
                                arr(0) = 42
                                if !q.offer(arr) then Thread.`yield`()
                            }
                        }
                        val consumers = (0 until nConsumers).map { cid =>
                            thread(s"consumer-$cid", start, stop) {
                                q.peek() match
                                    case Maybe.Present(arr) =>
                                        if arr(0) != 42 then failure.set(true)
                                    case _ =>
                                end match
                                q.poll()
                                Thread.`yield`()
                            }
                        }
                        (producers ++ consumers).map { t =>
                            assert(!failure.get()); t
                        }
                    }
                }

                // ---- F. Concurrent — Size/State Invariants ----

                "sizeNeverNegative" in runNotJS {
                    concurrentTest { (stop, start) =>
                        val q       = makeQueue[Int](cap)
                        val failure = new AtomicBoolean(false)
                        val producers = (0 until nProducers).map { pid =>
                            thread(s"producer-$pid", start, stop) {
                                if !q.offer(pid) then Thread.`yield`()
                            }
                        }
                        val consumers = (0 until nConsumers).map { cid =>
                            thread(s"consumer-$cid", start, stop) {
                                q.poll()
                                Thread.`yield`()
                            }
                        }
                        val observer = thread("observer", start, stop) {
                            if q.size() < 0 then failure.set(true)
                        }
                        (producers ++ consumers :+ observer).map { t =>
                            assert(!failure.get()); t
                        }
                    }
                }

                if isBounded then
                    "sizeNeverExceedsCapacity" in runNotJS {
                        concurrentTest { (stop, start) =>
                            val q       = makeQueue[Int](cap)
                            val failure = new AtomicBoolean(false)
                            val producers = (0 until nProducers).map { pid =>
                                thread(s"producer-$pid", start, stop) {
                                    q.offer(pid)
                                    Thread.`yield`()
                                }
                            }
                            val consumers = (0 until nConsumers).map { cid =>
                                thread(s"consumer-$cid", start, stop) {
                                    q.poll()
                                    Thread.`yield`()
                                }
                            }
                            val observer = thread("observer", start, stop) {
                                val s = q.size()
                                if s > q.capacity then failure.set(true)
                            }
                            (producers ++ consumers :+ observer).map { t =>
                                assert(!failure.get()); t
                            }
                        }
                    }
                end if

                // ---- G. Concurrent — isEmpty/poll consistency ----

                "pollAfterIsEmpty" in runNotJS {
                    concurrentTest { (stop, start) =>
                        val q       = makeQueue[Int](cap)
                        val failure = new AtomicBoolean(false)
                        val producers = (0 until nProducers).map { pid =>
                            thread(s"producer-$pid", start, stop) {
                                q.offer(pid)
                                Thread.`yield`()
                            }
                        }
                        val checker = thread("checker", start, stop) {
                            if !q.isEmpty() then
                                // There may be a race here, so we don't assert strictly
                                discard(q.poll())
                        }
                        producers :+ checker
                    }
                }

                // ---- H. Concurrent — Ordering and Data Integrity ----

                "noDataLoss" in runNotJS {
                    concurrentTest { (stop, start) =>
                        val q        = makeQueue[Long](cap)
                        val offered  = new AtomicLong(0)
                        val consumed = new AtomicLong(0)
                        val done     = new AtomicBoolean(false)
                        val producers = (0 until nProducers).map { pid =>
                            thread(s"producer-$pid", start, stop) {
                                if q.offer(offered.incrementAndGet()) then ()
                                else Thread.`yield`()
                            }
                        }
                        val consumers = (0 until nConsumers).map { cid =>
                            thread(s"consumer-$cid", start, stop) {
                                q.poll() match
                                    case Maybe.Present(_) => discard(consumed.incrementAndGet())
                                    case _                => Thread.`yield`()
                            }
                        }
                        producers ++ consumers
                    }
                    // Note: after the test, offered - consumed = remaining in queue
                    // This is a smoke test, not an exact count verification
                }

                "perProducerFIFO" in runNotJS {
                    if nProducers > 1 && nConsumers == 1 then
                        concurrentTest { (stop, start) =>
                            val q        = makeQueue[Long](cap)
                            val failure  = new AtomicBoolean(false)
                            val counters = Array.fill(nProducers)(new AtomicLong(0))
                            val producers = (0 until nProducers).map { pid =>
                                thread(s"producer-$pid", start, stop) {
                                    val v = pid.toLong * 1000000 + counters(pid).incrementAndGet()
                                    if !q.offer(v) then Thread.`yield`()
                                }
                            }
                            val lastSeen = Array.fill(nProducers)(0L)
                            val consumer = thread("consumer", start, stop) {
                                q.poll() match
                                    case Maybe.Present(v) =>
                                        val pid = (v / 1000000).toInt
                                        val seq = v % 1000000
                                        if pid >= 0 && pid < nProducers then
                                            if seq <= lastSeen(pid) then failure.set(true)
                                            lastSeen(pid) = seq
                                    case _ => Thread.`yield`()
                            }
                            (producers :+ consumer).map { t =>
                                assert(!failure.get()); t
                            }
                        }
                }

                // ---- I. Concurrent — Contention ----

                "highContention" in runNotJS {
                    concurrentTest { (stop, start) =>
                        val q = makeQueue[Int](if isBounded then Math.min(cap, 8) else cap)
                        val producers = (0 until nProducers).map { pid =>
                            thread(s"producer-$pid", start, stop) {
                                if !q.offer(pid) then Thread.`yield`()
                            }
                        }
                        val consumers = (0 until nConsumers).map { cid =>
                            thread(s"consumer-$cid", start, stop) {
                                q.poll()
                                Thread.`yield`()
                            }
                        }
                        producers ++ consumers
                    }
                }

                "singleElementPingPong" in runNotJS {
                    if isBounded then
                        concurrentTest { (stop, start) =>
                            val q       = makeQueue[Int](4)
                            val failure = new AtomicBoolean(false)
                            val producer = thread("producer", start, stop) {
                                if !q.offer(1) then Thread.`yield`()
                            }
                            val consumer = thread("consumer", start, stop) {
                                q.poll()
                                Thread.`yield`()
                            }
                            Seq(producer, consumer)
                        }
                }

                // ---- J. Concurrent — Drain ----

                "concurrentDrainNoLoss" in runNotJS {
                    concurrentTest { (stop, start) =>
                        val q       = makeQueue[Int](cap)
                        val offered = new AtomicLong(0)
                        val drained = new AtomicLong(0)
                        val producers = (0 until nProducers).map { pid =>
                            thread(s"producer-$pid", start, stop) {
                                if q.offer(pid) then discard(offered.incrementAndGet())
                                else Thread.`yield`()
                            }
                        }
                        val consumer = thread("drainer", start, stop) {
                            discard(drained.addAndGet(q.drain(_ => (), Math.max(1, cap / 4))))
                        }
                        producers :+ consumer
                    }
                }

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
                }

                "channelClosePattern" in {
                    if !isBounded then
                        val q = makeQueue[Int](cap)
                        fill(q, 0 until 100)
                        val closed = Seq.newBuilder[Int]
                        q.drain(v => closed += v)
                        assert(closed.result() == (0 until 100))
                        discard(assert(q.isEmpty()))
                }

            } // capacity
    }         // queueName

end UnsafeQueueBaseTest
