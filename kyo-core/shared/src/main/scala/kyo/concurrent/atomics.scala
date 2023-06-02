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
    /*inline(1)*/
    def get: Int > IOs =
      IOs(ref.get())
    /*inline(1)*/
    def set(v: Int): Unit > IOs =
      IOs(ref.set(v))
    /*inline(1)*/
    def lazySet(v: Int): Unit > IOs =
      IOs(ref.lazySet(v))
    /*inline(1)*/
    def getAndSet(v: Int): Int > IOs =
      IOs(ref.getAndSet(v))
    /*inline(1)*/
    def cas(curr: Int, next: Int): Boolean > IOs =
      IOs(ref.compareAndSet(curr, next))
    /*inline(1)*/
    def incrementAndGet: Int > IOs =
      IOs(ref.incrementAndGet())
    /*inline(1)*/
    def decrementAndGet: Int > IOs =
      IOs(ref.decrementAndGet())
    /*inline(1)*/
    def getAndIncrement: Int > IOs =
      IOs(ref.getAndIncrement())
    /*inline(1)*/
    def getAndDecrement: Int > IOs =
      IOs(ref.getAndDecrement())
    /*inline(1)*/
    def getAndAdd(v: Int): Int > IOs =
      IOs(ref.getAndAdd(v))
    /*inline(1)*/
    def addAndGet(v: Int): Int > IOs =
      IOs(ref.addAndGet(v))
  }

  class AtomicLong private[atomics] (private val ref: JAtomicLong) extends AnyVal {
    /*inline(1)*/
    def get: Long > IOs =
      IOs(ref.get())
    /*inline(1)*/
    def set(v: Long): Unit > IOs =
      IOs(ref.set(v))
    /*inline(1)*/
    def lazySet(v: Long): Unit > IOs =
      IOs(ref.lazySet(v))
    /*inline(1)*/
    def getAndSet(v: Long): Long > IOs =
      IOs(ref.getAndSet(v))
    /*inline(1)*/
    def cas(curr: Long, next: Long): Boolean > IOs =
      IOs(ref.compareAndSet(curr, next))
    /*inline(1)*/
    def incrementAndGet: Long > IOs =
      IOs(ref.incrementAndGet())
    /*inline(1)*/
    def decrementAndGet: Long > IOs =
      IOs(ref.decrementAndGet())
    /*inline(1)*/
    def getAndIncrement: Long > IOs =
      IOs(ref.getAndIncrement())
    /*inline(1)*/
    def getAndDecrement: Long > IOs =
      IOs(ref.getAndDecrement())
    /*inline(1)*/
    def getAndAdd(v: Long): Long > IOs =
      IOs(ref.getAndAdd(v))
    /*inline(1)*/
    def addAndGet(v: Long): Long > IOs =
      IOs(ref.addAndGet(v))
  }

  class AtomicBoolean private[atomics] (private val ref: JAtomicBoolean) extends AnyVal {
    /*inline(1)*/
    def get: Boolean > IOs =
      IOs(ref.get())
    /*inline(1)*/
    def set(v: Boolean): Unit > IOs =
      IOs(ref.set(v))
    /*inline(1)*/
    def lazySet(v: Boolean): Unit > IOs =
      IOs(ref.lazySet(v))
    /*inline(1)*/
    def getAndSet(v: Boolean): Boolean > IOs =
      IOs(ref.getAndSet(v))
    /*inline(1)*/
    def cas(curr: Boolean, next: Boolean): Boolean > IOs =
      IOs(ref.compareAndSet(curr, next))
  }

  class AtomicRef[T] private[atomics] (private val ref: JAtomicReference[T]) extends AnyVal {
    /*inline(1)*/
    def get: T > IOs =
      IOs(ref.get())
    /*inline(1)*/
    def set(v: T): Unit > IOs =
      IOs(ref.set(v))
    /*inline(1)*/
    def lazySet(v: T): Unit > IOs =
      IOs(ref.lazySet(v))
    /*inline(1)*/
    def getAndSet(v: T): T > IOs =
      IOs(ref.getAndSet(v))
    /*inline(1)*/
    def cas(curr: T, next: T): Boolean > IOs =
      IOs(ref.compareAndSet(curr, next))
  }
}
