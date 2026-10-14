package kyo.net.internal

import java.util.concurrent.ConcurrentLinkedQueue
import kyo.*

/** The owner of one NIO handle. Its JDK `SSLEngine` and buffers, its socket writes, and every close decision run only while it is held,
  * which is what [[NioIoDriver.runOwned]] means on NIO.
  *
  * NIO has no carrier that owns a handle the way a poll loop owns a posix fd: reads dispatch on the selector carrier, writes run on the
  * writer's carrier, and a close is decided on whichever carrier observed it. The gate stands in for that carrier. Every holder runs one
  * bounded, CPU-only section: an engine operation, a socket write, a read's pre-check, or the owned ops queued for the handle.
  *
  * Two ways in:
  *   - A writer, a close and the handshake spin ([[locked]]). The thread that already holds the gate re-enters without spinning: an owned op
  *     fails parked promises, whose callbacks run inline and can write on the same thread, and a spin there would never end.
  *   - The selector carrier and [[NioIoDriver.runOwned]] never spin ([[tryLocked]], [[acquireOrDefer]]). When the gate is held they leave
  *     their work to the holder's release, which hands it to the selector carrier through the hook the driver [[bind]]s. A deferred op
  *     costs one selector cycle per release, never a selector cycle per retry.
  *
  * The deferral is lossless by ordering: a deferrer sets its flag before its acquire attempt, and a releaser frees the gate before it reads
  * the flag. Either the releaser sees the flag, or the flag was set after the gate was free and the acquire that follows it succeeds. An
  * acquire that finds an earlier deferral still flagged keeps the flag for its own release, since the releaser that flagged it may read it
  * only after this acquire.
  */
final private[kyo] class NioGate:
    // Unsafe: the gate is a bridging primitive under every NIO engine, write and close section; its atomics are built and touched here
    // with no ambient AllowUnsafe, as NioHandle's are.
    import AllowUnsafe.embrace.danger

    private val held     = AtomicBoolean.Unsafe.init(false)
    private val deferred = AtomicBoolean.Unsafe.init(false)
    // Written only by the thread that holds the gate, after it takes it and before it frees it, so a thread reads itself here only while
    // it holds the gate. The JDK thread is the identity because a carrier runs one section at a time to completion.
    @volatile private var holder: Thread = null
    // Until a driver binds the handle, the releaser runs what was queued itself: there is no selector carrier to hand it to.
    @volatile private var handBack: () => Unit = () => discard(lockedOrDefer(runQueued()))

    // Concurrent-collection audit: kyo has no concurrent-queue type, so a raw ConcurrentLinkedQueue is the documented no-equivalent
    // exception, as for NioIoDriver's deferred queues. Producers are any carrier calling runOwned; the consumer is whichever carrier holds
    // the gate, one at a time.
    private val owned = new ConcurrentLinkedQueue[() => Unit]()

    /** Install the driver's hand-back: queue this handle's deferred work for the selector carrier and wake it. */
    private[internal] def bind(onDeferred: () => Unit): Unit = handBack = onDeferred

    /** Run `op` holding the gate, spinning for it unless this thread already holds it. */
    inline def locked[A](inline op: NioGate.Held ?=> A): A =
        val took = spinAcquire()
        try op(using NioGate.evidence)
        finally if took then release()
    end locked

    /** Run `op` holding the gate if it is free; `Absent`, with nothing run, when another section holds it. */
    inline def tryLocked[A](inline op: NioGate.Held ?=> A): Maybe[A] =
        if !tryAcquire() then Absent
        else
            try Present(op(using NioGate.evidence))
            finally release()

    /** Run `op` holding the gate if it is free, or leave the work to the holder's release; `Absent` when it was left. */
    inline def lockedOrDefer[A](inline op: NioGate.Held ?=> A): Maybe[A] =
        if !acquireOrDefer() then Absent
        else
            try Present(op(using NioGate.evidence))
            finally release()

    /** Queue an op for the next holder that runs the queue. */
    def enqueue(op: () => Unit): Unit = discard(owned.offer(op))

    /** Run every queued op, in order, including those queued while it runs. A throwing op is contained, so the ops behind it still run
      * and the gate is still released.
      */
    def runQueued()(using NioGate.Held): Unit =
        var op = owned.poll()
        while op ne null do
            try op()
            catch
                case t: Throwable =>
                    given Frame = Frame.internal
                    Log.live.unsafe.error("NioGate owned op threw; the queue continues", t)
            end try
            op = owned.poll()
        end while
    end runQueued

    /** Whether ops are queued. */
    def hasQueued: Boolean = !owned.isEmpty

    /** Whether some section holds the gate now. Inspection only. */
    private[internal] def isHeld: Boolean = held.get()

    /** Whether the current thread holds the gate. */
    def heldByCurrentThread: Boolean = holder eq Thread.currentThread()

    private def tryAcquire(): Boolean =
        if held.compareAndSet(false, true) then
            holder = Thread.currentThread()
            true
        else false

    // Bounded: no holder waits on anything while it holds the gate.
    private def spinAcquire(): Boolean =
        if heldByCurrentThread then false
        else
            while !tryAcquire() do Thread.onSpinWait()
            true

    private def acquireOrDefer(): Boolean =
        val earlier = deferred.getAndSet(true)
        if tryAcquire() then
            if !earlier then deferred.set(false)
            true
        else false
        end if
    end acquireOrDefer

    private def release(): Unit =
        holder = null
        held.set(false)
        if deferred.getAndSet(false) then handBack()
    end release

end NioGate

private[kyo] object NioGate:

    /** Evidence that the gate of the handle whose state is touched is held: the operations of [[NioTlsState]] require it, so no call site
      * can reach the engine or its buffers without going through [[NioGate.locked]], [[NioGate.tryLocked]] or [[NioGate.lockedOrDefer]].
      */
    opaque type Held = Unit

    private val evidence: Held = ()
end NioGate
