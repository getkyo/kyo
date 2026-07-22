package kyo.scheduler

import java.lang.invoke.VarHandle
import java.util.concurrent.atomic.AtomicBoolean
import scala.annotation.tailrec

/** Worker run queue: a zero-allocation 4-ary array-backed min-heap of `Task`, keyed on a snapshot of `Task.runtime()` taken at insertion.
  *
  * The heap root (index 0) is the lowest-key task, which is the highest scheduling priority: lower accumulated runtime sorts ahead, so a
  * task whose runtime was reset to the minimum reaches the head first. `poll` removes the root; `add`/`offer` sift a new element up;
  * `addAndPoll` swaps the root for a new element; `stealingBy` batch-transfers the head plus about half of the remainder to an empty target
  * queue; `drain` snapshots and clears; `rebuild` re-snapshots every live key and re-establishes the heap invariant (Floyd, O(n)).
  *
  * The queue OWNS its sort key rather than reading it live off the element. A task's runtime can change while it sits in the heap, from a
  * thread that is not the one holding this lock, and a key that moves under a heap nobody re-sifts leaves the invariant broken: `poll` then
  * returns an element that is not the minimum. Snapshotting at insertion makes every comparison a pure function of queue-owned state read
  * under the lock, so no writer can perturb the ordering. The cost of that guarantee is that a runtime change becomes visible to the ordering
  * only at the next `rebuild`, which is exactly the path the interrupt priority boost already takes.
  *
  * Thread safety is provided by an explicit spin-lock (the inherited AtomicBoolean), held for the minimal critical section. Work stealing
  * fails fast when either lock is unavailable. The structure is specialized on `Task` so the priority comparison inlines to two loads from a
  * contiguous int array, with no Ordering indirection and no element casts.
  *
  * The scheduler uses one queue per worker as the foundation for task distribution, leveraging the work-stealing and draining capabilities
  * for load balancing and worker management.
  */
final private class WorkerQueue extends AtomicBoolean {

    // 4-ary (d=4) implicit min-heap. For a node at index i (0-based):
    //   parent      = (i - 1) >>> 2
    //   first child = (i << 2) + 1 = 4i + 1; children span 4i+1 .. 4i+4.
    // d=4 gives shallower trees (log_4 n levels) and better cache locality (4 contiguous
    // children per probe) than a binary heap, at the cost of a 4-way min scan per level.
    private var arr: Array[Task] = new Array[Task](queueCapacity())
    // The sort key, snapshotted from `Task.runtime()` when the element is inserted and owned by the queue thereafter.
    // The key must NOT be read live off the element: a task can be inside this heap while another thread changes its
    // runtime (Worker.runTask bills the task it just ran AFTER `run` returned, by which point a mid-run self-schedule
    // may already have enqueued it; Task.doPreempt and IOTask.onComplete likewise reset the runtime of queued tasks).
    // A key that moves under a heap that is not re-sifted leaves the invariant broken, so the queue keeps its own copy
    // and every comparison is a pure function of queue-owned state read under the lock. `rebuild` re-snapshots.
    private var keys: Array[Int] = new Array[Int](queueCapacity())
    // Mutated under the spin-lock but read lock-free by size()/isEmpty() (e.g. Worker.checkStalling) from other
    // threads. @volatile so those cross-thread reads see fresh writes: with a plain var a stale 0 can persist on
    // weak memory models (observed on linux), making a worker pinned on a CPU-bound task whose queue has pending
    // work look empty, so checkStalling skips its doPreempt.
    @volatile private var count: Int = 0

    @inline private def lessThan(i: Int, j: Int): Boolean =
        keys(i) < keys(j)

    /** Tests if queue contains no elements. Provides a non-blocking snapshot of empty state.
      */
    def isEmpty() =
        size() == 0

    /** Returns number of elements in queue. Non-blocking operation with memory fence for cross-thread visibility.
      */
    def size(): Int = {
        VarHandle.acquireFence()
        count
    }

    /** Adds element to queue in priority order. Acquires lock for thread-safe insertion.
      */
    def add(value: Task): Unit = {
        lock()
        try addLocked(value)
        finally unlock()
    }

    /** Attempts non-blocking element addition.
      */
    def offer(value: Task): Boolean =
        tryLock() && {
            try {
                addLocked(value)
                true
            } finally
                unlock()
        }

    /** Retrieves and removes highest priority element.
      */
    def poll(): Task =
        if (isEmpty())
            null
        else {
            lock()
            try
                if (count == 0) null
                else pollLocked()
            finally
                unlock()
        }

    /** Atomically exchanges input element for highest priority element.
      *
      * Returns input if queue is empty, otherwise adds input and returns previous highest priority element as single atomic operation.
      */
    def addAndPoll(value: Task): Task =
        if (isEmpty())
            value
        else {
            lock()
            try
                if (count == 0) value
                else {
                    val root = arr(0)
                    arr(0) = value
                    keys(0) = value.runtime()
                    siftDownLocked(0)
                    root
                }
            finally
                unlock()
        }

