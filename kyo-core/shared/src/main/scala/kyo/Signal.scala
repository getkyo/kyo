package kyo

import scala.annotation.implicitNotFound
import scala.annotation.nowarn
import scala.annotation.tailrec

/** A reactive value that can change over time, providing both synchronous access to its current state and asynchronous notification of
  * changes.
  *
  * Signal provides two fundamental operations:
  *   - `current`: synchronous access to the current value
  *   - `next`: asynchronous notification of the next change
  *
  * Changes can be observed through streaming operations:
  *   - `streamCurrent`: emits the current value continuously
  *   - `streamChanges`: emits only when values change
  *
  * Note that `streamChanges` may skip intermediate values if changes occur faster than they can be processed. This makes it suitable for UI
  * updates or other scenarios where processing only the latest value is acceptable, but not for cases where capturing every single change
  * is critical.
  *
  * The companion object provides these creation methods:
  *   - `Signal.initRef[A]`: creates a mutable `Signal.Ref[A]` initialized with a starting value
  *   - `Signal.initConst[A]`: creates an immutable `Signal[A]` that always returns the same value
  *   - `Signal.initRaw[A]`: (low-level API) creates a custom `Signal[A]` by directly implementing its fundamental operations, primarily
  *     intended for implementing signal combinators and custom signal types
  *
  * @tparam A
  *   The type of value contained in the signal. Must have an instance of `CanEqual[A, A]`
  */
sealed abstract class Signal[A](using CanEqual[A, A]) extends Serializable:
    self =>

    /** Retrieves the current value of the signal.
      *
      * This method provides synchronous access to the signal's current state. It's useful when you need immediate access to the value
      * without waiting for changes.
      *
      * @return
      *   The current value of type A
      */
    final def current(using Frame): A < Sync = currentWith(identity)

    /** Retrieves and transforms the current value of the signal.
      *
      * This method allows for synchronous access to the signal's current state while simultaneously applying a transformation function.
      * This is more efficient than calling `current` followed by a separate transformation as it combines both operations.
      *
      * @param f
      *   The transformation function to apply to the current value
      * @return
      *   The transformed value wrapped in combined effects S & Sync
      */
    def currentWith[B, S](f: A => B < S)(using Frame): B < (S & Sync)

    /** Waits for and returns the next value change in the signal.
      *
      * This method provides asynchronous notification of the next value change. It will wait until the signal's value changes before
      * completing.
      *
      * @return
      *   The next value of type A wrapped in an Async effect
      */
    final def next(using Frame): A < Async = nextWith(identity)

    /** Waits for the next value change and transforms it.
      *
      * This method combines waiting for the next value change with a transformation function. It's more efficient than calling `next`
      * followed by a separate transformation as it combines both operations.
      *
      * @param f
      *   The transformation function to apply to the next value
      * @return
      *   The transformed value wrapped in combined effects S & Async
      */
    def nextWith[B, S](f: A => B < S)(using Frame): B < (S & Async)

    /** Creates a new signal by applying a transformation function to this signal's values.
      *
      * This operation creates a derived signal that automatically updates whenever the source signal changes, lazily applying the given
      * transformation to each value.
      *
      * @param f
      *   The transformation function to apply to signal values
      * @return
      *   A new signal containing transformed values
      */
    @nowarn("msg=anonymous")
    inline def map[B](inline f: A => B)(using CanEqual[B, B]): Signal[B] =
        Signal.initRaw(
            currentWith = [C, S] => g => self.currentWith(a => g(f(a))),
            nextWith = [C, S] => g => self.nextWith(a => g(f(a)))
        )

    /** Creates a stream that continuously emits the current value of the signal.
      *
      * This method produces a stream that will emit the signal's current value repeatedly. It's useful for scenarios where you need to
      * continuously monitor the signal's state, even when the value hasn't changed.
      *
      * @return
      *   A stream that continuously emits the current signal value
      */
    final def streamCurrent(using Frame, Tag[Emit[Chunk[A]]]): Stream[A, Async] =
        Stream {
            Loop.forever(currentWith(a => Emit.value(Chunk(a))))
        }

    /** Creates a stream that emits only when the signal's value changes.
      *
      * This method produces a stream that emits values only when they differ from the previous value. Note that rapid changes may result in
      * some intermediate values being skipped if they occur faster than they can be processed.
      *
      * @return
      *   A stream that emits only when values change
      */
    final def streamChanges(using Frame, Tag[Emit[Chunk[A]]]): Stream[A, Async] =
        Stream(
            Loop(Maybe.empty[A]) { last =>
                currentWith { curr =>
                    if last.forall(_ != curr) then
                        Emit.valueWith(Chunk(curr))(Loop.continue(Present(curr)))
                    else
                        nextWith { a =>
                            Emit.valueWith(Chunk(a))(Loop.continue(Present(a)))
                        }
                }

            }
        )
    end streamChanges

