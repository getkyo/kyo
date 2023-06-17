package kyo.concurrent

import kyo._
import kyo.ios._

import java.util.concurrent.atomic.{AtomicBoolean => JAtomicBoolean}
import java.util.concurrent.atomic.{AtomicInteger => JAtomicInteger}
import java.util.concurrent.atomic.{AtomicLong => JAtomicLong}
import java.util.concurrent.atomic.{AtomicReference => JAtomicReference}
import scala.annotation.tailrec

object atomics {

  object Atomics {
    def initInt(v: Int): AtomicInt > IOs             = IOs(new AtomicInt(new JAtomicInteger(v)))
    def initLong(v: Long): AtomicLong > IOs          = IOs(new AtomicLong(new JAtomicLong(v)))
    def initBoolean(v: Boolean): AtomicBoolean > IOs = IOs(new AtomicBoolean(new JAtomicBoolean(v)))
    def initRef[T](v: T): AtomicRef[T] > IOs = IOs(new AtomicRef(new JAtomicReference[T](v)))
  }

  class AtomicInt private[atomics] (private val ref: JAtomicInteger) extends AnyVal {

    def get: Int > IOs =
      IOs(ref.get())

    def set(v: Int): Unit > IOs =
      IOs(ref.set(v))

    def lazySet(v: Int): Unit > IOs =
      IOs(ref.lazySet(v))

    def getAndSet(v: Int): Int > IOs =
      IOs(ref.getAndSet(v))

    def cas(curr: Int, next: Int): Boolean > IOs =
      IOs(ref.compareAndSet(curr, next))

    def incrementAndGet: Int > IOs =
      IOs(ref.incrementAndGet())

    def decrementAndGet: Int > IOs =
      IOs(ref.decrementAndGet())

    def getAndIncrement: Int > IOs =
      IOs(ref.getAndIncrement())

    def getAndDecrement: Int > IOs =
      IOs(ref.getAndDecrement())

    def getAndAdd(v: Int): Int > IOs =
      IOs(ref.getAndAdd(v))

    def addAndGet(v: Int): Int > IOs =
      IOs(ref.addAndGet(v))

    override def toString = ref.toString()
  }

  class AtomicLong private[atomics] (private val ref: JAtomicLong) extends AnyVal {

    def get: Long > IOs =
      IOs(ref.get())

    def set(v: Long): Unit > IOs =
      IOs(ref.set(v))

    def lazySet(v: Long): Unit > IOs =
      IOs(ref.lazySet(v))

    def getAndSet(v: Long): Long > IOs =
      IOs(ref.getAndSet(v))

    def cas(curr: Long, next: Long): Boolean > IOs =
      IOs(ref.compareAndSet(curr, next))

    def incrementAndGet: Long > IOs =
      IOs(ref.incrementAndGet())

    def decrementAndGet: Long > IOs =
      IOs(ref.decrementAndGet())

    def getAndIncrement: Long > IOs =
      IOs(ref.getAndIncrement())

    def getAndDecrement: Long > IOs =
      IOs(ref.getAndDecrement())

    def getAndAdd(v: Long): Long > IOs =
      IOs(ref.getAndAdd(v))

    def addAndGet(v: Long): Long > IOs =
      IOs(ref.addAndGet(v))

    override def toString = ref.toString()
  }

  class AtomicBoolean private[atomics] (private val ref: JAtomicBoolean) extends AnyVal {

    def get: Boolean > IOs =
      IOs(ref.get())

    def set(v: Boolean): Unit > IOs =
      IOs(ref.set(v))

    def lazySet(v: Boolean): Unit > IOs =
      IOs(ref.lazySet(v))

    def getAndSet(v: Boolean): Boolean > IOs =
      IOs(ref.getAndSet(v))

    def cas(curr: Boolean, next: Boolean): Boolean > IOs =
      IOs(ref.compareAndSet(curr, next))

    override def toString = ref.toString()
  }

  class AtomicRef[T] private[atomics] (private val ref: JAtomicReference[T]) extends AnyVal {

    def get: T > IOs =
      IOs(ref.get())

    def set(v: T): Unit > IOs =
      IOs(ref.set(v))

    def lazySet(v: T): Unit > IOs =
      IOs(ref.lazySet(v))

    def getAndSet(v: T): T > IOs =
      IOs(ref.getAndSet(v))

    def cas(curr: T, next: T): Boolean > IOs =
      IOs(ref.compareAndSet(curr, next))

    override def toString = ref.toString()
  }
}
