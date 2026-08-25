package kyo.net.internal.util

import java.util.concurrent.atomic.AtomicReference

/** An unbounded multi-producer single-consumer FIFO of primitive `long`, allocation-free in steady state.
  *
  * Built for the [[kyo.net.internal.posix.PollerIoDriver]] interest-change FIFO: many fibers submit packed `long` commands
  * ([[offer]], multi-producer) and the single change-worker fiber drains them ([[poll]], single-consumer). A
  * `ConcurrentLinkedQueue[java.lang.Long]` would box each command on every enqueue (a `java.lang.Long` allocation per offer, which a JFR
  * alloc profile of the poller pinpointed as the dominant boxing source on the hot path); this queue stores the raw `long` in a node and
  * recycles drained nodes through a free list, so after warm-up neither an enqueue nor a dequeue allocates.
  *
  * Algorithm: Vyukov's intrusive MPSC linked queue. Producers publish a node by a single atomic swap of the tail (`getAndSet`) then link the
  * previous tail to it; the consumer walks `next` pointers from the head. A one-node "stub" separates head from tail so the empty state needs
  * no special casing. Dequeued nodes are pushed onto a Treiber free-list (`freeList`) and reused by the next `offer`, bounding steady-state
  * allocation to zero. The free-list pop is itself lock-free (a CAS loop), so a producer that loses the pop race simply allocates a fresh node
  * that later returns to the pool.
  *
  * Memory ordering: `getAndSet` on `tail` and the `next` `AtomicReference` writes/reads carry the JMM happens-before edges the producers and
  * the consumer need; a value stored before [[offer]] is visible to the consumer that dequeues it (the same publication guarantee a
  * `ConcurrentLinkedQueue.offer` provides the poller's `pendingReadPromise` store).
  *
  * Single-consumer contract: [[poll]] and [[peekNonEmpty]] must be called from one thread/fiber at a time (the driver's single change
  * worker). [[offer]] is safe from any number of producers.
  */
final private[kyo] class MpscLongQueue:
    import MpscLongQueue.*

    // The stub node makes head/tail never null and removes the empty-queue special case. Producers swap `tail`; the consumer reads from `head`.
    private val stub                 = new Node(0L)
    private val tail                 = new AtomicReference[Node](stub)
    @volatile private var head: Node = stub
    private val freeList             = new AtomicReference[Node](null)

    /** Append `value` to the tail. Safe from any number of producer threads/fibers. Allocation-free once the free list is warm. */
    def offer(value: Long): Unit =
        val node = acquireNode(value)
        // Single linearization point: atomically make `node` the new tail and read the previous tail. The window between this swap and the
        // `prev.next` link below is where a concurrent consumer can briefly observe the list as "not yet linked" (prev.next == null) even
        // though a successor exists; poll() handles that by reporting empty for that instant, exactly as Vyukov's algorithm specifies.
        val prev = tail.getAndSet(node)
        prev.next.set(node)
    end offer

    /** Remove and return the head value, or [[MpscLongQueue.Empty]] if the queue is observably empty. Single-consumer only.
      *
      * Returns [[Empty]] both when the queue is genuinely empty and during the transient producer window described in [[offer]]; the caller
      * treats `Empty` as "nothing to do right now" and the next poll observes the linked successor. Since the packed commands the poller
      * enqueues are always `>= 0` (an opcode in bits 34+ ORed with a non-negative fd), [[Empty]] (`Long.MinValue`) can never collide with a
      * real value.
      */
    def poll(): Long =
        val h    = head
        val next = h.next.get()
        if next eq null then Empty
        else
            val value = next.value
            head = next    // advance: `next` becomes the new stub; its slot is consumed.
            recycleNode(h) // the old head node is free for reuse.
            value
        end if
    end poll

    /** True if a subsequent [[poll]] would observe a value (a successor is linked). Single-consumer only. Used for the worker's
      * stand-down re-check, mirroring `ConcurrentLinkedQueue.peek() != null`.
      */
    def peekNonEmpty(): Boolean =
        head.next.get() ne null

    private def acquireNode(value: Long): Node =
        @scala.annotation.tailrec
        def loop(): Node =
            val free = freeList.get()
            if free eq null then new Node(value)
            else if freeList.compareAndSet(free, free.freeNext) then
                free.freeNext = null
                free.next.set(null)
                free.value = value
                free
            else loop()
            end if
        end loop
        loop()
    end acquireNode

    private def recycleNode(node: Node): Unit =
        // The drained node is reset and pushed onto the Treiber free list. `next`/`value` are re-initialized in acquireNode before reuse, so
        // a stale `next` here is harmless. Bounding the free list is unnecessary: it can hold at most the queue's peak depth.
        @scala.annotation.tailrec
        def loop(): Unit =
            val free = freeList.get()
            node.freeNext = free
            if !freeList.compareAndSet(free, node) then loop()
        end loop
        loop()
    end recycleNode

end MpscLongQueue

private[kyo] object MpscLongQueue:

    /** Sentinel returned by [[MpscLongQueue.poll]] when the queue is observably empty. `Long.MinValue` is never a valid packed poller command
      * (those are always `>= 0`), so it can never collide with a real dequeued value.
      */
    final val Empty: Long = Long.MinValue

    /** Intrusive queue node holding one `long`. `next` links the FIFO order (read by the consumer, written by producers); `freeNext` links the
      * recycle free list (a separate chain so a node can sit on the free list without disturbing FIFO `next`).
      */
    final private class Node(@volatile var value: Long):
        val next: AtomicReference[Node] = new AtomicReference[Node](null)
        @volatile var freeNext: Node    = null
    end Node

end MpscLongQueue
