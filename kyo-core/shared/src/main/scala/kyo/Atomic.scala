package kyo

import java.util.concurrent.atomic as j

/** A wrapper for Java's AtomicInteger.
  */
final class AtomicInt private[kyo] (ref: j.AtomicInteger) extends AnyVal:

    /** Gets the current value.
      * @return
      *   The current integer value
      */
    def get(using Frame): Int < IO = IO(ref.get())

    /** Sets to the given value.
      * @param v
      *   The new value
      */
    def set(v: Int)(using Frame): Unit < IO = IO(ref.set(v))

    /** Eventually sets to the given value.
      * @param v
      *   The new value
      */
    def lazySet(v: Int)(using Frame): Unit < IO = IO(ref.lazySet(v))

    /** Atomically sets to the given value and returns the old value.
      * @param v
      *   The new value
      * @return
      *   The previous value
      */
    def getAndSet(v: Int)(using Frame): Int < IO = IO(ref.getAndSet(v))

    /** Atomically sets the value to the given updated value if the current value is equal to the expected value.
      * @param curr
      *   The expected current value
      * @param next
      *   The new value
      * @return
      *   true if successful, false otherwise
      */
    def cas(curr: Int, next: Int)(using Frame): Boolean < IO = IO(ref.compareAndSet(curr, next))

    /** Atomically increments the current value and returns the updated value.
      * @return
      *   The updated value
      */
    def incrementAndGet(using Frame): Int < IO = IO(ref.incrementAndGet())

    /** Atomically decrements the current value and returns the updated value.
      * @return
      *   The updated value
      */
    def decrementAndGet(using Frame): Int < IO = IO(ref.decrementAndGet())

    /** Atomically increments the current value and returns the old value.
      * @return
      *   The previous value
      */
    def getAndIncrement(using Frame): Int < IO = IO(ref.getAndIncrement())

    /** Atomically decrements the current value and returns the old value.
      * @return
      *   The previous value
      */
    def getAndDecrement(using Frame): Int < IO = IO(ref.getAndDecrement())

    /** Atomically adds the given value to the current value and returns the old value.
      * @param v
      *   The value to add
      * @return
      *   The previous value
      */
    def getAndAdd(v: Int)(using Frame): Int < IO = IO(ref.getAndAdd(v))

    /** Atomically adds the given value to the current value and returns the updated value.
      * @param v
      *   The value to add
      * @return
      *   The updated value
      */
    def addAndGet(v: Int)(using Frame): Int < IO = IO(ref.addAndGet(v))

    /** Returns a string representation of the current value.
      * @return
      *   A string representation of the atomic integer
      */
    override def toString = ref.toString()
end AtomicInt

object AtomicInt:
    /** Creates a new AtomicInt with the given initial value.
      * @param v
      *   The initial value
      * @return
      *   A new AtomicInt instance
      */
    def init(v: Int)(using Frame): AtomicInt < IO = IO(AtomicInt(new j.AtomicInteger(v)))
end AtomicInt

/** A wrapper for Java's AtomicLong.
  */
final class AtomicLong private[kyo] (ref: j.AtomicLong) extends AnyVal:
    /** Gets the current value.
      * @return
      *   The current long value
      */
    def get(using Frame): Long < IO = IO(ref.get())

    /** Sets to the given value.
      * @param v
      *   The new value
      */
    def set(v: Long)(using Frame): Unit < IO = IO(ref.set(v))

    /** Eventually sets to the given value.
      * @param v
      *   The new value
      */
    def lazySet(v: Long)(using Frame): Unit < IO = IO(ref.lazySet(v))

    /** Atomically sets to the given value and returns the old value.
      * @param v
      *   The new value
      * @return
      *   The previous value
      */
    def getAndSet(v: Long)(using Frame): Long < IO = IO(ref.getAndSet(v))

    /** Atomically sets the value to the given updated value if the current value is equal to the expected value.
      * @param curr
      *   The expected current value
      * @param next
      *   The new value
      * @return
      *   true if successful, false otherwise
      */
    def cas(curr: Long, next: Long)(using Frame): Boolean < IO = IO(ref.compareAndSet(curr, next))

    /** Atomically increments the current value and returns the updated value.
      * @return
      *   The updated value
      */
    def incrementAndGet(using Frame): Long < IO = IO(ref.incrementAndGet())

    /** Atomically decrements the current value and returns the updated value.
      * @return
      *   The updated value
      */
    def decrementAndGet(using Frame): Long < IO = IO(ref.decrementAndGet())

    /** Atomically increments the current value and returns the old value.
      * @return
      *   The previous value
      */
    def getAndIncrement(using Frame): Long < IO = IO(ref.getAndIncrement())

    /** Atomically decrements the current value and returns the old value.
      * @return
      *   The previous value
      */
    def getAndDecrement(using Frame): Long < IO = IO(ref.getAndDecrement())

    /** Atomically adds the given value to the current value and returns the old value.
      * @param v
      *   The value to add
      * @return
      *   The previous value
      */
    def getAndAdd(v: Long)(using Frame): Long < IO = IO(ref.getAndAdd(v))

    /** Atomically adds the given value to the current value and returns the updated value.
      * @param v
      *   The value to add
      * @return
      *   The updated value
      */
    def addAndGet(v: Long)(using Frame): Long < IO = IO(ref.addAndGet(v))

    /** Returns a string representation of the current value.
      * @return
      *   A string representation of the atomic long
      */
    override def toString = ref.toString()
