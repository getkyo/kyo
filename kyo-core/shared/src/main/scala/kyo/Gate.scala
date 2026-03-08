package kyo

import scala.annotation.tailrec

/** A synchronization primitive that coordinates concurrent parties through a shared passage point.
  *
  * A gate controls passage: parties arrive and wait until all expected parties are present, then everyone passes through together. The gate
  * then resets for the next group, like a revolving door.
  *
  *   - `Gate`: Fixed parties, multi-pass. Similar to Java's `CyclicBarrier` but adds pass tracking (`passCount`, `passAt`) and non-blocking
  *     arrival (`arrive`).
  *   - `Gate.Dynamic`: Dynamic membership (`join`/`leave`) and hierarchical subgroups. Similar to Java's `Phaser`.
  *
  * Use `Gate` when the set of participants is fixed at creation. Use `Gate.Dynamic` when participants need to join or leave at runtime, or
  * when independent groups should synchronize locally before coordinating with a parent gate.
  *
  * Both variants share `pass` (arrive and wait), `arrive` (signal without waiting), and `close` (fail all waiters with `Closed`) as primary
  * operations. Use `init` (with Scope) for automatic cleanup or `initUnscoped` with manual `close`. A custom stop condition or fixed pass
  * count can be provided at creation to auto-close the gate.
  *
  * Unlike [[kyo.Latch]], which is asymmetric (some tasks release while others wait), Gate is symmetric: all parties participate equally and
  * pass through together.
  *
  * @see
  *   [[Gate.Dynamic]] for dynamic membership and subgroup hierarchies
  * @see
  *   [[kyo.Latch]] for asymmetric countdown coordination (similar to `java.util.concurrent.CountDownLatch`)
  */
opaque type Gate = Gate.Unsafe