end Signal

export Signal.SignalRef

object Signal:

    private inline val missingCanEqual =
        "Cannot create Signal because values of type '${A}' cannot be compared for equality to detect changes. Make sure there is a 'CanEqual[${A}, ${A}]' instance available."

    /** Creates a new mutable signal reference with an initial value.
      *
      * This method initializes a new `Signal.Ref[A]` that can be modified over time. The reference starts with the provided initial value
      * and can be updated using methods like `set`, `getAndSet`, etc.
      *
      * @param initial
      *   The starting value for the signal reference
      * @return
      *   A new mutable `Signal.Ref[A]`
      * @tparam A
      *   The type of value contained in the signal. Must have an instance of `CanEqual[A, A]`
      */
    def initRef[A](initial: A)(
        using
        frame: Frame,
        @implicitNotFound(missingCanEqual)
        canEqual: CanEqual[A, A]
    ): SignalRef[A] < Sync =
        initRefWith[A](initial)(identity)

    /** Creates a new mutable signal reference with an initial value and applies a transformation function.
      *
      * This method initializes a new `Signal.Ref[A]` that can be modified over time, and immediately applies a transformation function to
      * it. The reference starts with the provided initial value and the transformation is applied within the same atomic operation.
      *
      * @param initial
      *   The starting value for the signal reference
      * @param f
      *   The transformation function to apply to the newly created reference
      * @return
      *   The result of applying the transformation function
      * @tparam A
      *   The type of value contained in the signal. Must have an instance of `CanEqual[A, A]`
      * @tparam B
      *   The return type of the transformation function
      * @tparam S
      *   The effect type of the transformation function
      */
    def initRefWith[A](initial: A)[B, S](f: SignalRef[A] => B < S)(
        using
        frame: Frame,
        @implicitNotFound(missingCanEqual)
        canEqual: CanEqual[A, A]
    ): B < (S & Sync) =
        Sync.Unsafe(f(new SignalRef(SignalRef.Unsafe.init(initial))))

    /** Creates a new immutable signal with a constant value.
      *
      * This method creates a signal that always returns the same value. Unlike `Signal.Ref`, this signal cannot be modified after creation.
      * This is useful for cases where you need a signal interface but the value never changes.
      *
      * @param value
      *   The constant value for the signal
      * @return
      *   A new immutable `Signal[A]` that always returns the provided value
      * @tparam A
      *   The type of value contained in the signal. Must have an instance of `CanEqual[A, A]`
      */
    def initConst[A](value: A)(
        using
        frame: Frame,
        @implicitNotFound(missingCanEqual)
        canEqual: CanEqual[A, A]
    ): Signal[A] =
        initRaw(
            currentWith = [B, S] => f => f(value),
            nextWith = [B, S] => f => f(value)
        )

    /** Creates a new immutable signal with a constant value and applies a transformation function.
      *
      * This method creates a signal that always returns the same value and immediately applies a transformation function to it. Unlike
      * `Signal.Ref`, this signal cannot be modified after creation.
      *
      * @param value
      *   The constant value for the signal
      * @param f
      *   The transformation function to apply to the newly created signal
      * @return
      *   The result of applying the transformation function
      * @tparam A
      *   The type of value contained in the signal. Must have an instance of `CanEqual[A, A]`
      * @tparam B
      *   The return type of the transformation function
      * @tparam S
      *   The effect type of the transformation function
      */
    def initConstWith[A](value: A)[B, S](f: Signal[A] => B < S)(
        using
        frame: Frame,
        @implicitNotFound(missingCanEqual)
        canEqual: CanEqual[A, A]
    ): B < S =
        f(initConst(value))

    /** Creates a new signal by specifying its fundamental operations.
      *
      * This is a lower-level constructor that allows direct implementation of a signal's behavior through its currentWith and nextWith
      * operations. It's primarily intended for implementing signal combinators and custom signal types.
      *
      * @param currentWith
      *   The implementation of currentWith, handling synchronous value access and transformation
      * @param nextWith
      *   The implementation of nextWith, handling asynchronous value changes and transformation
      * @tparam A
      *   The type of value contained in the signal. Must have an instance of `CanEqual[A, A]`
      * @return
      *   A new signal with the specified behavior
      */
    @nowarn("msg=anonymous")
    inline def initRaw[A](
        inline currentWith: [B, S] => (A => B < S) => B < (S & Sync),
        inline nextWith: [B, S] => (A => B < S) => B < (S & Async)
    )(
        using
        frame: Frame,
        @implicitNotFound(missingCanEqual)
        canEqual: CanEqual[A, A]
    ): Signal[A] =
        _initRaw(currentWith, nextWith)

    /** Creates a new signal by specifying its fundamental operations and applies a transformation function.
      *
      * This is a lower-level constructor that allows direct implementation of a signal's behavior through its currentWith and nextWith
      * operations, and immediately applies a transformation function to the created signal. It's primarily intended for implementing signal
      * combinators and custom signal types.
      *
      * @param currentWith
      *   The implementation of currentWith, handling synchronous value access and transformation
      * @param nextWith
      *   The implementation of nextWith, handling asynchronous value changes and transformation
      * @param f
      *   The transformation function to apply to the newly created signal
      * @tparam A
      *   The type of value contained in the signal. Must have an instance of `CanEqual[A, A]`
      * @tparam B
      *   The return type of the transformation function
      * @tparam S
      *   The effect type of the transformation function
      * @return
      *   The result of applying the transformation function
      */
    @nowarn("msg=anonymous")
    inline def initRawWith[A](
        inline currentWith: [B, S] => (A => B < S) => B < (S & Sync),
        inline nextWith: [B, S] => (A => B < S) => B < (S & Async)
    )[B, S](f: Signal[A] => B < S)(
        using
        frame: Frame,
        @implicitNotFound(missingCanEqual)
        canEqual: CanEqual[A, A]
    ): B < S =
        f(initRaw(currentWith, nextWith))

    // Separated from initRaw to avoid name conflicts between parameters and Signal members
    @nowarn("msg=anonymous")
    private inline def _initRaw[A](
        inline _currentWith: [B, S] => (A => B < S) => B < (S & Sync),
        inline _nextWith: [B, S] => (A => B < S) => B < (S & Async)
    )(
        using
        frame: Frame,
        canEqual: CanEqual[A, A]
    ): Signal[A] =
        new Signal[A]:
            def currentWith[B, S](f: A => B < S)(using frame: Frame): B < (S & Sync) =
                _currentWith(f)
            def nextWith[B, S](f: A => B < S)(using frame: Frame): B < (S & Async) =
                _nextWith(f)
        end new
    end _initRaw

    /** A mutable reference implementation of Signal that allows modification of its value over time.
      *
      * This class provides methods to get, set, and modify the contained value atomically. All operations are thread-safe and will properly
      * notify observers of changes.
      *
      * @tparam A
      *   The type of value contained in the reference. Must have an instance of `CanEqual[A, A]`
      */
    final class SignalRef[A] private[Signal] (_unsafe: SignalRef.Unsafe[A])(using CanEqual[A, A]) extends Signal[A]:

        def currentWith[B, S](f: A => B < S)(using Frame) = Sync.Unsafe(f(unsafe.get()))

        def nextWith[B, S](f: A => B < S)(using Frame) = Sync.Unsafe(unsafe.next().safe.use(f))

        /** Retrieves the current value of the reference.
          *
          * This is a convenience method equivalent to `current` but with a more familiar name for reference types.
          *
          * @return
          *   The current value
          */
        def get(using Frame): A < Sync = use(identity)

        /** Retrieves and transforms the current value of the reference.
          *
          * This is a convenience method that provides synchronous access to the reference's current value while applying a transformation
          * function. It's equivalent to `currentWith` but with a more familiar name for reference types.
          *
          * @param f
          *   The transformation function to apply to the current value
          * @return
          *   The transformed value wrapped in combined effects S & Sync
          */
        inline def use[B, S](inline f: A => B < S)(using Frame): B < (S & Sync) = Sync.Unsafe(f(_unsafe.get()))

        /** Sets the reference to a new value.
          *
          * Updates the reference's value and notifies any observers if the value has changed. The previous value is returned.
          *
          * @param value
          *   The new value to set
          */
        def set(value: A)(using Frame): Unit < Sync = Sync.Unsafe(_unsafe.set(value))

        /** Updates the reference's value and returns the previous value.
          *
          * @param value
          *   The new value to set
          * @return
          *   The previous value
          */
        def getAndSet(value: A)(using Frame): A < Sync =
            Sync.Unsafe(_unsafe.getAndSet(value))

        /** Atomically sets the value to the given updated value if the current value equals the expected value.
          *
          * @param curr
          *   The expected current value
          * @param next
          *   The new value to set if the current value matches
          * @return
          *   True if successful, false otherwise
          */
        def compareAndSet(curr: A, next: A)(using Frame): Boolean < Sync =
            Sync.Unsafe(_unsafe.compareAndSet(curr, next))

        /** Atomically updates the current value using the provided function and returns the previous value.
          *
          * @param f
          *   The function to transform the current value
          * @return
          *   The previous value
          */
        def getAndUpdate(f: A => A)(using Frame): A < Sync =
            Sync.Unsafe(_unsafe.getAndUpdate(f))

        /** Atomically updates the current value using the provided function and returns the new value.
          *
          * @param f
          *   The function to transform the current value
          * @return
          *   The new value
          */
        def updateAndGet(f: A => A)(using Frame): A < Sync =
            Sync.Unsafe(_unsafe.updateAndGet(f))

        def unsafe: SignalRef.Unsafe[A] = _unsafe
    end SignalRef

    object SignalRef:

        /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details.
          *
          * The implementation uses two atomic references to manage state:
          *   - An `AtomicRef[A]` storing the current value
          *   - An `AtomicRef[Promise]` managing change notifications
          *
          * Methods like `set`, `getAndSet`, and `compareAndSet` update the current value atomically and check if it has actually changed
          * using `CanEqual`. When values differ, `onUpdate` is triggered: the current promise is atomically replaced with a new masked
          * promise, then completed with the new value. This ensures the next promise is always ready before notifying of changes.
          *
          * Promises are masked to prevent interrupt propagation between observers - if one observer is interrupted, the interruption won't
          * affect other observers waiting on the same signal. This ensures notification chains remain independent.
          */
        final class Unsafe[A] private (
            currentRef: AtomicRef.Unsafe[A],
            nextPromise: AtomicRef.Unsafe[Promise.Unsafe[Nothing, A]]
        )(using CanEqual[A, A]):

            def get()(using AllowUnsafe): A = currentRef.get()

            def set(value: A)(using AllowUnsafe): Unit =
                discard(getAndSet(value))

            def getAndSet(value: A)(using AllowUnsafe): A =
                val prev = currentRef.getAndSet(value)
                if prev != value then
                    onUpdate(value)
                prev
            end getAndSet

            def compareAndSet(curr: A, next: A)(using AllowUnsafe): Boolean =
                val r = currentRef.compareAndSet(curr, next)
                if r && curr != next then
                    discard(onUpdate(next))
                r
            end compareAndSet

            def getAndUpdate(f: A => A)(using AllowUnsafe): A =
                @tailrec
                def loop(): A =
                    val prev: A = currentRef.get()
                    val next: A = f(prev)
                    if prev == next then prev
                    else if currentRef.compareAndSet(prev, next) then
                        discard(onUpdate(next))
                        prev
                    else
                        loop()
                    end if
                end loop
                loop()
            end getAndUpdate

            def updateAndGet(f: A => A)(using AllowUnsafe): A =
                @tailrec
                def loop(): A =
                    val prev: A = currentRef.get()
                    val next: A = f(prev)
                    if prev == next then next
                    else if currentRef.compareAndSet(prev, next) then
                        discard(onUpdate(next))
                        next
                    else
                        loop()
                    end if
                end loop
                loop()
            end updateAndGet

            def next()(using AllowUnsafe): Fiber.Unsafe[Nothing, A] =
                nextPromise.get()

            private def onUpdate(value: A)(using AllowUnsafe): Unit =
                nextPromise.getAndSet(Promise.Unsafe.initMasked())
                    .completeDiscard(Result.succeed(value))

            def safe: SignalRef[A] = SignalRef(this)

        end Unsafe

        object Unsafe:

            /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details.
              */
            def init[A](initial: A)(using AllowUnsafe, CanEqual[A, A]): Unsafe[A] =
                Unsafe(
                    AtomicRef.Unsafe.init(initial),
                    AtomicRef.Unsafe.init(Promise.Unsafe.init())
                )
        end Unsafe

    end SignalRef
end Signal
