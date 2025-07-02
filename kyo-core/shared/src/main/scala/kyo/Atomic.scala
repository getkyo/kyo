package kyo

import scala.annotation.nowarn

/** A thread-safe atomic integer with effect-based operations.
  *
  * AtomicInt provides a wrapper around platform-specific atomic integer implementations (such as java.util.concurrent.atomic.AtomicInteger
  * on the JVM) with a consistent effect-based API. It supports atomic operations like get, set, increment, decrement, and compare-and-set.
  *
  * This class guarantees atomicity for all operations, making it suitable for concurrent programming scenarios where multiple threads may
  * access and modify the same value.
  *
  * @see
  *   [[LongAdder]] Alternative optimized for high contention with better write performance but slower reads
  */
final case class AtomicInt private (unsafe: AtomicInt.Unsafe):

    /** Gets the current value.
      * @return
      *   The current integer value
      */
    inline def get(using inline frame: Frame): Int < Sync = use(identity)

    /** Uses the current value with a transformation function.
      * @param f
      *   The function to apply to the current value
      * @return
      *   The result of applying the function to the current value
      */
    inline def use[A, S](inline f: Int => A < S)(using inline frame: Frame): A < (S & Sync) =
        Sync.Unsafe(f(unsafe.get()))

    /** Sets to the given value.
      * @param v
      *   The new value
      */
    inline def set(v: Int)(using inline frame: Frame): Unit < Sync = Sync.Unsafe(unsafe.set(v))

    /** Eventually sets to the given value.
      * @param v
      *   The new value
      */
    inline def lazySet(v: Int)(using inline frame: Frame): Unit < Sync = Sync.Unsafe(unsafe.lazySet(v))

    /** Atomically sets to the given value and returns the old value.
      * @param v
      *   The new value
      * @return
      *   The previous value
      */
    inline def getAndSet(v: Int)(using inline frame: Frame): Int < Sync = Sync.Unsafe(unsafe.getAndSet(v))

    /** Atomically sets the value to the given updated value if the current value is equal to the expected value.
      * @param curr
      *   The expected current value
      * @param next
      *   The new value
      * @return
      *   true if successful, false otherwise
      */
    inline def compareAndSet(curr: Int, next: Int)(using inline frame: Frame): Boolean < Sync =
        Sync.Unsafe(unsafe.compareAndSet(curr, next))

    /** Atomically increments the current value and returns the updated value.
      * @return
      *   The updated value
      */
    inline def incrementAndGet(using inline frame: Frame): Int < Sync = Sync.Unsafe(unsafe.incrementAndGet())

    /** Atomically decrements the current value and returns the updated value.
      * @return
      *   The updated value
      */
    inline def decrementAndGet(using inline frame: Frame): Int < Sync = Sync.Unsafe(unsafe.decrementAndGet())

    /** Atomically increments the current value and returns the old value.
      * @return
      *   The previous value
      */
    inline def getAndIncrement(using inline frame: Frame): Int < Sync = Sync.Unsafe(unsafe.getAndIncrement())

    /** Atomically decrements the current value and returns the old value.
      * @return
      *   The previous value
      */
    inline def getAndDecrement(using inline frame: Frame): Int < Sync = Sync.Unsafe(unsafe.getAndDecrement())

    /** Atomically adds the given value to the current value and returns the old value.
      * @param v
      *   The value to add
      * @return
      *   The previous value
      */
    inline def getAndAdd(v: Int)(using inline frame: Frame): Int < Sync = Sync.Unsafe(unsafe.getAndAdd(v))

    /** Atomically adds the given value to the current value and returns the updated value.
      * @param v
      *   The value to add
      * @return
      *   The updated value
      */
    inline def addAndGet(v: Int)(using inline frame: Frame): Int < Sync = Sync.Unsafe(unsafe.addAndGet(v))

    /** Atomically updates the current value using the given function and returns the old value.
      * @param f
      *   The function to apply to the current value
      * @return
      *   The previous value
      */
    inline def getAndUpdate(inline f: Int => Int)(using inline frame: Frame): Int < Sync = Sync.Unsafe(unsafe.getAndUpdate(f))

    /** Atomically updates the current value using the given function and returns the updated value.
      * @param f
      *   The function to apply to the current value
      * @return
      *   The updated value
      */
    inline def updateAndGet(inline f: Int => Int)(using inline frame: Frame): Int < Sync = Sync.Unsafe(unsafe.updateAndGet(f))

    /** Returns a string representation of the current value.
      * @return
      *   A string representation of the atomic integer
      */
    override def toString = unsafe.toString()

