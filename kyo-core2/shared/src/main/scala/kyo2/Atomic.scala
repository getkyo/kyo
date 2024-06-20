package kyo2

import java.util.concurrent.atomic.*

object Atomic:

    def initInt(v: Int): OfInt < IO             = IO(OfInt(new AtomicInteger(v)))
    def initLong(v: Long): OfLong < IO          = IO(OfLong(new AtomicLong(v)))
    def initBoolean(v: Boolean): OfBoolean < IO = IO(OfBoolean(new AtomicBoolean(v)))
    def initRef[T](v: T): OfRef[T] < IO         = IO(OfRef(new AtomicReference(v)))

    class OfInt private[kyo2] (ref: AtomicInteger) extends AnyVal:
        def get(using Frame): Int < IO                           = IO(ref.get())
        def set(v: Int)(using Frame): Unit < IO                  = IO(ref.set(v))
        def lazySet(v: Int)(using Frame): Unit < IO              = IO(ref.lazySet(v))
        def getAndSet(v: Int)(using Frame): Int < IO             = IO(ref.getAndSet(v))
        def cas(curr: Int, next: Int)(using Frame): Boolean < IO = IO(ref.compareAndSet(curr, next))
        def incrementAndGet(using Frame): Int < IO               = IO(ref.incrementAndGet())
        def decrementAndGet(using Frame): Int < IO               = IO(ref.decrementAndGet())
        def getAndIncrement(using Frame): Int < IO               = IO(ref.getAndIncrement())
        def getAndDecrement(using Frame): Int < IO               = IO(ref.getAndDecrement())
        def getAndAdd(v: Int)(using Frame): Int < IO             = IO(ref.getAndAdd(v))
        def addAndGet(v: Int)(using Frame): Int < IO             = IO(ref.addAndGet(v))
        override def toString                                    = ref.toString()
    end OfInt

    class OfLong private[kyo2] (ref: AtomicLong) extends AnyVal:
        def get(using Frame): Long < IO                            = IO(ref.get())
        def set(v: Long)(using Frame): Unit < IO                   = IO(ref.set(v))
        def lazySet(v: Long)(using Frame): Unit < IO               = IO(ref.lazySet(v))
        def getAndSet(v: Long)(using Frame): Long < IO             = IO(ref.getAndSet(v))
        def cas(curr: Long, next: Long)(using Frame): Boolean < IO = IO(ref.compareAndSet(curr, next))
        def incrementAndGet(using Frame): Long < IO                = IO(ref.incrementAndGet())
        def decrementAndGet(using Frame): Long < IO                = IO(ref.decrementAndGet())
        def getAndIncrement(using Frame): Long < IO                = IO(ref.getAndIncrement())
        def getAndDecrement(using Frame): Long < IO                = IO(ref.getAndDecrement())
        def getAndAdd(v: Long)(using Frame): Long < IO             = IO(ref.getAndAdd(v))
        def addAndGet(v: Long)(using Frame): Long < IO             = IO(ref.addAndGet(v))
        override def toString                                      = ref.toString()
    end OfLong

    class OfBoolean private[kyo2] (ref: AtomicBoolean) extends AnyVal:
        def get(using Frame): Boolean < IO                               = IO(ref.get())
        def set(v: Boolean)(using Frame): Unit < IO                      = IO(ref.set(v))
        def lazySet(v: Boolean)(using Frame): Unit < IO                  = IO(ref.lazySet(v))
        def getAndSet(v: Boolean)(using Frame): Boolean < IO             = IO(ref.getAndSet(v))
        def cas(curr: Boolean, next: Boolean)(using Frame): Boolean < IO = IO(ref.compareAndSet(curr, next))
        override def toString                                            = ref.toString()
    end OfBoolean

    class OfRef[T] private[kyo2] (private val ref: AtomicReference[T]) extends AnyVal:
        def get(using Frame): T < IO                         = IO(ref.get())
        def set(v: T)(using Frame): Unit < IO                = IO(ref.set(v))
        def lazySet(v: T)(using Frame): Unit < IO            = IO(ref.lazySet(v))
        def getAndSet(v: T)(using Frame): T < IO             = IO(ref.getAndSet(v))
        def cas(curr: T, next: T)(using Frame): Boolean < IO = IO(ref.compareAndSet(curr, next))
        def update[S](f: T => T)(using Frame): Unit < IO     = updateAndGet(f).unit
        def updateAndGet[S](f: T => T)(using Frame): T < IO  = IO(ref.updateAndGet(f(_)))
        override def toString                                = ref.toString()
    end OfRef
end Atomic
