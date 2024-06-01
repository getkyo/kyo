package kyo

import java.util.concurrent.atomic.AtomicBoolean as JAtomicBoolean
import java.util.concurrent.atomic.AtomicInteger as JAtomicInteger
import java.util.concurrent.atomic.AtomicLong as JAtomicLong
import java.util.concurrent.atomic.AtomicReference as JAtomicReference
import kyo.internal.Trace

object Atomics:

    def initInt(v: Int)(using Trace): AtomicInt < IOs             = IOs(new AtomicInt(new JAtomicInteger(v)))
    def initLong(v: Long)(using Trace): AtomicLong < IOs          = IOs(new AtomicLong(new JAtomicLong(v)))
    def initBoolean(v: Boolean)(using Trace): AtomicBoolean < IOs = IOs(new AtomicBoolean(new JAtomicBoolean(v)))
    def initRef[T](v: T)(using Trace): AtomicRef[T] < IOs         = IOs(new AtomicRef(new JAtomicReference[T](v)))
end Atomics

class AtomicInt private[kyo] (private val ref: JAtomicInteger) extends AnyVal:

    def get(using Trace): Int < IOs                           = IOs(ref.get())
    def set(v: Int)(using Trace): Unit < IOs                  = IOs(ref.set(v))
    def lazySet(v: Int)(using Trace): Unit < IOs              = IOs(ref.lazySet(v))
    def getAndSet(v: Int)(using Trace): Int < IOs             = IOs(ref.getAndSet(v))
    def cas(curr: Int, next: Int)(using Trace): Boolean < IOs = IOs(ref.compareAndSet(curr, next))
    def incrementAndGet(using Trace): Int < IOs               = IOs(ref.incrementAndGet())
    def decrementAndGet(using Trace): Int < IOs               = IOs(ref.decrementAndGet())
    def getAndIncrement(using Trace): Int < IOs               = IOs(ref.getAndIncrement())
    def getAndDecrement(using Trace): Int < IOs               = IOs(ref.getAndDecrement())
    def getAndAdd(v: Int)(using Trace): Int < IOs             = IOs(ref.getAndAdd(v))
    def addAndGet(v: Int)(using Trace): Int < IOs             = IOs(ref.addAndGet(v))

    override def toString = ref.toString()
end AtomicInt

class AtomicLong private[kyo] (private val ref: JAtomicLong) extends AnyVal:

    def get(using Trace): Long < IOs                            = IOs(ref.get())
    def set(v: Long)(using Trace): Unit < IOs                   = IOs(ref.set(v))
    def lazySet(v: Long)(using Trace): Unit < IOs               = IOs(ref.lazySet(v))
    def getAndSet(v: Long)(using Trace): Long < IOs             = IOs(ref.getAndSet(v))
    def cas(curr: Long, next: Long)(using Trace): Boolean < IOs = IOs(ref.compareAndSet(curr, next))
    def incrementAndGet(using Trace): Long < IOs                = IOs(ref.incrementAndGet())
    def decrementAndGet(using Trace): Long < IOs                = IOs(ref.decrementAndGet())
    def getAndIncrement(using Trace): Long < IOs                = IOs(ref.getAndIncrement())
    def getAndDecrement(using Trace): Long < IOs                = IOs(ref.getAndDecrement())
    def getAndAdd(v: Long)(using Trace): Long < IOs             = IOs(ref.getAndAdd(v))
    def addAndGet(v: Long)(using Trace): Long < IOs             = IOs(ref.addAndGet(v))

    override def toString = ref.toString()
end AtomicLong

class AtomicBoolean private[kyo] (private val ref: JAtomicBoolean) extends AnyVal:

    def get(using Trace): Boolean < IOs                               = IOs(ref.get())
    def set(v: Boolean)(using Trace): Unit < IOs                      = IOs(ref.set(v))
    def lazySet(v: Boolean)(using Trace): Unit < IOs                  = IOs(ref.lazySet(v))
    def getAndSet(v: Boolean)(using Trace): Boolean < IOs             = IOs(ref.getAndSet(v))
    def cas(curr: Boolean, next: Boolean)(using Trace): Boolean < IOs = IOs(ref.compareAndSet(curr, next))

    override def toString = ref.toString()
end AtomicBoolean

class AtomicRef[T] private[kyo] (private val ref: JAtomicReference[T]) extends AnyVal:

    def get(using Trace): T < IOs                         = IOs(ref.get())
    def set(v: T)(using Trace): Unit < IOs                = IOs(ref.set(v))
    def lazySet(v: T)(using Trace): Unit < IOs            = IOs(ref.lazySet(v))
    def getAndSet(v: T)(using Trace): T < IOs             = IOs(ref.getAndSet(v))
    def cas(curr: T, next: T)(using Trace): Boolean < IOs = IOs(ref.compareAndSet(curr, next))
    def update[S](f: T => T)(using Trace): Unit < IOs     = updateAndGet(f).unit
    def updateAndGet[S](f: T => T)(using Trace): T < IOs  = IOs(ref.updateAndGet(f(_)))

    override def toString = ref.toString()
end AtomicRef