end AtomicInt

object AtomicInt:

    /** Creates a new AtomicInt with an initial value of 0.
      * @return
      *   A new AtomicInt instance initialized to 0
      */
    def init(using Frame): AtomicInt < Sync = initWith(0)(identity)

    /** Creates a new AtomicInt with the given initial value.
      * @param v
      *   The initial value
      * @return
      *   A new AtomicInt instance
      */
    def init(initialValue: Int)(using Frame): AtomicInt < Sync = initWith(initialValue)(identity)

    /** Uses a new AtomicInt with initial value 0 in the given function.
      * @param f
      *   The function to apply to the new AtomicInt
      * @return
      *   The result of applying the function
      */
    inline def initWith[A, S](inline f: AtomicInt => A < S)(using inline frame: Frame): A < (S & Sync) =
        initWith(0)(f)

    /** Uses a new AtomicInt with the given initial value in the function.
      * @param initialValue
      *   The initial value
      * @param f
      *   The function to apply to the new AtomicInt
      * @return
      *   The result of applying the function
      */
    inline def initWith[A, S](initialValue: Int)(inline f: AtomicInt => A < S)(using inline frame: Frame): A < (S & Sync) =
        Sync.Unsafe(f(AtomicInt(Unsafe.init(initialValue))))

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    opaque type Unsafe = java.util.concurrent.atomic.AtomicInteger

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    object Unsafe:
        def init(using AllowUnsafe): Unsafe = init(0)

        def init(v: Int)(using AllowUnsafe): Unsafe = new java.util.concurrent.atomic.AtomicInteger(v)

        extension (self: Unsafe)
            inline def get()(using inline allow: AllowUnsafe): Int                                   = self.get()
            inline def set(v: Int)(using inline allow: AllowUnsafe): Unit                            = self.set(v)
            inline def lazySet(v: Int)(using inline allow: AllowUnsafe): Unit                        = self.lazySet(v)
            inline def getAndSet(v: Int)(using inline allow: AllowUnsafe): Int                       = self.getAndSet(v)
            inline def compareAndSet(curr: Int, next: Int)(using inline allow: AllowUnsafe): Boolean = self.compareAndSet(curr, next)
            inline def incrementAndGet()(using inline allow: AllowUnsafe): Int                       = self.incrementAndGet()
            inline def decrementAndGet()(using inline allow: AllowUnsafe): Int                       = self.decrementAndGet()
            inline def getAndIncrement()(using inline allow: AllowUnsafe): Int                       = self.getAndIncrement()
            inline def getAndDecrement()(using inline allow: AllowUnsafe): Int                       = self.getAndDecrement()
            inline def getAndAdd(v: Int)(using inline allow: AllowUnsafe): Int                       = self.getAndAdd(v)
            inline def addAndGet(v: Int)(using inline allow: AllowUnsafe): Int                       = self.addAndGet(v)
            @nowarn("msg=anonymous")
            inline def getAndUpdate(inline f: Int => Int)(using inline allow: AllowUnsafe): Int = self.getAndUpdate(f(_))
            @nowarn("msg=anonymous")
            inline def updateAndGet(inline f: Int => Int)(using inline allow: AllowUnsafe): Int = self.updateAndGet(f(_))
            inline def safe: AtomicInt                                                          = AtomicInt(self)
        end extension
    end Unsafe
end AtomicInt

/** A thread-safe atomic long integer with effect-based operations.
  *
  * AtomicLong provides a wrapper around platform-specific atomic long implementations (such as java.util.concurrent.atomic.AtomicLong on
  * the JVM) with a consistent effect-based API. It supports atomic operations like get, set, increment, decrement, and compare-and-set.
  *
  * This class guarantees atomicity for all operations, making it suitable for concurrent programming scenarios where multiple threads may
  * access and modify the same value.
  *
  * @see
  *   [[LongAdder]] Alternative optimized for high contention with better write performance but slower reads
  */
