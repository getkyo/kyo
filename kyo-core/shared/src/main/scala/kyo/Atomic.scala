package kyo

import java.util.concurrent.atomic as j

/** A wrapper for Java's AtomicInteger.
  */
final case class AtomicInt private (unsafe: AtomicInt.Unsafe) extends AnyVal:

    /** Gets the current value.
      * @return
      *   The current integer value
      */
    inline def get(using inline frame: Frame): Int < IO = IO(unsafe.get())

    /** Sets to the given value.
      * @param v
      *   The new value
      */
    inline def set(v: Int)(using inline frame: Frame): Unit < IO = IO(unsafe.set(v))

    /** Eventually sets to the given value.
      * @param v
      *   The new value
      */
    inline def lazySet(v: Int)(using inline frame: Frame): Unit < IO = IO(unsafe.lazySet(v))

    /** Atomically sets to the given value and returns the old value.
      * @param v
      *   The new value
      * @return
      *   The previous value
      */
    inline def getAndSet(v: Int)(using inline frame: Frame): Int < IO = IO(unsafe.getAndSet(v))

    /** Atomically sets the value to the given updated value if the current value is equal to the expected value.
      * @param curr
      *   The expected current value
      * @param next
      *   The new value
      * @return
      *   true if successful, false otherwise
      */
    inline def cas(curr: Int, next: Int)(using inline frame: Frame): Boolean < IO = IO(unsafe.cas(curr, next))

    /** Atomically increments the current value and returns the updated value.
      * @return
      *   The updated value
      */
    inline def incrementAndGet(using inline frame: Frame): Int < IO = IO(unsafe.incrementAndGet())

    /** Atomically decrements the current value and returns the updated value.
      * @return
      *   The updated value
      */
    inline def decrementAndGet(using inline frame: Frame): Int < IO = IO(unsafe.decrementAndGet())

    /** Atomically increments the current value and returns the old value.
      * @return
      *   The previous value
      */
    inline def getAndIncrement(using inline frame: Frame): Int < IO = IO(unsafe.getAndIncrement())

    /** Atomically decrements the current value and returns the old value.
      * @return
      *   The previous value
      */
    inline def getAndDecrement(using inline frame: Frame): Int < IO = IO(unsafe.getAndDecrement())

    /** Atomically adds the given value to the current value and returns the old value.
      * @param v
      *   The value to add
      * @return
      *   The previous value
      */
    inline def getAndAdd(v: Int)(using inline frame: Frame): Int < IO = IO(unsafe.getAndAdd(v))

    /** Atomically adds the given value to the current value and returns the updated value.
      * @param v
      *   The value to add
      * @return
      *   The updated value
      */
    inline def addAndGet(v: Int)(using inline frame: Frame): Int < IO = IO(unsafe.addAndGet(v))

    /** Returns a string representation of the current value.
      * @return
      *   A string representation of the atomic integer
      */
    override def toString = unsafe.toString()

end AtomicInt

object AtomicInt:
    /** Creates a new AtomicInt with the given initial value.
      * @param v
      *   The initial value
      * @return
      *   A new AtomicInt instance
      */
    def init(v: Int)(using Frame): AtomicInt < IO = IO(AtomicInt(new j.AtomicInteger(v)))

    opaque type Unsafe = j.AtomicInteger

    object Unsafe:
        given Flat[Unsafe] = Flat.unsafe.bypass

        def init(v: Int)(using allow: AllowUnsafe): Unsafe = new j.AtomicInteger(v)

        extension (self: Unsafe)
            inline def get()(using inline allow: AllowUnsafe): Int                         = self.get()
            inline def set(v: Int)(using inline allow: AllowUnsafe): Unit                  = self.set(v)
            inline def lazySet(v: Int)(using inline allow: AllowUnsafe): Unit              = self.lazySet(v)
            inline def getAndSet(v: Int)(using inline allow: AllowUnsafe): Int             = self.getAndSet(v)
            inline def cas(curr: Int, next: Int)(using inline allow: AllowUnsafe): Boolean = self.compareAndSet(curr, next)
            inline def incrementAndGet()(using inline allow: AllowUnsafe): Int             = self.incrementAndGet()
            inline def decrementAndGet()(using inline allow: AllowUnsafe): Int             = self.decrementAndGet()
            inline def getAndIncrement()(using inline allow: AllowUnsafe): Int             = self.getAndIncrement()
            inline def getAndDecrement()(using inline allow: AllowUnsafe): Int             = self.getAndDecrement()
            inline def getAndAdd(v: Int)(using inline allow: AllowUnsafe): Int             = self.getAndAdd(v)
            inline def addAndGet(v: Int)(using inline allow: AllowUnsafe): Int             = self.addAndGet(v)
            inline def safe: AtomicInt                                                     = AtomicInt(self)
        end extension
    end Unsafe
