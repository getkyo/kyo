package kyo

import java.util.concurrent.atomic as j

/** A wrapper for Java's AtomicInteger.
  */
final case class AtomicInt private (unsafe: AtomicInt.Unsafe):

    /** Gets the current value.
      * @return
      *   The current integer value
      */
    inline def get(using inline frame: Frame): Int < IO = use(identity)

    /** Uses the current value with a transformation function.
      * @param f
      *   The function to apply to the current value
      * @return
      *   The result of applying the function to the current value
      */
    inline def use[A, S](inline f: Int => A < S)(using inline frame: Frame): A < (S & IO) =
        IO.Unsafe(f(unsafe.get()))

    /** Sets to the given value.
      * @param v
      *   The new value
      */
    inline def set(v: Int)(using inline frame: Frame): Unit < IO = IO.Unsafe(unsafe.set(v))

    /** Eventually sets to the given value.
      * @param v
      *   The new value
      */
    inline def lazySet(v: Int)(using inline frame: Frame): Unit < IO = IO.Unsafe(unsafe.lazySet(v))

    /** Atomically sets to the given value and returns the old value.
      * @param v
      *   The new value
      * @return
      *   The previous value
      */
    inline def getAndSet(v: Int)(using inline frame: Frame): Int < IO = IO.Unsafe(unsafe.getAndSet(v))

    /** Atomically sets the value to the given updated value if the current value is equal to the expected value.
      * @param curr
      *   The expected current value
      * @param next
      *   The new value
      * @return
      *   true if successful, false otherwise
      */
    inline def compareAndSet(curr: Int, next: Int)(using inline frame: Frame): Boolean < IO = IO.Unsafe(unsafe.compareAndSet(curr, next))

    /** Atomically increments the current value and returns the updated value.
      * @return
      *   The updated value
      */
    inline def incrementAndGet(using inline frame: Frame): Int < IO = IO.Unsafe(unsafe.incrementAndGet())

    /** Atomically decrements the current value and returns the updated value.
      * @return
      *   The updated value
      */
    inline def decrementAndGet(using inline frame: Frame): Int < IO = IO.Unsafe(unsafe.decrementAndGet())

    /** Atomically increments the current value and returns the old value.
      * @return
      *   The previous value
      */
    inline def getAndIncrement(using inline frame: Frame): Int < IO = IO.Unsafe(unsafe.getAndIncrement())

    /** Atomically decrements the current value and returns the old value.
      * @return
      *   The previous value
      */
    inline def getAndDecrement(using inline frame: Frame): Int < IO = IO.Unsafe(unsafe.getAndDecrement())

    /** Atomically adds the given value to the current value and returns the old value.
      * @param v
      *   The value to add
      * @return
      *   The previous value
      */
    inline def getAndAdd(v: Int)(using inline frame: Frame): Int < IO = IO.Unsafe(unsafe.getAndAdd(v))

    /** Atomically adds the given value to the current value and returns the updated value.
      * @param v
      *   The value to add
      * @return
      *   The updated value
      */
    inline def addAndGet(v: Int)(using inline frame: Frame): Int < IO = IO.Unsafe(unsafe.addAndGet(v))

    /** Atomically updates the current value using the given function and returns the old value.
      * @param f
      *   The function to apply to the current value
      * @return
      *   The previous value
      */
    inline def getAndUpdate(inline f: Int => Int)(using inline frame: Frame): Int < IO = IO.Unsafe(unsafe.getAndUpdate(f))

    /** Atomically updates the current value using the given function and returns the updated value.
      * @param f
      *   The function to apply to the current value
      * @return
      *   The updated value
      */
    inline def updateAndGet(inline f: Int => Int)(using inline frame: Frame): Int < IO = IO.Unsafe(unsafe.updateAndGet(f))

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
    def init(using Frame): AtomicInt < IO = initWith(0)(identity)

    /** Creates a new AtomicInt with the given initial value.
      * @param v
      *   The initial value
      * @return
      *   A new AtomicInt instance
      */
    def init(initialValue: Int)(using Frame): AtomicInt < IO = initWith(initialValue)(identity)

    /** Uses a new AtomicInt with initial value 0 in the given function.
      * @param f
      *   The function to apply to the new AtomicInt
      * @return
      *   The result of applying the function
      */
    inline def initWith[A, S](inline f: AtomicInt => A < S)(using inline frame: Frame): A < (S & IO) =
        initWith(0)(f)

    /** Uses a new AtomicInt with the given initial value in the function.
      * @param initialValue
      *   The initial value
      * @param f
      *   The function to apply to the new AtomicInt
      * @return
      *   The result of applying the function
      */
    inline def initWith[A, S](initialValue: Int)(inline f: AtomicInt => A < S)(using inline frame: Frame): A < (S & IO) =
        IO.Unsafe(f(AtomicInt(Unsafe.init(initialValue))))

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    opaque type Unsafe = j.AtomicInteger

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    object Unsafe:
        given Flat[Unsafe] = Flat.unsafe.bypass

        def init(using AllowUnsafe): Unsafe = init(0)

        def init(v: Int)(using AllowUnsafe): Unsafe = new j.AtomicInteger(v)

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
            inline def getAndUpdate(inline f: Int => Int)(using inline allow: AllowUnsafe): Int      = self.getAndUpdate(f(_))
            inline def updateAndGet(inline f: Int => Int)(using inline allow: AllowUnsafe): Int      = self.updateAndGet(f(_))
            inline def safe: AtomicInt                                                               = AtomicInt(self)
        end extension
    end Unsafe