final case class AtomicLong private (unsafe: AtomicLong.Unsafe):
    /** Gets the current value.
      * @return
      *   The current long value
      */
    inline def get(using inline frame: Frame): Long < Sync = use(identity)

    /** Uses the current value with a transformation function.
      * @param f
      *   The function to apply to the current value
      * @return
      *   The result of applying the function to the current value
      */
    inline def use[A, S](inline f: Long => A < S)(using inline frame: Frame): A < (S & Sync) =
        Sync.Unsafe(f(unsafe.get()))

    /** Sets to the given value.
      * @param v
      *   The new value
      */
    inline def set(v: Long)(using inline frame: Frame): Unit < Sync = Sync.Unsafe(unsafe.set(v))

    /** Eventually sets to the given value.
      * @param v
      *   The new value
      */
    inline def lazySet(v: Long)(using inline frame: Frame): Unit < Sync = Sync.Unsafe(unsafe.lazySet(v))

    /** Atomically sets to the given value and returns the old value.
      * @param v
      *   The new value
      * @return
      *   The previous value
      */
    inline def getAndSet(v: Long)(using inline frame: Frame): Long < Sync = Sync.Unsafe(unsafe.getAndSet(v))

    /** Atomically sets the value to the given updated value if the current value is equal to the expected value.
      * @param curr
      *   The expected current value
      * @param next
      *   The new value
      * @return
      *   true if successful, false otherwise
      */
    inline def compareAndSet(curr: Long, next: Long)(using inline frame: Frame): Boolean < Sync =
        Sync.Unsafe(unsafe.compareAndSet(curr, next))

    /** Atomically increments the current value and returns the updated value.
      * @return
      *   The updated value
      */
    inline def incrementAndGet(using inline frame: Frame): Long < Sync = Sync.Unsafe(unsafe.incrementAndGet())

    /** Atomically decrements the current value and returns the updated value.
      * @return
      *   The updated value
      */
    inline def decrementAndGet(using inline frame: Frame): Long < Sync = Sync.Unsafe(unsafe.decrementAndGet())

    /** Atomically increments the current value and returns the old value.
      * @return
      *   The previous value
      */
    inline def getAndIncrement(using inline frame: Frame): Long < Sync = Sync.Unsafe(unsafe.getAndIncrement())

    /** Atomically decrements the current value and returns the old value.
      * @return
      *   The previous value
      */
    inline def getAndDecrement(using inline frame: Frame): Long < Sync = Sync.Unsafe(unsafe.getAndDecrement())

    /** Atomically adds the given value to the current value and returns the old value.
      * @param v
      *   The value to add
      * @return
      *   The previous value
      */
    inline def getAndAdd(v: Long)(using inline frame: Frame): Long < Sync = Sync.Unsafe(unsafe.getAndAdd(v))

    /** Atomically adds the given value to the current value and returns the updated value.
      * @param v
      *   The value to add
      * @return
      *   The updated value
      */
    inline def addAndGet(v: Long)(using inline frame: Frame): Long < Sync = Sync.Unsafe(unsafe.addAndGet(v))

    /** Atomically updates the current value using the given function and returns the old value.
      * @param f
      *   The function to apply to the current value
      * @return
      *   The previous value
      */
    inline def getAndUpdate(inline f: Long => Long)(using inline frame: Frame): Long < Sync = Sync.Unsafe(unsafe.getAndUpdate(f(_)))

    /** Atomically updates the current value using the given function and returns the updated value.
      * @param f
      *   The function to apply to the current value
      * @return
      *   The updated value
      */
    inline def updateAndGet(inline f: Long => Long)(using inline frame: Frame): Long < Sync = Sync.Unsafe(unsafe.updateAndGet(f(_)))

    /** Returns a string representation of the current value.
      * @return
      *   A string representation of the atomic long
      */
    override def toString = unsafe.toString()

end AtomicLong