end AtomicInt

/** A wrapper for Java's AtomicLong.
  */
final case class AtomicLong private (unsafe: AtomicLong.Unsafe) extends AnyVal:
    /** Gets the current value.
      * @return
      *   The current long value
      */
    inline def get(using inline frame: Frame): Long < IO = IO(unsafe.get())

    /** Sets to the given value.
      * @param v
      *   The new value
      */
    inline def set(v: Long)(using inline frame: Frame): Unit < IO = IO(unsafe.set(v))

    /** Eventually sets to the given value.
      * @param v
      *   The new value
      */
    inline def lazySet(v: Long)(using inline frame: Frame): Unit < IO = IO(unsafe.lazySet(v))

    /** Atomically sets to the given value and returns the old value.
      * @param v
      *   The new value
      * @return
      *   The previous value
      */
    inline def getAndSet(v: Long)(using inline frame: Frame): Long < IO = IO(unsafe.getAndSet(v))

    /** Atomically sets the value to the given updated value if the current value is equal to the expected value.
      * @param curr
      *   The expected current value
      * @param next
      *   The new value
      * @return
      *   true if successful, false otherwise
      */
    inline def cas(curr: Long, next: Long)(using inline frame: Frame): Boolean < IO = IO(unsafe.cas(curr, next))

    /** Atomically increments the current value and returns the updated value.
      * @return
      *   The updated value
      */
    inline def incrementAndGet(using inline frame: Frame): Long < IO = IO(unsafe.incrementAndGet())

    /** Atomically decrements the current value and returns the updated value.
      * @return
      *   The updated value
      */
    inline def decrementAndGet(using inline frame: Frame): Long < IO = IO(unsafe.decrementAndGet())

    /** Atomically increments the current value and returns the old value.
      * @return
      *   The previous value
      */
    inline def getAndIncrement(using inline frame: Frame): Long < IO = IO(unsafe.getAndIncrement())

    /** Atomically decrements the current value and returns the old value.
      * @return
      *   The previous value
      */
    inline def getAndDecrement(using inline frame: Frame): Long < IO = IO(unsafe.getAndDecrement())

    /** Atomically adds the given value to the current value and returns the old value.
      * @param v
      *   The value to add
      * @return
      *   The previous value
      */
    inline def getAndAdd(v: Long)(using inline frame: Frame): Long < IO = IO(unsafe.getAndAdd(v))

    /** Atomically adds the given value to the current value and returns the updated value.
      * @param v
      *   The value to add
      * @return
      *   The updated value
      */
    inline def addAndGet(v: Long)(using inline frame: Frame): Long < IO = IO(unsafe.addAndGet(v))

    /** Returns a string representation of the current value.
      * @return
      *   A string representation of the atomic long
      */
    override def toString = unsafe.toString()

end AtomicLong