end AtomicInt

/** A wrapper for Java's AtomicLong.
  */
final case class AtomicLong private (unsafe: AtomicLong.Unsafe):
    /** Gets the current value.
      * @return
      *   The current long value
      */
    inline def get(using inline frame: Frame): Long < IO = use(identity)

    /** Uses the current value with a transformation function.
      * @param f
      *   The function to apply to the current value
      * @return
      *   The result of applying the function to the current value
      */
    inline def use[A, S](inline f: Long => A < S)(using inline frame: Frame): A < (S & IO) =
        IO.Unsafe(f(unsafe.get()))

    /** Sets to the given value.
      * @param v
      *   The new value
      */
    inline def set(v: Long)(using inline frame: Frame): Unit < IO = IO.Unsafe(unsafe.set(v))

    /** Eventually sets to the given value.
      * @param v
      *   The new value
      */
    inline def lazySet(v: Long)(using inline frame: Frame): Unit < IO = IO.Unsafe(unsafe.lazySet(v))

    /** Atomically sets to the given value and returns the old value.
      * @param v
      *   The new value
      * @return
      *   The previous value
      */
    inline def getAndSet(v: Long)(using inline frame: Frame): Long < IO = IO.Unsafe(unsafe.getAndSet(v))

    /** Atomically sets the value to the given updated value if the current value is equal to the expected value.
      * @param curr
      *   The expected current value
      * @param next
      *   The new value
      * @return
      *   true if successful, false otherwise
      */
    inline def compareAndSet(curr: Long, next: Long)(using inline frame: Frame): Boolean < IO =
        IO.Unsafe(unsafe.compareAndSet(curr, next))

    /** Atomically increments the current value and returns the updated value.
      * @return
      *   The updated value
      */
    inline def incrementAndGet(using inline frame: Frame): Long < IO = IO.Unsafe(unsafe.incrementAndGet())

    /** Atomically decrements the current value and returns the updated value.
      * @return
      *   The updated value
      */
    inline def decrementAndGet(using inline frame: Frame): Long < IO = IO.Unsafe(unsafe.decrementAndGet())

    /** Atomically increments the current value and returns the old value.
      * @return
      *   The previous value
      */
    inline def getAndIncrement(using inline frame: Frame): Long < IO = IO.Unsafe(unsafe.getAndIncrement())

    /** Atomically decrements the current value and returns the old value.
      * @return
      *   The previous value
      */
    inline def getAndDecrement(using inline frame: Frame): Long < IO = IO.Unsafe(unsafe.getAndDecrement())

    /** Atomically adds the given value to the current value and returns the old value.
      * @param v
      *   The value to add
      * @return
      *   The previous value
      */
    inline def getAndAdd(v: Long)(using inline frame: Frame): Long < IO = IO.Unsafe(unsafe.getAndAdd(v))

    /** Atomically adds the given value to the current value and returns the updated value.
      * @param v
      *   The value to add
      * @return
      *   The updated value
      */
    inline def addAndGet(v: Long)(using inline frame: Frame): Long < IO = IO.Unsafe(unsafe.addAndGet(v))

    /** Atomically updates the current value using the given function and returns the old value.
      * @param f
      *   The function to apply to the current value
      * @return
      *   The previous value
      */
    inline def getAndUpdate(inline f: Long => Long)(using inline frame: Frame): Long < IO = IO.Unsafe(unsafe.getAndUpdate(f(_)))

    /** Atomically updates the current value using the given function and returns the updated value.
      * @param f
      *   The function to apply to the current value
      * @return
      *   The updated value
      */
    inline def updateAndGet(inline f: Long => Long)(using inline frame: Frame): Long < IO = IO.Unsafe(unsafe.updateAndGet(f(_)))

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
    def init(using Frame): AtomicLong < IO = init(0)

    /** Creates a new AtomicLong with the given initial value.
      * @param initialValue
      *   The initial value
      * @return
      *   A new AtomicLong instance
      */
    def init(initialValue: Long)(using Frame): AtomicLong < IO =
        initWith(initialValue)(identity)

    /** Uses a new AtomicLong with initial value 0 in the given function.
      * @param f
      *   The function to apply to the new AtomicLong
      * @return
      *   The result of applying the function
      */
    inline def initWith[A, S](inline f: AtomicLong => A < S)(using inline frame: Frame): A < (S & IO) =
        initWith(0)(f)

    /** Uses a new AtomicLong with the given initial value in the function.
      * @param initialValue
      *   The initial value
      * @param f
      *   The function to apply to the new AtomicLong
      * @return
      *   The result of applying the function
      */
    inline def initWith[A, S](initialValue: Long)(inline f: AtomicLong => A < S)(using inline frame: Frame): A < (S & IO) =
        IO.Unsafe(f(AtomicLong(Unsafe.init(initialValue))))

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    opaque type Unsafe = j.AtomicLong

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    object Unsafe:
        given Flat[Unsafe] = Flat.unsafe.bypass

        def init(using AllowUnsafe): Unsafe = init(0)

        def init(v: Long)(using AllowUnsafe): Unsafe = new j.AtomicLong(v)

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
            inline def getAndUpdate(inline f: Long => Long)(using inline allow: AllowUnsafe): Long     = self.getAndUpdate(f(_))
            inline def updateAndGet(inline f: Long => Long)(using inline allow: AllowUnsafe): Long     = self.updateAndGet(f(_))
            inline def safe: AtomicLong                                                                = AtomicLong(self)
        end extension
    end Unsafe