end AtomicLong

object AtomicLong:

    /** Creates a new AtomicLong with the given initial value.
      * @param v
      *   The initial value
      * @return
      *   A new AtomicLong instance
      */
    def init(v: Long)(using Frame): AtomicLong < IO = IO(AtomicLong(new j.AtomicLong(v)))
end AtomicLong

/** A wrapper for Java's AtomicBoolean.
  */
final class AtomicBoolean private[kyo] (ref: j.AtomicBoolean) extends AnyVal:
    /** Gets the current value.
      * @return
      *   The current boolean value
      */
    def get(using Frame): Boolean < IO = IO(ref.get())

    /** Sets to the given value.
      * @param v
      *   The new value
      */
    def set(v: Boolean)(using Frame): Unit < IO = IO(ref.set(v))

    /** Eventually sets to the given value.
      * @param v
      *   The new value
      */
    def lazySet(v: Boolean)(using Frame): Unit < IO = IO(ref.lazySet(v))

    /** Atomically sets to the given value and returns the old value.
      * @param v
      *   The new value
      * @return
      *   The previous value
      */
    def getAndSet(v: Boolean)(using Frame): Boolean < IO = IO(ref.getAndSet(v))

    /** Atomically sets the value to the given updated value if the current value is equal to the expected value.
      * @param curr
      *   The expected current value
      * @param next
      *   The new value
      * @return
      *   true if successful, false otherwise
      */
    def cas(curr: Boolean, next: Boolean)(using Frame): Boolean < IO = IO(ref.compareAndSet(curr, next))

    /** Returns a string representation of the current value.
      * @return
      *   A string representation of the atomic boolean
      */
    override def toString = ref.toString()
end AtomicBoolean

object AtomicBoolean:

    /** Creates a new AtomicBoolean with the given initial value.
      * @param v
      *   The initial value
      * @return
      *   A new AtomicBoolean instance
      */
    def init(v: Boolean)(using Frame): AtomicBoolean < IO = IO(AtomicBoolean(new j.AtomicBoolean(v)))
end AtomicBoolean

/** A wrapper for Java's AtomicReference.
  *
  * @tparam A
  *   The type of the referenced value
  */
final class AtomicRef[A] private[kyo] (private val ref: j.AtomicReference[A]) extends AnyVal:

    /** Gets the current value.
      * @return
      *   The current value
      */
    def get(using Frame): A < IO = IO(ref.get())

    /** Sets to the given value.
      * @param v
      *   The new value
      */
    def set(v: A)(using Frame): Unit < IO = IO(ref.set(v))

    /** Eventually sets to the given value.
      * @param v
      *   The new value
      */
    def lazySet(v: A)(using Frame): Unit < IO = IO(ref.lazySet(v))

    /** Atomically sets to the given value and returns the old value.
      * @param v
      *   The new value
      * @return
      *   The previous value
      */
    def getAndSet(v: A)(using Frame): A < IO = IO(ref.getAndSet(v))

    /** Atomically sets the value to the given updated value if the current value is equal to the expected value.
      * @param curr
      *   The expected current value
      * @param next
      *   The new value
      * @return
      *   true if successful, false otherwise
      */
    def cas(curr: A, next: A)(using Frame): Boolean < IO = IO(ref.compareAndSet(curr, next))

    /** Atomically updates the current value using the given function.
      * @param f
      *   The function to apply to the current value
      */
    def update[S](f: A => A)(using Frame): Unit < IO = updateAndGet(f).unit

    /** Atomically updates the current value using the given function and returns the updated value.
      * @param f
      *   The function to apply to the current value
      * @return
      *   The updated value
      */
    def updateAndGet[S](f: A => A)(using Frame): A < IO = IO(ref.updateAndGet(f(_)))

    /** Returns a string representation of the current value.
      * @return
      *   A string representation of the atomic reference
      */
    override def toString = ref.toString()
end AtomicRef

object AtomicRef:

    /** Creates a new AtomicRef with the given initial value.
      * @param v
      *   The initial value
      * @return
      *   A new AtomicRef instance
      * @tparam A
      *   The type of the referenced value
      */
    def init[A](v: A)(using Frame): AtomicRef[A] < IO = IO(AtomicRef(new j.AtomicReference(v)))
end AtomicRef