object AtomicLong:

    /** Creates a new AtomicLong with an initial value of 0.
      * @return
      *   A new AtomicLong instance initialized to 0
      */
    def init(using Frame): AtomicLong < Sync = init(0)

    /** Creates a new AtomicLong with the given initial value.
      * @param initialValue
      *   The initial value
      * @return
      *   A new AtomicLong instance
      */
    def init(initialValue: Long)(using Frame): AtomicLong < Sync =
        initWith(initialValue)(identity)

    /** Uses a new AtomicLong with initial value 0 in the given function.
      * @param f
      *   The function to apply to the new AtomicLong
      * @return
      *   The result of applying the function
      */
    inline def initWith[A, S](inline f: AtomicLong => A < S)(using inline frame: Frame): A < (S & Sync) =
        initWith(0)(f)

    /** Uses a new AtomicLong with the given initial value in the function.
      * @param initialValue
      *   The initial value
      * @param f
      *   The function to apply to the new AtomicLong
      * @return
      *   The result of applying the function
      */
    inline def initWith[A, S](initialValue: Long)(inline f: AtomicLong => A < S)(using inline frame: Frame): A < (S & Sync) =
        Sync.Unsafe(f(AtomicLong(Unsafe.init(initialValue))))

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    opaque type Unsafe = java.util.concurrent.atomic.AtomicLong

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    object Unsafe:
        def init(using AllowUnsafe): Unsafe = init(0)

        def init(v: Long)(using AllowUnsafe): Unsafe = new java.util.concurrent.atomic.AtomicLong(v)

        extension (self: Unsafe)
            inline def get()(using inline allow: AllowUnsafe): Long                                    = self.get()
            inline def set(v: Long)(using inline allow: AllowUnsafe): Unit                             = self.set(v)
            inline def lazySet(v: Long)(using inline allow: AllowUnsafe): Unit                         = self.lazySet(v)
            inline def getAndSet(v: Long)(using inline allow: AllowUnsafe): Long                       = self.getAndSet(v)
            inline def compareAndSet(curr: Long, next: Long)(using inline allow: AllowUnsafe): Boolean = self.compareAndSet(curr, next)
            inline def incrementAndGet()(using inline allow: AllowUnsafe): Long                        = self.incrementAndGet()
            inline def decrementAndGet()(using inline allow: AllowUnsafe): Long                        = self.decrementAndGet()
            inline def getAndIncrement()(using inline allow: AllowUnsafe): Long                        = self.getAndIncrement()
            inline def getAndDecrement()(using inline allow: AllowUnsafe): Long                        = self.getAndDecrement()
            inline def getAndAdd(v: Long)(using inline allow: AllowUnsafe): Long                       = self.getAndAdd(v)
            inline def addAndGet(v: Long)(using inline allow: AllowUnsafe): Long                       = self.addAndGet(v)
            @nowarn("msg=anonymous")
            inline def getAndUpdate(inline f: Long => Long)(using inline allow: AllowUnsafe): Long = self.getAndUpdate(f(_))
            @nowarn("msg=anonymous")
            inline def updateAndGet(inline f: Long => Long)(using inline allow: AllowUnsafe): Long = self.updateAndGet(f(_))
            inline def safe: AtomicLong                                                            = AtomicLong(self)
        end extension
    end Unsafe
end AtomicLong

/** A thread-safe atomic boolean with effect-based operations.
  *
  * AtomicBoolean provides a wrapper around platform-specific atomic boolean implementations (such as
  * java.util.concurrent.atomic.AtomicBoolean on the JVM) with a consistent effect-based API. It supports atomic operations like get, set,
  * and compare-and-set.
  *
  * This class guarantees atomicity for all operations, making it suitable for concurrent programming scenarios where multiple threads may
  * access and modify the same boolean value.
  */