end AtomicLong

/** A wrapper for Java's AtomicBoolean.
  */
final case class AtomicBoolean private (unsafe: AtomicBoolean.Unsafe):
    /** Gets the current value.
      * @return
      *   The current boolean value
      */
    inline def get(using inline frame: Frame): Boolean < IO = use(identity)

    /** Uses the current value with a transformation function.
      * @param f
      *   The function to apply to the current value
      * @return
      *   The result of applying the function to the current value
      */
    inline def use[A, S](inline f: Boolean => A < S)(using inline frame: Frame): A < (S & IO) =
        IO.Unsafe(f(unsafe.get()))

    /** Sets to the given value.
      * @param v
      *   The new value
      */
    inline def set(v: Boolean)(using inline frame: Frame): Unit < IO = IO.Unsafe(unsafe.set(v))

    /** Eventually sets to the given value.
      * @param v
      *   The new value
      */
    inline def lazySet(v: Boolean)(using inline frame: Frame): Unit < IO = IO.Unsafe(unsafe.lazySet(v))

    /** Atomically sets to the given value and returns the old value.
      * @param v
      *   The new value
      * @return
      *   The previous value
      */
    inline def getAndSet(v: Boolean)(using inline frame: Frame): Boolean < IO = IO.Unsafe(unsafe.getAndSet(v))

    /** Atomically sets the value to the given updated value if the current value is equal to the expected value.
      * @param curr
      *   The expected current value
      * @param next
      *   The new value
      * @return
      *   true if successful, false otherwise
      */
    inline def compareAndSet(curr: Boolean, next: Boolean)(using inline frame: Frame): Boolean < IO =
        IO.Unsafe(unsafe.compareAndSet(curr, next))

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
    def init(using Frame): AtomicBoolean < IO = init(false)

    /** Creates a new AtomicBoolean with the given initial value.
      * @param initialValue
      *   The initial value
      * @return
      *   A new AtomicBoolean instance
      */
    def init(initialValue: Boolean)(using Frame): AtomicBoolean < IO =
        initWith(initialValue)(identity)

    /** Uses a new AtomicBoolean with initial value false in the given function.
      * @param f
      *   The function to apply to the new AtomicBoolean
      * @return
      *   The result of applying the function
      */
    inline def initWith[A, S](inline f: AtomicBoolean => A < S)(using inline frame: Frame): A < (S & IO) =
        initWith(false)(f)

    /** Uses a new AtomicBoolean with the given initial value in the function.
      * @param initialValue
      *   The initial value
      * @param f
      *   The function to apply to the new AtomicBoolean
      * @return
      *   The result of applying the function
      */
    inline def initWith[A, S](initialValue: Boolean)(inline f: AtomicBoolean => A < S)(using inline frame: Frame): A < (S & IO) =
        IO.Unsafe(f(AtomicBoolean(Unsafe.init(initialValue))))

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    opaque type Unsafe = j.AtomicBoolean

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    object Unsafe:
        given Flat[Unsafe] = Flat.unsafe.bypass

        def init(using AllowUnsafe): Unsafe = init(false)

        def init(v: Boolean)(using AllowUnsafe): Unsafe = new j.AtomicBoolean(v)

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