object Gate:

    /** Creates a gate with automatic cleanup via Scope.
      *
      * @param parties
      *   the number of parties that must pass before the gate advances
      */
    def init(parties: Int)(using Frame): Gate < (Sync & Scope) =
        Scope.acquireRelease(initUnscoped(parties))(doClose(_))

    /** Creates a gate with a custom stop condition. The gate closes when `stop(passNumber, parties)` returns true. */
    def init(parties: Int, stop: (Int, Int) => Boolean)(using Frame): Gate < (Sync & Scope) =
        Scope.acquireRelease(initUnscoped(parties, stop))(doClose(_))

    /** Creates a gate that automatically closes after a fixed number of passes. */
    def init(parties: Int, totalPasses: Int)(using Frame): Gate < (Sync & Scope) =
        Scope.acquireRelease(initUnscoped(parties, totalPasses))(doClose(_))

    inline def initWith[A, S](parties: Int)(inline f: Gate => A < S)(using inline frame: Frame): A < (S & Sync & Scope) =
        Sync.Unsafe.defer:
            val gate = Unsafe.init(parties)
            Scope.ensure(doClose(gate.safe)).andThen:
                f(gate.safe)

    def initUnscoped(parties: Int)(using Frame): Gate < Sync =
        Sync.Unsafe.defer(Unsafe.init(parties).safe)

    def initUnscoped(parties: Int, stop: (Int, Int) => Boolean)(using Frame): Gate < Sync =
        Sync.Unsafe.defer(Unsafe.init(parties, stop).safe)

    def initUnscoped(parties: Int, totalPasses: Int)(using Frame): Gate < Sync =
        if totalPasses <= 0 then
            Sync.Unsafe.defer(Unsafe.noop.safe)
        else
            initUnscoped(parties, (phase, _) => phase >= totalPasses - 1)

    inline def initUnscopedWith[A, S](parties: Int)(inline f: Gate => A < S)(using inline frame: Frame): A < (S & Sync) =
        Sync.Unsafe.defer(f(Unsafe.init(parties).safe))

    extension (self: Gate)

        /** Pass through the gate. Blocks until all parties have passed.
          *
          * Each call signals one arrival and waits for all remaining parties. When the last party passes, the gate opens and all waiters
          * proceed. The gate then resets for the next pass.
          *
          * Fails with `Abort[Closed]` if the gate is closed while waiting or was already closed.
          */
        def pass(using Frame): Unit < (Async & Abort[Closed]) =
            Sync.Unsafe.defer(self.pass().safe.get)

        /** Pass through the gate and apply an inline continuation, avoiding closure allocation. */
        inline def passWith[B, S](inline f: => B < S)(using Frame): B < (S & Async & Abort[Closed]) =
            Sync.Unsafe.defer(self.pass().safe.get.andThen(f))

        /** Signal arrival without waiting for others to pass.
          *
          * Useful when a party needs to signal its presence but continue working without blocking. If this is the last party to arrive, the
          * gate opens and all waiters proceed.
          */
        def arrive(using Frame): Unit < Sync = Sync.Unsafe.defer(self.arrive())

        /** Signal arrival and apply an inline continuation, avoiding closure allocation. */
        inline def arriveWith[B, S](inline f: => B < S)(using Frame): B < (S & Sync) =
            Sync.Unsafe.defer {
                self.arrive()
                f
            }

        /** Wait for the gate to be passed through a specific number of times.
          *
          * If the gate has already been passed through `n` or more times, returns immediately. Otherwise blocks until the nth pass
          * completes.
          *
          * Fails with `Abort[Closed]` if the gate is closed while waiting or was already closed.
          *
          * @param n
          *   the pass number to wait for (0-indexed)
          */
        def passAt(n: Int)(using Frame): Unit < (Async & Abort[Closed]) =
            Sync.Unsafe.defer(self.passAt(n).safe.get)

        /** Wait for a specific pass and apply an inline continuation, avoiding closure allocation. */
        inline def passAtWith[B, S](n: Int)(inline f: => B < S)(using Frame): B < (S & Async & Abort[Closed]) =
            Sync.Unsafe.defer(self.passAt(n).safe.get.andThen(f))

        /** How many parties are still expected before the gate opens. After close, reflects the pending count at the time of close. */
        def pendingCount(using Frame): Int < Sync =
            Sync.Unsafe.defer(self.pendingCount())

        /** How many times the gate has been passed through (how many complete group passages). After close, reflects the pass count at the
          * time of close.
          */
        def passCount(using Frame): Int < Sync =
            Sync.Unsafe.defer(self.passCount())

        /** How many parties have arrived at the gate so far for the current pass. After close, reflects the arrived count at the time of
          * close.
          */
        def arrivedCount(using Frame): Int < Sync =
            Sync.Unsafe.defer(self.arrivedCount())

        /** Close the gate, failing all waiters with `Closed`. Returns true if this call closed it. */
        def close(using Frame): Boolean < Sync =
            Sync.Unsafe.defer(self.close())

        /** Whether the gate has been closed. */
        def closed(using Frame): Boolean < Sync =
            Sync.Unsafe.defer(self.closed())

        def unsafe: Unsafe = self
    end extension

    // Core gate implementation. Dynamic extends this class,
    // inheriting all CAS logic.
    sealed abstract class Unsafe(initialParties: Int, initFrame: Frame, allowUnsafe: AllowUnsafe):

        protected val state = State.init(0, initialParties, 0)(using allowUnsafe)

        // Promise for the current pass, swapped on each pass advance.
        // Masked to prevent one interrupted fiber from affecting other waiters.
        protected val currentPass =
            AtomicRef.Unsafe.init(
                Promise.Unsafe.initMasked[Unit, Abort[Closed]]()(using allowUnsafe)
            )(using allowUnsafe)

        // Read promise before state (promise-before-CAS ordering): if the pass
        // changed between promise read and CAS, the CAS fails and we retry,
        // guaranteeing the promise matches the pass we arrived at.
        def pass()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[Closed]] =
            @tailrec def loop(): Fiber.Unsafe[Unit, Abort[Closed]] =
                val p = currentPass.get()
                val s = state.get()
                if s.isClosed then
                    // Closed, return failed promise
                    p
                else
                    val parties = s.parties
                    if s.arrived + 1 < parties then
                        // Not last party, record arrival and wait
                        if !state.cas(s, s.incArrived) then
                            loop() // CAS failed, retry
                        else
                            p
                    else
                        // Last party, advance to next phase
                        val phase = s.phase
                        if !state.cas(s, s.advancePhase) then
                            loop() // CAS failed, retry
                        else
                            advancePass(p, phase, parties)
                            p
                        end if
                    end if
                end if
            end loop
            loop()
        end pass

        def arrive()(using AllowUnsafe, Frame): Unit =
            @tailrec def loop(): Unit =
                val s = state.get()
                if !s.isClosed then
                    val arrived = s.arrived + 1
                    val parties = s.parties
                    val phase   = s.phase
                    if arrived < parties then
                        // Not last party, record arrival
                        if !state.cas(s, s.incArrived) then
                            loop() // CAS failed, retry
                    else
                        // Last party, advance to next phase
                        if !state.cas(s, s.advancePhase) then
                            loop() // CAS failed, retry
                        else
                            advancePass(currentPass.get(), phase, parties)
                    end if
                end if
            end loop
            loop()
        end arrive

        // Fast path: if phase already past target, no CAS or promise read needed.
        // Otherwise chains via flatMap through intermediate passes.
        def passAt(target: Int)(using AllowUnsafe): Fiber.Unsafe[Unit, Abort[Closed]] =
            val s = state.get()
            if s.phase > target then
                Fiber.unit.unsafe
            else
                currentPass.get().flatMap(_ => passAt(target))
            end if
        end passAt

        def pendingCount()(using AllowUnsafe): Int =
            val s = state.get()
            s.parties - s.arrived

        def passCount()(using AllowUnsafe): Int =
            state.get().phase

        def arrivedCount()(using AllowUnsafe): Int =
            state.get().arrived

        def close()(using AllowUnsafe, Frame): Boolean =
            @tailrec def loop(): Boolean =
                val s = state.get()
                if s.isClosed then
                    // Already closed
                    false
                else if !state.cas(s, s.markClosed) then
                    loop() // CAS failed, retry
                else
                    // Fail all waiters
                    currentPass.get().completeDiscard(Result.fail(Closed("Gate", initFrame)))
                    true
                end if
            end loop
            loop()
        end close

        def closed()(using AllowUnsafe): Boolean =
            state.get().isClosed

        def safe: Gate = this

        // Called when all parties have arrived for a pass. Default behavior:
        // close if no parties remain, otherwise swap the promise and release
        // waiters. Overridden by factories when user provides stop callback,
        // and by Dynamic to signal the parent gate.
        // Safe: only one fiber reaches here per phase (CAS on advancePhase serializes).
        protected def advancePass(
            currentPromise: Promise.Unsafe[Unit, Abort[Closed]],
            phase: Int,
            parties: Int
        )(using AllowUnsafe, Frame): Unit =
            if parties == 0 then
                discard(close()) // No parties remain, close gate
            else
                // Swap promise and release waiters
                currentPass.set(Promise.Unsafe.initMasked[Unit, Abort[Closed]]())
                currentPromise.completeUnitDiscard()
            end if
        end advancePass

    end Unsafe

    object Unsafe:

        def noop(using frame: Frame, allowUnsafe: AllowUnsafe): Unsafe =
            new Unsafe(0, frame, allowUnsafe):
                override def pass()(using AllowUnsafe, Frame)       = Fiber.unit.unsafe
                override def arrive()(using AllowUnsafe, Frame)     = ()
                override def passAt(target: Int)(using AllowUnsafe) = Fiber.unit.unsafe
                override def pendingCount()(using AllowUnsafe)      = 0
                override def passCount()(using AllowUnsafe)         = 0
                override def arrivedCount()(using AllowUnsafe)      = 0
                override def close()(using AllowUnsafe, Frame)      = false
                override def closed()(using AllowUnsafe)            = true

        def init(initialParties: Int)(using Frame, AllowUnsafe): Unsafe =
            init(initialParties, (_, _) => false)

        def init(initialParties: Int, stop: (Int, Int) => Boolean)(using frame: Frame, allowUnsafe: AllowUnsafe): Unsafe =
            if initialParties <= 0 then
                noop
            else
                new Unsafe(initialParties, frame, allowUnsafe):
                    override protected def advancePass(
                        currentPromise: Promise.Unsafe[Unit, Abort[Closed]],
                        phase: Int,
                        parties: Int
                    )(using AllowUnsafe, Frame): Unit =
                        if parties == 0 then
                            // No parties remain, close gate
                            discard(this.close())
                        else if stop(phase, parties) then
                            // Stop condition met, release waiters then close
                            currentPass.set(Promise.Unsafe.initMasked[Unit, Abort[Closed]]())
                            currentPromise.completeUnitDiscard()
                            discard(this.close())
                        else
                            // Swap promise and release waiters
                            currentPass.set(Promise.Unsafe.initMasked[Unit, Abort[Closed]]())
                            currentPromise.completeUnitDiscard()
                        end if
                    end advancePass

        inline def initWith[A](initialParties: Int)(inline f: Unsafe => A)(using Frame, AllowUnsafe): A =
            f(init(initialParties))

    end Unsafe

    /** A gate with dynamic membership and hierarchical subgroups.
      *
      * Extends Gate with the ability for parties to `join` and `leave` at any time. Use `subgroup(n)` to create a child gate whose parties
      * synchronize locally: when all parties in a subgroup pass, it signals the parent. The parent advances only when all subgroups and
      * direct parties complete their pass.
      *
      * Joining adds you as a party the gate will wait for on each pass. After joining, you must eventually `pass`, `arrive`, or `leave` on
      * each pass to avoid blocking other parties.
      *
      * Similar to `java.util.concurrent.Phaser` with tiered (parent-child) support.
      */
    opaque type Dynamic <: Gate = Dynamic.Unsafe

    object Dynamic:

        extension (self: Dynamic)

            /** Join the gate, adding yourself as a party.
              *
              * After joining, you are expected to eventually `pass` or `leave`. The gate will wait for you on each pass.
              */
            def join(using Frame): Unit < Sync = Sync.Unsafe.defer(self.join())

            /** Join the gate, adding n parties. */
            def join(n: Int)(using Frame): Unit < Sync = Sync.Unsafe.defer(self.join(n))

            /** Join the gate and apply an inline continuation, avoiding closure allocation. */
            inline def joinWith[B, S](inline f: => B < S)(using Frame): B < (S & Sync) =
                Sync.Unsafe.defer {
                    self.join()
                    f
                }

            /** Leave the gate, removing yourself as a party.
              *
              * If all remaining parties have already arrived, this may trigger the gate to advance to the next pass.
              */
            def leave(using Frame): Unit < Sync = Sync.Unsafe.defer(self.leave())

            /** Leave the gate and apply an inline continuation, avoiding closure allocation. */
            inline def leaveWith[B, S](inline f: => B < S)(using Frame): B < (S & Sync) =
                Sync.Unsafe.defer {
                    self.leave()
                    f
                }

            /** How many parties are currently registered at the gate. After close, reflects the party count at the time of close. */
            def size(using Frame): Int < Sync = Sync.Unsafe.defer(self.size())

            /** Create a subgroup with the given number of parties.
              *
              * The subgroup is a child gate that synchronizes its own parties locally. When the subgroup completes a pass, it signals the
              * parent gate. The parent gate advances only when all subgroups (and direct parties) have completed their pass.
              *
              * @param parties
              *   the number of parties in this subgroup
              */
            def subgroup(parties: Int)(using Frame): Dynamic < Sync = Sync.Unsafe.defer(self.subgroup(parties).safe)

            def unsafe: Dynamic.Unsafe = self

        end extension

        private def doClose(b: Dynamic)(using Frame): Unit < Sync =
            Sync.Unsafe.defer(discard(b.close())) // Scope cleanup, idempotent

        /** Creates a dynamic gate with automatic cleanup via Scope. */
        def init(parties: Int)(using Frame): Dynamic < (Sync & Scope) =
            Scope.acquireRelease(initUnscoped(parties))(doClose(_))

        /** Creates a dynamic gate with a custom stop condition. */
        def init(parties: Int, stop: (Int, Int) => Boolean)(using Frame): Dynamic < (Sync & Scope) =
            Scope.acquireRelease(initUnscoped(parties, stop))(doClose(_))

        /** Creates a dynamic gate that automatically closes after a fixed number of passes. */
        def init(parties: Int, totalPasses: Int)(using Frame): Dynamic < (Sync & Scope) =
            Scope.acquireRelease(initUnscoped(parties, totalPasses))(doClose(_))

        inline def initWith[A, S](parties: Int)(inline f: Dynamic => A < S)(using inline frame: Frame): A < (S & Sync & Scope) =
            Sync.Unsafe.defer:
                val gate = Unsafe.init(parties)
                Scope.ensure(doClose(gate.safe)).andThen:
                    f(gate.safe)

        def initUnscoped(parties: Int)(using Frame): Dynamic < Sync =
            Sync.Unsafe.defer(Unsafe.init(parties).safe)

        def initUnscoped(parties: Int, stop: (Int, Int) => Boolean)(using Frame): Dynamic < Sync =
            Sync.Unsafe.defer(Unsafe.init(parties, stop).safe)

        def initUnscoped(parties: Int, totalPasses: Int)(using Frame): Dynamic < Sync =
            if totalPasses <= 0 then
                Sync.Unsafe.defer(Unsafe.noop.safe)
            else
                initUnscoped(parties, (phase, _) => phase >= totalPasses - 1)

        inline def initUnscopedWith[A, S](parties: Int)(inline f: Dynamic => A < S)(using inline frame: Frame): A < (S & Sync) =
            Sync.Unsafe.defer(f(Unsafe.init(parties).safe))

        sealed abstract class Unsafe(
            initialParties: Int,
            val parent: Maybe[Dynamic.Unsafe],
            initFrame: Frame,
            allowUnsafe: AllowUnsafe
        ) extends Gate.Unsafe(initialParties, initFrame, allowUnsafe):

            def join()(using AllowUnsafe): Unit = join(1)

            def join(n: Int)(using AllowUnsafe): Unit =
                @tailrec def loop(): Unit =
                    val s = state.get()
                    if !s.isClosed then
                        // Add n parties to the gate
                        if !state.cas(s, s.addParties(n)) then
                            loop() // CAS failed, retry
                    end if
                end loop
                loop()
            end join

            def leave()(using AllowUnsafe, Frame): Unit =
                @tailrec def loop(): Unit =
                    val s = state.get()
                    if !s.isClosed then
                        val parties = s.parties
                        val arrived = s.arrived
                        val phase   = s.phase
                        if parties <= 1 then
                            // Last party leaving, advance with zero parties
                            val newState = s.advancePhase(0)
                            if !state.cas(s, newState) then
                                loop() // CAS failed, retry
                            else
                                advancePass(currentPass.get(), phase, 0)
                            end if
                        else
                            // Remove party
                            val newState = s.removeParty
                            if !state.cas(s, newState) then
                                loop() // CAS failed, retry
                            else if arrived >= parties - 1 then
                                // All remaining parties already arrived, advance
                                if state.cas(newState, newState.advancePhase) then
                                    advancePass(currentPass.get(), phase, parties - 1)
                            end if
                        end if
                    end if
                end loop
                loop()
            end leave

            def size()(using AllowUnsafe): Int =
                state.get().parties

            def subgroup(parties: Int)(using AllowUnsafe, Frame): Dynamic.Unsafe =
                Dynamic.Unsafe.init(parties, Maybe(this))

            override def safe: Dynamic = this

            // Override close to also leave from parent
            override def close()(using AllowUnsafe, Frame): Boolean =
                @tailrec def loop(): Boolean =
                    val s = state.get()
                    if s.isClosed then
                        // Already closed
                        false
                    else if !state.cas(s, s.markClosed) then
                        loop() // CAS failed, retry
                    else
                        // Fail all waiters, notify parent
                        currentPass.get().completeDiscard(Result.fail(Closed("Gate", initFrame)))
                        parent.foreach(_.arriveAndLeave())
                        true
                    end if
                end loop
                loop()
            end close

            // Override advancePass to signal parent on pass completion
            override protected def advancePass(
                currentPromise: Promise.Unsafe[Unit, Abort[Closed]],
                phase: Int,
                parties: Int
            )(using AllowUnsafe, Frame): Unit =
                if parties == 0 then
                    // No parties remain, close gate
                    discard(close())
                else
                    // Swap promise, release waiters, signal parent
                    val newPromise = Promise.Unsafe.initMasked[Unit, Abort[Closed]]()
                    currentPass.set(newPromise)
                    currentPromise.completeUnitDiscard()
                    parent.foreach(p => p.arrive())
                end if
            end advancePass

            // Internal: arrive and leave atomically. Used by subgroups
            // when closing to signal the parent.
            private[Gate] def arriveAndLeave()(using AllowUnsafe, Frame): Unit =
                @tailrec def loop(): Unit =
                    val s = state.get()
                    if !s.isClosed then
                        val arrived  = s.arrived + 1
                        val parties  = s.parties - 1
                        val phase    = s.phase
                        val newState = s.incArrived.removeParty
                        if !state.cas(s, newState) then
                            loop() // CAS failed, retry
                        else
                            if parties <= 0 then
                                // No parties remain, advance with zero
                                if state.cas(newState, newState.advancePhase(0)) then
                                    advancePass(currentPass.get(), phase, 0)
                            else if arrived >= parties then
                                // All parties arrived, advance
                                if state.cas(newState, newState.advancePhase) then
                                    advancePass(currentPass.get(), phase, parties)
                            end if
                        end if
                    end if
                end loop
                loop()
            end arriveAndLeave

        end Unsafe

        object Unsafe:

            def noop(using frame: Frame, allowUnsafe: AllowUnsafe): Unsafe =
                new Unsafe(0, Maybe.empty, frame, allowUnsafe):
                    override def join()(using AllowUnsafe)                        = ()
                    override def join(n: Int)(using AllowUnsafe)                  = ()
                    override def leave()(using AllowUnsafe, Frame)                = ()
                    override def size()(using AllowUnsafe)                        = 0
                    override def subgroup(parties: Int)(using AllowUnsafe, Frame) = this
                    override def pass()(using AllowUnsafe, Frame)                 = Fiber.unit.unsafe
                    override def arrive()(using AllowUnsafe, Frame)               = ()
                    override def passAt(target: Int)(using AllowUnsafe)           = Fiber.unit.unsafe
                    override def pendingCount()(using AllowUnsafe)                = 0
                    override def passCount()(using AllowUnsafe)                   = 0
                    override def arrivedCount()(using AllowUnsafe)                = 0
                    override def close()(using AllowUnsafe, Frame)                = false
                    override def closed()(using AllowUnsafe)                      = true

            def init(initialParties: Int)(using Frame, AllowUnsafe): Unsafe =
                init(initialParties, (_, _) => false)

            def init(initialParties: Int, parentGate: Maybe[Dynamic.Unsafe])(using frame: Frame, allowUnsafe: AllowUnsafe): Unsafe =
                if initialParties <= 0 && parentGate.isEmpty then
                    noop
                else
                    new Unsafe(initialParties, parentGate, frame, allowUnsafe):
                        parentGate.foreach { parent =>
                            if initialParties > 0 then
                                parent.join()
                        }

            def init(initialParties: Int, stop: (Int, Int) => Boolean)(using frame: Frame, allowUnsafe: AllowUnsafe): Unsafe =
                if initialParties <= 0 then
                    noop
                else
                    new Unsafe(initialParties, Maybe.empty, frame, allowUnsafe):
                        override protected def advancePass(
                            currentPromise: Promise.Unsafe[Unit, Abort[Closed]],
                            phase: Int,
                            parties: Int
                        )(using AllowUnsafe, Frame): Unit =
                            if parties == 0 then
                                // No parties remain, close gate
                                discard(this.close())
                            else if stop(phase, parties) then
                                // Stop condition met, release waiters then close
                                currentPass.set(Promise.Unsafe.initMasked[Unit, Abort[Closed]]())
                                currentPromise.completeUnitDiscard()
                                parent.foreach(p => p.arrive())
                                discard(this.close())
                            else
                                // Swap promise, release waiters, signal parent
                                currentPass.set(Promise.Unsafe.initMasked[Unit, Abort[Closed]]())
                                currentPromise.completeUnitDiscard()
                                parent.foreach(p => p.arrive())
                            end if
                        end advancePass

            inline def initWith[A](initialParties: Int)(inline f: Unsafe => A)(using Frame, AllowUnsafe): A =
                f(init(initialParties))

        end Unsafe
    end Dynamic

    private def doClose(g: Gate)(using Frame): Unit < Sync =
        Sync.Unsafe.defer(discard(g.close())) // Scope cleanup, idempotent

    /** Packed AtomicLong layout for Gate/Dynamic.
      *
      * A single Long packs all gate state for single-CAS atomic updates:
      *   - bit 63: closed flag (state < 0 = closed)
      *   - bits 62-32: pass number (31 bits, ~2 billion passes)
      *   - bits 31-16: registered parties (16 bits, max 65535)
      *   - bits 15-0: arrived count (16 bits, max 65535)
      *
      * Arrived in low bits means arrive is just `state + 1L` — no bit manipulation. Closed as sign bit means `closed` is just `state < 0`.
      */
    private type State = State.Impl

    private object State:

        opaque type Impl = AtomicLong.Unsafe

        opaque type Snapshot = Long

        private inline def pack(phase: Int, parties: Int, arrived: Int): Long =
            (phase.toLong << 32) | (parties.toLong << 16) | arrived.toLong

        def init(phase: Int, parties: Int, arrived: Int)(using AllowUnsafe): State =
            AtomicLong.Unsafe.init(pack(phase, parties, arrived))

        extension (self: State)
            def get()(using AllowUnsafe): Snapshot = AtomicLong.Unsafe.get(self)()
            def cas(expected: Snapshot, update: Snapshot)(using AllowUnsafe): Boolean =
                AtomicLong.Unsafe.compareAndSet(self)(expected, update)
        end extension

        extension (self: Snapshot)
            inline def arrived: Int                         = (self & 0xffffL).toInt
            inline def parties: Int                         = ((self & 0xffff0000L) >>> 16).toInt
            inline def phase: Int                           = ((self & 0x7fffffff00000000L) >>> 32).toInt
            inline def isClosed: Boolean                    = self < 0
            inline def markClosed: Snapshot                 = self | Long.MinValue
            inline def incArrived: Snapshot                 = self + 1L
            inline def addParties(n: Int): Snapshot         = self + (n.toLong << 16)
            inline def removeParty: Snapshot                = self - (1L << 16)
            inline def advancePhase: Snapshot               = pack(self.phase + 1, self.parties, 0)
            inline def advancePhase(parties: Int): Snapshot = pack(self.phase + 1, parties, 0)
        end extension
    end State

end Gate