object AtomicLong:

    /** Creates a new AtomicLong with the given initial value.
      * @param v
      *   The initial value
      * @return
      *   A new AtomicLong instance
      */
    def init(v: Long)(using Frame): AtomicLong < IO = IO(AtomicLong(new j.AtomicLong(v)))

    opaque type Unsafe = j.AtomicLong

    object Unsafe:
        given Flat[Unsafe] = Flat.unsafe.bypass

        def init(v: Long)(using AllowUnsafe): Unsafe = new j.AtomicLong(v)

        extension (self: Unsafe)
            inline def get()(using inline allow: AllowUnsafe): Long                          = self.get()
            inline def set(v: Long)(using inline allow: AllowUnsafe): Unit                   = self.set(v)
            inline def lazySet(v: Long)(using inline allow: AllowUnsafe): Unit               = self.lazySet(v)
            inline def getAndSet(v: Long)(using inline allow: AllowUnsafe): Long             = self.getAndSet(v)
            inline def cas(curr: Long, next: Long)(using inline allow: AllowUnsafe): Boolean = self.compareAndSet(curr, next)
            inline def incrementAndGet()(using inline allow: AllowUnsafe): Long              = self.incrementAndGet()
            inline def decrementAndGet()(using inline allow: AllowUnsafe): Long              = self.decrementAndGet()
            inline def getAndIncrement()(using inline allow: AllowUnsafe): Long              = self.getAndIncrement()
            inline def getAndDecrement()(using inline allow: AllowUnsafe): Long              = self.getAndDecrement()
            inline def getAndAdd(v: Long)(using inline allow: AllowUnsafe): Long             = self.getAndAdd(v)
            inline def addAndGet(v: Long)(using inline allow: AllowUnsafe): Long             = self.addAndGet(v)
            inline def safe: AtomicLong                                                      = AtomicLong(self)
        end extension
    end Unsafe
end AtomicLong

/** A wrapper for Java's AtomicBoolean.
  */
final case class AtomicBoolean private (unsafe: AtomicBoolean.Unsafe) extends AnyVal:
    /** Gets the current value.
      * @return
      *   The current boolean value
      */
    inline def get(using inline frame: Frame): Boolean < IO = IO(unsafe.get())

    /** Sets to the given value.
      * @param v
      *   The new value
      */
    inline def set(v: Boolean)(using inline frame: Frame): Unit < IO = IO(unsafe.set(v))

    /** Eventually sets to the given value.
      * @param v
      *   The new value
      */
    inline def lazySet(v: Boolean)(using inline frame: Frame): Unit < IO = IO(unsafe.lazySet(v))

    /** Atomically sets to the given value and returns the old value.
      * @param v
      *   The new value
      * @return
      *   The previous value
      */
    inline def getAndSet(v: Boolean)(using inline frame: Frame): Boolean < IO = IO(unsafe.getAndSet(v))

    /** Atomically sets the value to the given updated value if the current value is equal to the expected value.
      * @param curr
      *   The expected current value
      * @param next
      *   The new value
      * @return
      *   true if successful, false otherwise
      */
    inline def cas(curr: Boolean, next: Boolean)(using inline frame: Frame): Boolean < IO = IO(unsafe.cas(curr, next))

    /** Returns a string representation of the current value.
      * @return
      *   A string representation of the atomic boolean
      */
    override def toString = unsafe.toString()

end AtomicBoolean

object AtomicBoolean:

    /** Creates a new AtomicBoolean with the given initial value.
      * @param v
      *   The initial value
      * @return
      *   A new AtomicBoolean instance
      */
    def init(v: Boolean)(using Frame): AtomicBoolean < IO = IO(AtomicBoolean(Unsafe.init(v)))

    opaque type Unsafe = j.AtomicBoolean

    object Unsafe:
        given Flat[Unsafe] = Flat.unsafe.bypass

        def init(v: Boolean)(using AllowUnsafe): Unsafe = new j.AtomicBoolean(v)

        extension (self: Unsafe)
            inline def get()(using inline allow: AllowUnsafe): Boolean                             = self.get()
            inline def set(v: Boolean)(using inline allow: AllowUnsafe): Unit                      = self.set(v)
            inline def lazySet(v: Boolean)(using inline allow: AllowUnsafe): Unit                  = self.lazySet(v)
            inline def getAndSet(v: Boolean)(using inline allow: AllowUnsafe): Boolean             = self.getAndSet(v)
            inline def cas(curr: Boolean, next: Boolean)(using inline allow: AllowUnsafe): Boolean = self.compareAndSet(curr, next)
            inline def safe: AtomicBoolean                                                         = AtomicBoolean(self)
        end extension
    end Unsafe
