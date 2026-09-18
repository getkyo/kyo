package kyo.ffi.internal

import java.util.concurrent.ConcurrentHashMap

/** A value each thread owns, found by the thread's identity.
  *
  * A `ConcurrentHashMap` keyed by the owning `Thread` holds every live value, and a `ThreadLocal` only caches that lookup, validated by
  * owner identity on every read: a cache entry that is missing or belongs to another thread costs one map lookup and nothing else.
  * `java.lang.ThreadLocal` alone is not enough on Scala Native, where an entry can vanish between two reads on the same thread (observed
  * on linux under the kyo-ffi-it suite), and per-thread state that must survive an FFI call, a transient callback frame or the borrow
  * owner a checked borrow was issued under, would silently start over.
  *
  * The value is created by `init` on the thread's first read. `set` replaces this thread's value. Values of threads that have died are
  * swept when a new thread registers, so a thread that never registers costs nothing and a dead one is held only until the next
  * registration.
  */
final private[ffi] class ThreadOwned[A <: AnyRef](init: Thread => A):

    final private class Slot(val owner: Thread, var value: A)

    private val slots = new ConcurrentHashMap[Thread, Slot]()

    private val cache: ThreadLocal[Slot] =
        new ThreadLocal[Slot]:
            override def initialValue(): Slot = resolve(Thread.currentThread().nn)

    private def resolve(t: Thread): Slot =
        val found = slots.get(t)
        if found != null then found
        else
            val fresh = new Slot(t, init(t))
            val prev  = slots.putIfAbsent(t, fresh)
            if prev != null then prev
            else
                sweep()
                fresh
            end if
        end if
    end resolve

    private def sweep(): Unit =
        val it = slots.entrySet().nn.iterator().nn
        while it.hasNext do
            if !it.next().nn.getKey.nn.isAlive then it.remove()
    end sweep

    private def slot(): Slot =
        val t = Thread.currentThread().nn
        val s = cache.get().nn
        if s.owner eq t then s else resolve(t)
    end slot

    /** This thread's value, created on the first read. */
    def get(): A = slot().value

    /** Replaces this thread's value. */
    def set(value: A): Unit = slot().value = value

    /** Test seam: drops this thread's cached lookup, so the next read resolves it from the map again. */
    private[ffi] def evictCacheForTest(): Unit = cache.remove()

    /** Test seam: the number of threads holding a value. */
    private[ffi] def sizeForTest: Int = slots.size()

end ThreadOwned
