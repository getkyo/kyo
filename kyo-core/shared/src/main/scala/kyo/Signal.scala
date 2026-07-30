package kyo

import scala.annotation.implicitNotFound
import scala.annotation.nowarn
import scala.annotation.tailrec

/** A reactive value that can change over time, providing both synchronous access to its current state and asynchronous notification of
  * changes.
  *
  * Signal provides two fundamental operations:
  *
  *   - `current`: synchronous access to the current value
  *   - `next`: asynchronous notification of the next change
  *
  * Changes can be observed through streaming operations:
  *
  *   - `streamCurrent`: emits the current value continuously
  *   - `streamChanges`: emits only when values change
  *
  * Note that `streamChanges` may skip intermediate values if changes occur faster than they can be processed. This makes it suitable for UI
  * updates or other scenarios where processing only the latest value is acceptable, but not for cases where capturing every single change
  * is critical.
  *
  * The companion object provides these creation methods:
  *
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

    /** Runs `f` for the current value and for every subsequent change, each inside a fresh [[Scope]] that closes when the next value arrives.
      *
      * This is a live subscription: `f` runs once for the current value, then again on every change, and the computation runs forever (fork it
      * and interrupt to stop). For each value, `f(value)` runs inside a new `Scope`: `f` sets the value up (renders, forks scoped children via
      * `Fiber.init`) and returns, then `observe` holds that per-value `Scope` open until the next change, at which point it closes the prior
      * value's `Scope` (interrupting whatever `f` forked, cascading to their descendants) before opening a fresh one for the new value. On
      * interrupt, the current value's `Scope` closes too. This is switch-with-resources: the inner lifetime is bounded by the outer value,
      * structurally, with no manual cleanup. A value that forks nothing just opens and closes an empty scope. Because each value's `Scope` is
      * closed before the next `f` runs, at most one value's children are alive at a time and no waiter or fiber accumulates across changes.
      *
      * It is designed never to permanently miss the latest value, even under a write that races the observation, and never to tear a
      * still-current value's `Scope` down on an idle timer. Delivery comes in two tiers. A [[SignalRef]] (and a `map` chain rooted in one)
      * observes exactly: a version-validated register/validate/await protocol makes every change wake the observer immediately, with no
      * repair timer armed at all (see the `SignalRef.observe` override). Combinator-derived signals (`zip`, `combineLatest`, `switchMap`,
      * `zipAll`, `combineLatestAll`, custom `initRaw`) use the repairing loop: it reads `current`, runs `f`, then re-arms a
      * `nextWith`/`Async.sleep(repairInterval)` race that holds the value's `Scope` open until the next change. A write that lands in the
      * window between reading `current` and registering `nextWith` is missed by the immediate wakeup and reconciled when the repair timer
      * next fires and re-reads `current` (the hold re-waits on a still-current value, so a repair timer never closes its `Scope`). So the
      * final value is always delivered: immediately in the common case, and within `repairInterval` in the worst case when a write races
      * that window on a derived signal. Correctness never depends on `repairInterval` ; only the worst-case reconciliation latency does.
      * This variant uses [[Signal.defaultRepairInterval]].
      *
      * @param f
      *   The per-value setup, run inside a fresh `Scope`; it may fork scoped children (`Fiber.init`) and should return once setup is done,
      *   leaving `observe` to hold the `Scope` until the next value
      * @see
      *   [[switchMap]] for the resource-free value-level switch
      */
    final def observe[S](f: A => Unit < (S & Async & Scope))(using Frame): Unit < (S & Async) =
        observe(Signal.defaultRepairInterval)(f)

    /** Like [[observe]] but with an explicit reconciliation interval.
      *
      * The loop is the repairing form: it tracks the last observed value and, while the current value is unchanged, re-arms a
      * `nextWith`/`Async.sleep(repairInterval)` race so a missed wakeup is reconciled within `repairInterval` WITHOUT tearing the still-current
      * value's `Scope` down. The hold loops until `current` actually differs, so a repair timer firing on a still-current value re-waits and
      * keeps the per-value `Scope` open.
      *
      * @param repairInterval
      *   How often a parked observation re-reads `current` to reconcile a missed wakeup on the repair path; ignored by exact observers
      *   (`SignalRef` and `map` chains rooted in one), which never miss a wakeup
      * @param f
      *   The per-value setup, run inside a fresh `Scope`
      */
    final def observe[S](repairInterval: Duration)(f: A => Unit < (S & Async & Scope))(using Frame): Unit < (S & Async) =
        observe(Absent, repairInterval)(f)

    /** Like [[observe]] but seeded: `f` is skipped while the current value still equals `baseline`.
      *
      * With `Absent` this is exactly [[observe]]. With `Present(v)` the loop treats `v` as the last observed value: the initial emission is
      * skipped when the current value still equals it, and the first differing value is delivered as usual. This is the primitive behind
      * [[observeChanges]] and behind callers that already processed the current value (e.g. painted it) and only want what changed since.
      *
      * This is the overridable observation primitive: [[SignalRef]] replaces the repairing loop with an exact register/validate/await
      * protocol (see there), and `map` delegates to its source's loop.
      *
      * @param baseline
      *   The value already processed by the caller: `f` is not run while `current` still equals it
      * @param repairInterval
      *   How often a parked observation re-reads `current` to reconcile a missed wakeup on the repair path
      * @param f
      *   The per-value setup, run inside a fresh `Scope`
      */
    def observe[S](baseline: Maybe[A], repairInterval: Duration)(f: A => Unit < (S & Async & Scope))(using Frame): Unit < (S & Async) =
        // Repairing default. Each value runs inside a fresh `Scope.run`; the inner `holdUntilChanged` loops until `current`
        // differs from the value `f` set up, so an idle repair timer NEVER closes a still-current value's scope. The scope
        // closes (releasing what `f` forked) only when the value actually changes; then the outer loop re-reads `current`.
        def holdUntilChanged(cur: A): Unit < (S & Async) =
            Async.race(Seq(nextWith(_ => ()), Async.sleep(repairInterval))).andThen {
                currentWith(c => if c == cur then holdUntilChanged(cur) else (): Unit < (S & Async))
            }
        def loop(last: Maybe[A]): Unit < (S & Async) =
            currentWith { cur =>
                if last.exists(_ == cur) then
                    Async.race(Seq(nextWith(_ => ()), Async.sleep(repairInterval))).andThen(loop(last))
                else
                    Scope.run(f(cur).andThen(holdUntilChanged(cur))).andThen(loop(Present(cur)))
            }
        loop(baseline)
    end observe

    /** Runs `f` for every change AFTER subscription, skipping the initial current value.
      *
      * Identical to [[observe]] except that the value current at subscription time is not delivered: the first emission is the first value
      * that differs from it. Same per-value [[Scope]] semantics and the same delivery tiers. This variant uses
      * [[Signal.defaultRepairInterval]].
      */
    final def observeChanges[S](f: A => Unit < (S & Async & Scope))(using Frame): Unit < (S & Async) =
        observeChanges(Signal.defaultRepairInterval)(f)

    /** Like [[observeChanges]] but with an explicit reconciliation interval. */
    final def observeChanges[S](repairInterval: Duration)(f: A => Unit < (S & Async & Scope))(using Frame): Unit < (S & Async) =
        currentWith(c => observe(Present(c), repairInterval)(f))

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
    inline def map[B](inline f: A => B)(using CanEqual[B, B], Frame): Signal[B] =
        Signal._initRawF(
            [C, S] => g => self.currentWith(a => g(f(a))),
            [C, S] => g => self.nextWith(a => g(f(a))),
            [S] =>
                (baseline, ri, g) =>
                    baseline match
                        case Present(b0) =>
                            // Translate the B-space baseline into an A-space seed by sampling the source: skip
                            // while the source still holds a value whose image equals the baseline. A source
                            // change whose image happens to equal the baseline re-emits it, consistent with a
                            // mapped observe already re-emitting equal images for distinct source values.
                            self.currentWith { a0 =>
                                if f(a0) == b0 then self.observe[S](Present(a0), ri)(a => g(f(a)))
                                else self.observe[S](Absent, ri)(a => g(f(a)))
                            }
                        case _ => self.observe[S](Absent, ri)(a => g(f(a)))
        )

    /** Dynamically switches to an inner signal based on the current value.
      *
      * When the outer signal changes, switches to the new inner signal produced by `f`. When the current inner signal changes, propagates
      * that change. This is switchMap semantics (no monad laws): the previous inner is implicitly dropped on outer change. The caller
      * re-arms via `nextWith` in a loop matching the `streamChanges` driver pattern.
      *
      * Note: like `streamChanges`, may skip intermediate values if changes occur faster than they can be processed. The combinator's own
      * await/re-read window applies here (a write landing between the wakeup and the re-read is coalesced); observation over it is
      * reconciled within the repair interval.
      *
      * @param f
      *   The function that produces an inner signal from the current value
      * @return
      *   A new signal that tracks the current inner signal
      */
    @nowarn("msg=anonymous")
    inline def switchMap[B](inline f: A => Signal[B])(using CanEqual[B, B], Frame): Signal[B] =
        Signal.initRaw(
            currentWith = [C, S] => g => self.currentWith(a => f(a).currentWith(g)),
            nextWith = [C, S] =>
                g =>
                    self.currentWith { a =>
                        val inner = f(a)
                        Signal.awaitAny(Seq(self, inner))
                            .andThen(self.currentWith { a2 =>
                                (if a2 == a then inner else f(a2)).currentWith(g)
                            })
                }
        )

    /** Pairs this signal with another, waiting for both to change before emitting.
      *
      * @param other
      *   The signal to pair with
      * @return
      *   A signal of pairs that updates only when both inputs have changed since the last emit
      */
    @nowarn("msg=anonymous")
    inline def zip[B](other: Signal[B])(using CanEqual[(A, B), (A, B)], Frame): Signal[(A, B)] =
        Signal.initRaw(
            currentWith = [C, S] => g => self.currentWith(a => other.currentWith(b => g((a, b)))),
            nextWith = [C, S] => g => Async.zip(self.next, other.next).andThen(self.currentWith(a => other.currentWith(b => g((a, b)))))
        )

    /** Pairs this signal with another, emitting when either changes (Rx combineLatest semantics).
      *
      * Note: like `streamChanges`, may skip intermediate values if changes occur faster than they can be processed.
      *
      * @param other
      *   The signal to pair with
      * @return
      *   A signal of pairs that updates when either input changes
      */
    @nowarn("msg=anonymous")
    inline def combineLatest[B](other: Signal[B])(using CanEqual[(A, B), (A, B)], Frame): Signal[(A, B)] =
        Signal.initRaw(
            currentWith = [C, S] => g => self.currentWith(a => other.currentWith(b => g((a, b)))),
            nextWith = [C, S] => g => Signal.awaitAny(Seq(self, other)).andThen(self.currentWith(a => other.currentWith(b => g((a, b)))))
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
      * The value current at subscription time is NOT emitted: the first element is the first value that differs from it. Built on
      * [[observeChanges]], so it inherits its delivery tiers: exact on a [[SignalRef]] (and `map` chains rooted in one), reconciled within
      * `repairInterval` on combinator-derived signals. Rapid changes may still coalesce (the stream is level-based, not a queue). This
      * variant uses [[Signal.defaultRepairInterval]].
      *
      * @return
      *   A stream that emits only when the value changes, skipping the subscription-time value
      */
    final def streamChanges(using Frame, Tag[Emit[Chunk[A]]]): Stream[A, Async] =
        streamChanges(Signal.defaultRepairInterval)

    /** Like [[streamChanges]] but with an explicit reconciliation interval. */
    final def streamChanges(repairInterval: Duration)(using Frame, Tag[Emit[Chunk[A]]]): Stream[A, Async] =
        Stream(observeChanges[Emit[Chunk[A]]](repairInterval)(a => Emit.value(Chunk(a))))

end Signal

export Signal.SignalRef

object Signal:

    /** Default reconciliation interval used by [[Signal.observe]] when none is given.
      *
      * It bounds how soon a missed wakeup is reconciled by re-reading `current`: a write that races the observation's
      * read/register window is delivered within this interval. Real changes are otherwise immediate, so this can be
      * generous; it exists to bound that rare race, not to drive normal updates. Exact observers ([[SignalRef]] and
      * `map` chains rooted in one) never miss a wakeup and ignore it entirely, arming no timer at all.
      */
    val defaultRepairInterval: Duration = 1.second

    /** Waits for any of the given signals to change.
      *
      * @param signals
      *   The signals to watch
      */
    def awaitAny(signals: Seq[Signal[?]])(using Frame): Unit < Async =
        if signals.isEmpty then ()
        else Async.race(signals.map(_.next)).unit

    /** Zips a sequence of signals, waiting for all to change before emitting.
      *
      * @param signals
      *   The signals to zip
      * @return
      *   A signal of Chunk that updates when all inputs have changed
      */
    @nowarn("msg=anonymous")
    inline def zipAll[A](signals: Seq[Signal[A]])(
        using
        Frame,
        CanEqual[A, A],
        CanEqual[Chunk[A], Chunk[A]]
    ): Signal[Chunk[A]] =
        signals.size match
            case 0 => initConst(Chunk.empty[A])
            case 1 => signals.head.map(Chunk(_))
            case n =>
                val sigs = Chunk.from(signals, n)
                initRaw(
                    currentWith = [B, S] => f => Kyo.foreach(sigs)(_.current).map(f),
                    nextWith = [B, S] => f => Async.foreachDiscard(sigs, sigs.size)(_.next).andThen(Kyo.foreach(sigs)(_.current).map(f))
                )

    /** Zips a sequence of signals, emitting when any changes.
      *
      * Note: like `streamChanges`, may skip intermediate values if changes occur faster than they can be processed.
      *
      * @param signals
      *   The signals to zip
      * @return
      *   A signal of Chunk that updates when any input changes
      */
    @nowarn("msg=anonymous")
    inline def combineLatestAll[A](signals: Seq[Signal[A]])(using Frame, CanEqual[A, A]): Signal[Chunk[A]] =
        signals.size match
            case 0 => initConst(Chunk.empty[A])
            case 1 => signals.head.map(Chunk(_))
            case n =>
                val sigs = Chunk.from(signals, n)
                initRaw(
                    currentWith = [C, S] => g => Kyo.foreach(sigs)(_.current).map(g),
                    nextWith = [C, S] => g => awaitAny(sigs).andThen(Kyo.foreach(sigs)(_.current).map(g))
                )

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
        Sync.Unsafe.defer(f(new SignalRef(SignalRef.Unsafe.init(initial))))

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

    // Like _initRaw but also supplies `observe`, letting a structural combinator delegate observation to its source's
    // `observe` loop (applying its transform to each value) rather than running a second repair loop over its own
    // `currentWith`/`nextWith`. `map` uses this so a `map`-over-leaf chain observes through one loop rooted at the leaf.
    @nowarn("msg=anonymous")
    private inline def _initRawF[A](
        inline _currentWith: [B, S] => (A => B < S) => B < (S & Sync),
        inline _nextWith: [B, S] => (A => B < S) => B < (S & Async),
        inline _observe: [S] => (Maybe[A], Duration, A => Unit < (S & Async & Scope)) => Unit < (S & Async)
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
            override def observe[S](baseline: Maybe[A], repairInterval: Duration)(f: A => Unit < (S & Async & Scope))(using
                frame: Frame
            ): Unit < (S & Async) =
                _observe(baseline, repairInterval, f)
        end new
    end _initRawF

    /** A mutable reference implementation of Signal that allows modification of its value over time.
      *
      * This class provides methods to get, set, and modify the contained value atomically. All operations are thread-safe and will properly
      * notify observers of changes.
      *
      * @tparam A
      *   The type of value contained in the reference. Must have an instance of `CanEqual[A, A]`
      */
    final class SignalRef[A] private[Signal] (_unsafe: SignalRef.Unsafe[A])(using CanEqual[A, A]) extends Signal[A]:

        def currentWith[B, S](f: A => B < S)(using Frame) = Sync.Unsafe.defer(f(unsafe.get()))

        def nextWith[B, S](f: A => B < S)(using Frame) = Sync.Unsafe.defer(unsafe.next().safe.use(f))

        // Exact, lossless observation via a version-validated register/validate/await protocol.
        //
        // Writer order (see Unsafe.onUpdate): value write, version increment, promise swap+complete. The observer
        // reads the version BEFORE the value, runs `f`, and re-arms through `nextSince(v0)`: capture the
        // next-change promise, re-check the version, and only park when it is unchanged.
        //   - If the validate step sees a newer version, `nextSince` returns immediately and the re-read observes
        //     the new value (the value write happens before the increment).
        //   - Otherwise the capture happened before that write's promise swap, and the swap completes exactly the
        //     captured promise. Either way no change is stranded, with no repair timer armed at all (so an idle
        //     parked observer costs nothing and holds exactly one waiter).
        //   - Version-before-value is load-bearing: reading the value first could pair a fresh value with a stale
        //     version and validate cleanly against a completion nobody held.
        // Coalescing stays allowed (observation is level-based): a change-and-revert during `f` wakes the
        // observer, re-reads an unchanged value, and re-arms.
        //
        // Scala Native note: earlier revisions of this override were blamed for kyo-browser native suite
        // SIGSEGVs ("miscompiles", "heap corruption"). Root-caused 2026-07-31: the crashes never involved
        // Signal at all (the class is unreachable from that binary; zero Signal symbols in the linked
        // artifact). The real fault is a Scala Native 0.5.12 libunwind null read (CFI_Parser::decodeFDE
        // during Throwable.fillInStackTrace) on certain deep continuation stacks, triggered whenever an
        // exception is constructed there, and layout-sensitive enough that unrelated source changes (like
        // adding a method here) flipped it on and off. The trigger on kyo's side was an exception thrown
        // per non-numeric token in SchemaSerializer.DiscriminatorReader.matchField, since made
        // exception-free. If the kyo-browser native suite segfaults after touching this file, suspect
        // exception construction on deep stacks (si_addr=(nil), libunwind frames in the core), not this
        // code.
        override def observe[S](baseline: Maybe[A], repairInterval: Duration)(f: A => Unit < (S & Async & Scope))(using
            Frame
        ): Unit < (S & Async) =
            // The repair interval is unused here: delivery is exact. The parameter exists for signals that can
            // miss wakeups (combinator-derived ones run the trait's repairing loop).
            discard(repairInterval)
            def nextSince(v0: Long): Unit < Async =
                Sync.Unsafe.defer {
                    if _unsafe.version() != v0 then ()
                    else
                        // Race wrapper: the next-promise is masked, and a fiber parked directly on a masked
                        // promise is not resumed by its own interrupt; racing behind a fresh result promise
                        // restores interruptibility.
                        Async.race(Seq(
                            Sync.Unsafe.defer {
                                val waiter = _unsafe.next().safe
                                if _unsafe.version() != v0 then (): Unit < Async
                                else waiter.use(_ => ())
                            }
                        ))
                }
            def hold(v0: Long, cur: A): Unit < Async =
                nextSince(v0).andThen(Sync.Unsafe.defer {
                    val v1 = _unsafe.version()
                    if _unsafe.get() == cur then hold(v1, cur) else (): Unit < Async
                })
            def loop(last: Maybe[A]): Unit < (S & Async) =
                Sync.Unsafe.defer {
                    val v0  = _unsafe.version()
                    val cur = _unsafe.get()
                    if last.exists(_ == cur) then nextSince(v0).andThen(loop(last))
                    else Scope.run(f(cur).andThen(hold(v0, cur))).andThen(loop(Present(cur)))
                }
            loop(baseline)
        end observe

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
        inline def use[B, S](inline f: A => B < S)(using Frame): B < (S & Sync) = Sync.Unsafe.defer(f(_unsafe.get()))

        /** Sets the reference to a new value.
          *
          * Updates the reference's value and notifies any observers if the value has changed. The previous value is returned.
          *
          * @param value
          *   The new value to set
          */
        def set(value: A)(using Frame): Unit < Sync = Sync.Unsafe.defer(_unsafe.set(value))

        /** Updates the reference's value and returns the previous value.
          *
          * @param value
          *   The new value to set
          * @return
          *   The previous value
          */
        def getAndSet(value: A)(using Frame): A < Sync =
            Sync.Unsafe.defer(_unsafe.getAndSet(value))

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
            Sync.Unsafe.defer(_unsafe.compareAndSet(curr, next))

        /** Atomically updates the current value using the provided function and returns the previous value.
          *
          * @param f
          *   The function to transform the current value
          * @return
          *   The previous value
          */
        def getAndUpdate(f: A => A)(using Frame): A < Sync =
            Sync.Unsafe.defer(_unsafe.getAndUpdate(f))

        /** Atomically updates the current value using the provided function and returns the new value.
          *
          * @param f
          *   The function to transform the current value
          * @return
          *   The new value
          */
        def updateAndGet(f: A => A)(using Frame): A < Sync =
            Sync.Unsafe.defer(_unsafe.updateAndGet(f))

        def waiters(using Frame): Int < Sync =
            Sync.Unsafe.defer(_unsafe.waiters())

        def unsafe: SignalRef.Unsafe[A] = _unsafe
    end SignalRef

    object SignalRef:

        /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details.
          *
          * The implementation uses two atomic references to manage state:
          *
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
            nextPromise: AtomicRef.Unsafe[Promise.Unsafe[A, Any]],
            versionRef: AtomicLong.Unsafe
        )(using CanEqual[A, A]):

            def get()(using AllowUnsafe): A = currentRef.get()

            /** Monotonic change counter, incremented once per distinct-value update. `SignalRef.observe` uses it
              * to validate that no write landed between reading `current` and capturing the next-change promise.
              */
            def version()(using AllowUnsafe): Long = versionRef.get()

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

            def next()(using AllowUnsafe): Fiber.Unsafe[A, Any] =
                nextPromise.get()

            private def onUpdate(value: A)(using AllowUnsafe): Unit =
                // The version MUST be bumped before the promise swap. Writer order is: value write (in the
                // caller), version increment, promise swap+complete. `SignalRef.observe`'s register/validate
                // protocol relies on exactly this order for losslessness (see the override).
                discard(versionRef.incrementAndGet())
                nextPromise.getAndSet(Promise.Unsafe.initMasked())
                    .completeDiscard(Result.succeed(value))
            end onUpdate

            def waiters()(using AllowUnsafe): Int = nextPromise.get().waiters()

            def safe: SignalRef[A] = SignalRef(this)

        end Unsafe

        object Unsafe:

            /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details.
              */
            def init[A](initial: A)(using AllowUnsafe, CanEqual[A, A]): Unsafe[A] =
                Unsafe(
                    AtomicRef.Unsafe.init(initial),
                    // Use initMasked so that interrupting one subscriber cannot propagate through the
                    // signal's next-promise and accidentally interrupt other subscribers waiting on the
                    // same signal. All subsequent promises (created in onUpdate) are already masked for
                    // the same reason; the initial promise must be consistent.
                    AtomicRef.Unsafe.init(Promise.Unsafe.initMasked()),
                    AtomicLong.Unsafe.init(0L)
                )
        end Unsafe

    end SignalRef
end Signal