final case class AtomicBoolean private (unsafe: AtomicBoolean.Unsafe):
    /** Gets the current value.
      * @return
      *   The current boolean value
      */
    inline def get(using inline frame: Frame): Boolean < Sync = use(identity)

    /** Uses the current value with a transformation function.
      * @param f
      *   The function to apply to the current value
      * @return
      *   The result of applying the function to the current value
      */
    inline def use[A, S](inline f: Boolean => A < S)(using inline frame: Frame): A < (S & Sync) =
        Sync.Unsafe(f(unsafe.get()))

    /** Sets to the given value.
      * @param v
      *   The new value
      */
    inline def set(v: Boolean)(using inline frame: Frame): Unit < Sync = Sync.Unsafe(unsafe.set(v))

    /** Eventually sets to the given value.
      * @param v
      *   The new value
      */
    inline def lazySet(v: Boolean)(using inline frame: Frame): Unit < Sync = Sync.Unsafe(unsafe.lazySet(v))

    /** Atomically sets to the given value and returns the old value.
      * @param v
      *   The new value
      * @return
      *   The previous value
      */
    inline def getAndSet(v: Boolean)(using inline frame: Frame): Boolean < Sync = Sync.Unsafe(unsafe.getAndSet(v))

    /** Atomically sets the value to the given updated value if the current value is equal to the expected value.
      * @param curr
      *   The expected current value
      * @param next
      *   The new value
      * @return
      *   true if successful, false otherwise
      */
    inline def compareAndSet(curr: Boolean, next: Boolean)(using inline frame: Frame): Boolean < Sync =
        Sync.Unsafe(unsafe.compareAndSet(curr, next))

    /** Returns a string representation of the current value.
      * @return
      *   A string representation of the atomic boolean
      */
    override def toString = unsafe.toString()

end AtomicBoolean

object AtomicBoolean:

    /** Creates a new AtomicBoolean with an initial value of false.
      * @return
      *   A new AtomicBoolean instance initialized to false
      */
    def init(using Frame): AtomicBoolean < Sync = init(false)

    /** Creates a new AtomicBoolean with the given initial value.
      * @param initialValue
      *   The initial value
      * @return
      *   A new AtomicBoolean instance
      */
    def init(initialValue: Boolean)(using Frame): AtomicBoolean < Sync =
        initWith(initialValue)(identity)

    /** Uses a new AtomicBoolean with initial value false in the given function.
      * @param f
      *   The function to apply to the new AtomicBoolean
      * @return
      *   The result of applying the function
      */
    inline def initWith[A, S](inline f: AtomicBoolean => A < S)(using inline frame: Frame): A < (S & Sync) =
        initWith(false)(f)

    /** Uses a new AtomicBoolean with the given initial value in the function.
      * @param initialValue
      *   The initial value
      * @param f
      *   The function to apply to the new AtomicBoolean
      * @return
      *   The result of applying the function
      */
    inline def initWith[A, S](initialValue: Boolean)(inline f: AtomicBoolean => A < S)(using inline frame: Frame): A < (S & Sync) =
        Sync.Unsafe(f(AtomicBoolean(Unsafe.init(initialValue))))

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    opaque type Unsafe = java.util.concurrent.atomic.AtomicBoolean

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    object Unsafe:
        def init(using AllowUnsafe): Unsafe = init(false)

        def init(v: Boolean)(using AllowUnsafe): Unsafe = new java.util.concurrent.atomic.AtomicBoolean(v)

        extension (self: Unsafe)
            inline def get()(using inline allow: AllowUnsafe): Boolean                 = self.get()
            inline def set(v: Boolean)(using inline allow: AllowUnsafe): Unit          = self.set(v)
            inline def lazySet(v: Boolean)(using inline allow: AllowUnsafe): Unit      = self.lazySet(v)
            inline def getAndSet(v: Boolean)(using inline allow: AllowUnsafe): Boolean = self.getAndSet(v)
            inline def compareAndSet(curr: Boolean, next: Boolean)(using inline allow: AllowUnsafe): Boolean =
                self.compareAndSet(curr, next)
            inline def safe: AtomicBoolean = AtomicBoolean(self)
        end extension
    end Unsafe
end AtomicBoolean

/** A thread-safe atomic reference with effect-based operations.
  *
  * AtomicRef provides a wrapper around platform-specific atomic reference implementations (such as
  * java.util.concurrent.atomic.AtomicReference on the JVM) with a consistent effect-based API. It supports atomic operations like get, set,
  * and compare-and-set.
  *
  * This class guarantees atomicity for all operations, making it suitable for concurrent programming scenarios where multiple threads may
  * access and modify the same reference.
  *
  * @tparam A
  *   The type of the referenced value
  */