    /** Atomically transfers elements between queues.
      *
      * Moves highest priority element plus approximately half of remaining elements to target queue. Operation only succeeds if target is
      * empty and both locks can be acquired without blocking. Maintains priority ordering in both queues.
      *
      * Used for work stealing in scheduling contexts.
      *
      * @param to
      *   recipient queue for stolen elements
      * @return
      *   highest priority element that was transferred, or null if transfer failed
      */
    def stealingBy(to: WorkerQueue): Task =
        if (!isEmpty() && tryLock()) {
            try
                if (!isEmpty() && to.isEmpty() && to.tryLock()) {
                    try {
                        val t = pollLocked()
                        // pollLocked already removed the head and decremented count, so size()
                        // is the number of elements remaining after the head. Transfer
                        // floor(s/2) of those to `to` (head + that many leave this queue).
                        val s = size()
                        val i = s - Math.ceil(s.toDouble / 2).intValue()
                        @tailrec def transfer(moved: Int): Unit =
                            if (moved < i) {
                                to.addLocked(pollLocked())
                                transfer(moved + 1)
                            }
                        transfer(0)
                        t
                    } finally
                        to.unlock()
                } else null
            finally
                unlock()
        } else null

    /** Atomically removes and processes all elements.
      *
      * Locks queue, swaps in a fresh backing array, then applies function to each removed element after lock release. Used for queue shutdown
      * and rebalancing. The replacement array is allocated outside the lock so the critical section is only the reference swap and the count
      * reset; `f` runs after unlock because it re-enters the scheduler and must not run under the spin-lock.
      */
    def drain(f: Task => Unit): Unit =
        if (!isEmpty()) {
            val fresh     = new Array[Task](queueCapacity())
            val freshKeys = new Array[Int](queueCapacity())
            lock()
            val snapshot = arr
            val n        = count
            arr = fresh
            keys = freshKeys
            count = 0
            unlock()
            @tailrec def loop(idx: Int): Unit =
                if (idx < n) {
                    f(snapshot(idx))
                    snapshot(idx) = null
                    loop(idx + 1)
                }
            loop(0)
        }

    /** Re-establishes the heap invariant from the current keys in place.
      *
      * Used after an element's runtime key changed without a heap operation (an interrupted task whose runtime was reset while it sat in the
      * queue): the element holds its old array position with a new key, so the invariant must be rebuilt over all positions. Floyd bottom-up
      * build, O(n), under the queue's existing lock so it serializes with the other operations. Empty and single-element queues are a no-op.
      */
    def rebuild(): Unit =
        if (!isEmpty()) {
            lock()
            try
                if (count > 1) {
                    // Re-snapshot first: rebuild exists precisely because an element's live runtime changed under the
                    // heap, so the stored keys are the stale ones. Heapifying without refreshing them would re-sort by
                    // the very values that are out of date and drop the interrupt priority boost on the floor.
                    @tailrec def resnapshot(i: Int): Unit =
                        if (i < count) {
                            keys(i) = arr(i).runtime()
                            resnapshot(i + 1)
                        }
                    resnapshot(0)
                    @tailrec def loop(i: Int): Unit =
                        if (i >= 0) {
                            siftDownLocked(i)
                            loop(i - 1)
                        }
                    loop((count - 2) >>> 2)
                }
            finally
                unlock()
        }

    /** Inserts an element, assuming the lock is already held. */
    private def addLocked(value: Task): Unit = {
        ensureCapacity(count + 1)
        arr(count) = value
        keys(count) = value.runtime()
        siftUp(count)
        count += 1
    }

    /** Removes and returns the root, assuming the lock is already held and the queue is non-empty. */
    private def pollLocked(): Task = {
        val root = arr(0)
        count -= 1
        arr(0) = arr(count)
        keys(0) = keys(count)
        arr(count) = null
        siftDownLocked(0)
        root
    }

    private def ensureCapacity(n: Int): Unit =
        if (n > arr.length) {
            arr = java.util.Arrays.copyOf(arr, arr.length << 1)
            keys = java.util.Arrays.copyOf(keys, keys.length << 1)
        }

    @tailrec private def siftUp(i: Int): Unit =
        if (i > 0) {
            val p = (i - 1) >>> 2
            if (lessThan(i, p)) {
                swap(i, p)
                siftUp(p)
            }
        }

    @tailrec private def siftDownLocked(i: Int): Unit = {
        val first = (i << 2) + 1
        if (first < count) {
            val last = Math.min(first + 3, count - 1)
            @tailrec def minChild(c: Int, best: Int): Int =
                if (c > last) best
                else minChild(c + 1, if (lessThan(c, best)) c else best)
            val min = minChild(first + 1, first)
            if (lessThan(min, i)) {
                swap(i, min)
                siftDownLocked(min)
            }
        }
    }

    private def swap(i: Int, j: Int): Unit = {
        val tmp = arr(i)
        arr(i) = arr(j)
        arr(j) = tmp
        val tmpKey = keys(i)
        keys(i) = keys(j)
        keys(j) = tmpKey
    }

    /** Acquires queue lock using busy-wait spin loop.
      *
      * Uses busy waiting rather than parking/blocking since:
      *   - Effective concurrency is bounded by available cores
      *   - Lock hold times are very short (optimized queue operations)
      *   - Most contended operations (stealing) fail fast if lock unavailable
      *
      * The scheduler's worker model ensures the number of threads competing for any given queue is limited, making spinning more efficient
      * than thread parking overhead.
      */
    private def lock(): Unit =
        while (!compareAndSet(false, true)) {}

    private def tryLock(): Boolean =
        compareAndSet(false, true)

    private def unlock(): Unit =
        set(false)
}
