package kyo

import java.util.concurrent.atomic as j

class AtomicInt private[kyo] (ref: j.AtomicInteger) extends AnyVal:
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
end AtomicInt

object AtomicInt:
    def init(v: Int)(using Frame): AtomicInt < IO = IO(AtomicInt(new j.AtomicInteger(v)))

class AtomicLong private[kyo] (ref: j.AtomicLong) extends AnyVal:
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
end AtomicLong

object AtomicLong:
    def init(v: Long)(using Frame): AtomicLong < IO = IO(AtomicLong(new j.AtomicLong(v)))

class AtomicBoolean private[kyo] (ref: j.AtomicBoolean) extends AnyVal:
    def get(using Frame): Boolean < IO                               = IO(ref.get())
    def set(v: Boolean)(using Frame): Unit < IO                      = IO(ref.set(v))
    def lazySet(v: Boolean)(using Frame): Unit < IO                  = IO(ref.lazySet(v))
    def getAndSet(v: Boolean)(using Frame): Boolean < IO             = IO(ref.getAndSet(v))
    def cas(curr: Boolean, next: Boolean)(using Frame): Boolean < IO = IO(ref.compareAndSet(curr, next))
    override def toString                                            = ref.toString()
end AtomicBoolean

object AtomicBoolean:
    def init(v: Boolean)(using Frame): AtomicBoolean < IO = IO(AtomicBoolean(new j.AtomicBoolean(v)))

class AtomicRef[A] private[kyo] (private val ref: j.AtomicReference[A]) extends AnyVal:
    def get(using Frame): A < IO                         = IO(ref.get())
    def set(v: A)(using Frame): Unit < IO                = IO(ref.set(v))
    def lazySet(v: A)(using Frame): Unit < IO            = IO(ref.lazySet(v))
    def getAndSet(v: A)(using Frame): A < IO             = IO(ref.getAndSet(v))
    def cas(curr: A, next: A)(using Frame): Boolean < IO = IO(ref.compareAndSet(curr, next))
    def update[S](f: A => A)(using Frame): Unit < IO     = updateAndGet(f).unit
    def updateAndGet[S](f: A => A)(using Frame): A < IO  = IO(ref.updateAndGet(f(_)))
    override def toString                                = ref.toString()
end AtomicRef

object AtomicRef:
    def init[A](v: A)(using Frame): AtomicRef[A] < IO = IO(AtomicRef(new j.AtomicReference(v)))