/** A wrapper for Java's AtomicReference.
  *
  * @tparam A
  *   The type of the referenced value
  */
final case class AtomicRef[A] private (unsafe: AtomicRef.Unsafe[A]):

    /** Gets the current value.
      * @return
      *   The current value
      */
    inline def get(using inline frame: Frame): A < IO = use(identity)

    /** Uses the current value with a transformation function.
      * @param f
      *   The function to apply to the current value
      * @return
      *   The result of applying the function to the current value
      */
    inline def use[B, S](inline f: A => B < S)(using inline frame: Frame): B < (S & IO) =
        IO.Unsafe(f(unsafe.get()))

    /** Sets to the given value.
      * @param v
      *   The new value
      */
    inline def set(v: A)(using inline frame: Frame): Unit < IO = IO.Unsafe(unsafe.set(v))

    /** Eventually sets to the given value.
      * @param v
      *   The new value
      */
    inline def lazySet(v: A)(using inline frame: Frame): Unit < IO = IO.Unsafe(unsafe.lazySet(v))

    /** Atomically sets to the given value and returns the old value.
      * @param v
      *   The new value
      * @return
      *   The previous value
      */
    inline def getAndSet(v: A)(using inline frame: Frame): A < IO = IO.Unsafe(unsafe.getAndSet(v))

    /** Atomically sets the value to the given updated value if the current value is equal to the expected value.
      * @param curr
      *   The expected current value
      * @param next
      *   The new value
      * @return
      *   true if successful, false otherwise
      */
    inline def compareAndSet(curr: A, next: A)(using inline frame: Frame): Boolean < IO = IO.Unsafe(unsafe.compareAndSet(curr, next))

    /** Atomically updates the current value using the given function and returns the old value.
      * @param f
      *   The function to apply to the current value
      * @return
      *   The previous value
      */
    inline def getAndUpdate(inline f: A => A)(using inline frame: Frame): A < IO = IO.Unsafe(unsafe.getAndUpdate(f(_)))

    /** Atomically updates the current value using the given function and returns the updated value.
      * @param f
      *   The function to apply to the current value
      * @return
      *   The updated value
      */
    inline def updateAndGet(inline f: A => A)(using inline frame: Frame): A < IO = IO.Unsafe(unsafe.updateAndGet(f(_)))

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
    def init[A](initialValue: A)(using Frame): AtomicRef[A] < IO =
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
    inline def initWith[A, B, S](initialValue: A)(inline f: AtomicRef[A] => B < S)(using inline frame: Frame): B < (S & IO) =
        IO.Unsafe(f(AtomicRef(Unsafe.init(initialValue))))

    opaque type Unsafe[A] = j.AtomicReference[A]

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    object Unsafe:
        given [A]: Flat[Unsafe[A]] = Flat.unsafe.bypass

        def init[A](v: A)(using AllowUnsafe): Unsafe[A] = new j.AtomicReference(v)

        extension [A](self: Unsafe[A])
            inline def get()(using inline allow: AllowUnsafe): A                                 = self.get()
            inline def set(v: A)(using inline allow: AllowUnsafe): Unit                          = self.set(v)
            inline def lazySet(v: A)(using inline allow: AllowUnsafe): Unit                      = self.lazySet(v)
            inline def getAndSet(v: A)(using inline allow: AllowUnsafe): A                       = self.getAndSet(v)
            inline def compareAndSet(curr: A, next: A)(using inline allow: AllowUnsafe): Boolean = self.compareAndSet(curr, next)
            inline def getAndUpdate(inline f: A => A)(using inline allow: AllowUnsafe): A        = self.getAndUpdate(f(_))
            inline def updateAndGet(inline f: A => A)(using inline allow: AllowUnsafe): A        = self.updateAndGet(f(_))
            inline def safe: AtomicRef[A]                                                        = AtomicRef(self)
        end extension
    end Unsafe
end AtomicRef