end AtomicBoolean

/** A wrapper for Java's AtomicReference.
  *
  * @tparam A
  *   The type of the referenced value
  */
final case class AtomicRef[A] private (unsafe: AtomicRef.Unsafe[A]) extends AnyVal:

    /** Gets the current value.
      * @return
      *   The current value
      */
    inline def get(using inline frame: Frame): A < IO = IO(unsafe.get())

    /** Sets to the given value.
      * @param v
      *   The new value
      */
    inline def set(v: A)(using inline frame: Frame): Unit < IO = IO(unsafe.set(v))

    /** Eventually sets to the given value.
      * @param v
      *   The new value
      */
    inline def lazySet(v: A)(using inline frame: Frame): Unit < IO = IO(unsafe.lazySet(v))

    /** Atomically sets to the given value and returns the old value.
      * @param v
      *   The new value
      * @return
      *   The previous value
      */
    inline def getAndSet(v: A)(using inline frame: Frame): A < IO = IO(unsafe.getAndSet(v))

    /** Atomically sets the value to the given updated value if the current value is equal to the expected value.
      * @param curr
      *   The expected current value
      * @param next
      *   The new value
      * @return
      *   true if successful, false otherwise
      */
    inline def cas(curr: A, next: A)(using inline frame: Frame): Boolean < IO = IO(unsafe.cas(curr, next))

    /** Atomically updates the current value using the given function.
      * @param f
      *   The function to apply to the current value
      */
    inline def update[S](f: A => A)(using inline frame: Frame): Unit < IO = updateAndGet(f).unit

    /** Atomically updates the current value using the given function and returns the updated value.
      * @param f
      *   The function to apply to the current value
      * @return
      *   The updated value
      */
    inline def updateAndGet[S](f: A => A)(using inline frame: Frame): A < IO = IO(unsafe.updateAndGet(f(_)))

    /** Returns a string representation of the current value.
      * @return
      *   A string representation of the atomic reference
      */
    override def toString = unsafe.toString()

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
    def init[A](v: A)(using Frame): AtomicRef[A] < IO = IO(AtomicRef(Unsafe.init(v)))

    opaque type Unsafe[A] = j.AtomicReference[A]

    object Unsafe:
        given [A]: Flat[Unsafe[A]] = Flat.unsafe.bypass

        def init[A](v: A)(using AllowUnsafe): Unsafe[A] = new j.AtomicReference(v)

        extension [A](self: Unsafe[A])
            inline def get()(using inline allow: AllowUnsafe): A                       = self.get()
            inline def set(v: A)(using inline allow: AllowUnsafe): Unit                = self.set(v)
            inline def lazySet(v: A)(using inline allow: AllowUnsafe): Unit            = self.lazySet(v)
            inline def getAndSet(v: A)(using inline allow: AllowUnsafe): A             = self.getAndSet(v)
            inline def cas(curr: A, next: A)(using inline allow: AllowUnsafe): Boolean = self.compareAndSet(curr, next)
            def update[S](f: A => A)(using AllowUnsafe): Unit                          = discard(self.updateAndGet(f(_)))
            def updateAndGet[S](f: A => A)(using AllowUnsafe): A                       = self.updateAndGet(f(_))
            inline def safe: AtomicRef[A]                                              = AtomicRef(self)
        end extension
    end Unsafe
end AtomicRef