final case class AtomicRef[A] private (unsafe: AtomicRef.Unsafe[A]):

    /** Gets the current value.
      * @return
      *   The current value
      */
    inline def get(using inline frame: Frame): A < Sync = use(identity)

    /** Uses the current value with a transformation function.
      * @param f
      *   The function to apply to the current value
      * @return
      *   The result of applying the function to the current value
      */
    inline def use[B, S](inline f: A => B < S)(using inline frame: Frame): B < (S & Sync) =
        Sync.Unsafe(f(unsafe.get()))

    /** Sets to the given value.
      * @param v
      *   The new value
      */
    inline def set(v: A)(using inline frame: Frame): Unit < Sync = Sync.Unsafe(unsafe.set(v))

    /** Eventually sets to the given value.
      * @param v
      *   The new value
      */
    inline def lazySet(v: A)(using inline frame: Frame): Unit < Sync = Sync.Unsafe(unsafe.lazySet(v))

    /** Atomically sets to the given value and returns the old value.
      * @param v
      *   The new value
      * @return
      *   The previous value
      */
    inline def getAndSet(v: A)(using inline frame: Frame): A < Sync = Sync.Unsafe(unsafe.getAndSet(v))

    /** Atomically sets the value to the given updated value if the current value is equal to the expected value.
      * @param curr
      *   The expected current value
      * @param next
      *   The new value
      * @return
      *   true if successful, false otherwise
      */
    inline def compareAndSet(curr: A, next: A)(using inline frame: Frame): Boolean < Sync = Sync.Unsafe(unsafe.compareAndSet(curr, next))

    /** Atomically updates the current value using the given function and returns the old value.
      * @param f
      *   The function to apply to the current value
      * @return
      *   The previous value
      */
    inline def getAndUpdate(inline f: A => A)(using inline frame: Frame): A < Sync = Sync.Unsafe(unsafe.getAndUpdate(f(_)))

    /** Atomically updates the current value using the given function and returns the updated value.
      * @param f
      *   The function to apply to the current value
      * @return
      *   The updated value
      */
    inline def updateAndGet(inline f: A => A)(using inline frame: Frame): A < Sync = Sync.Unsafe(unsafe.updateAndGet(f(_)))

    /** Returns a string representation of the current value.
      * @return
      *   A string representation of the atomic reference
      */
    override def toString = unsafe.toString()

end AtomicRef

object AtomicRef:

    /** Creates a new AtomicRef with the given initial value.
      * @param initialValue
      *   The initial value
      * @return
      *   A new AtomicRef instance
      * @tparam A
      *   The type of the referenced value
      */
    def init[A](initialValue: A)(using Frame): AtomicRef[A] < Sync =
        initWith(initialValue)(identity)

    /** Uses a new AtomicRef with the given initial value in the function.
      * @param initialValue
      *   The initial value
      * @param f
      *   The function to apply to the new AtomicRef
      * @return
      *   The result of applying the function
      * @tparam A
      *   The type of the referenced value
      */
    inline def initWith[A, B, S](initialValue: A)(inline f: AtomicRef[A] => B < S)(using inline frame: Frame): B < (S & Sync) =
        Sync.Unsafe(f(AtomicRef(Unsafe.init(initialValue))))

    opaque type Unsafe[A] = java.util.concurrent.atomic.AtomicReference[A]

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    object Unsafe:
        def init[A](v: A)(using AllowUnsafe): Unsafe[A] = new java.util.concurrent.atomic.AtomicReference(v)

        extension [A](self: Unsafe[A])
            inline def get()(using inline allow: AllowUnsafe): A                                 = self.get()
            inline def set(v: A)(using inline allow: AllowUnsafe): Unit                          = self.set(v)
            inline def lazySet(v: A)(using inline allow: AllowUnsafe): Unit                      = self.lazySet(v)
            inline def getAndSet(v: A)(using inline allow: AllowUnsafe): A                       = self.getAndSet(v)
            inline def compareAndSet(curr: A, next: A)(using inline allow: AllowUnsafe): Boolean = self.compareAndSet(curr, next)
            @nowarn("msg=anonymous")
            inline def getAndUpdate(inline f: A => A)(using inline allow: AllowUnsafe): A = self.getAndUpdate(f(_))
            @nowarn("msg=anonymous")
            inline def updateAndGet(inline f: A => A)(using inline allow: AllowUnsafe): A = self.updateAndGet(f(_))
            inline def safe: AtomicRef[A]                                                 = AtomicRef(self)
        end extension
    end Unsafe
end AtomicRef
