package kyo

import java.util.concurrent.atomic.AtomicLong
import kyo.TRefLog.*
import scala.annotation.tailrec

/** A transactional reference that can be modified within STM transactions. Provides atomic read and write operations with strong
  * consistency guarantees.
  */
sealed trait TRef[A]:

    /** Applies a function to the current value of the reference within a transaction.
      *
      * @param f
      *   A function that transforms the current value of type A into a result of type B, with effects S
      * @return
      *   The result of type B with combined STM and S effects
      */
    def use[B, S](f: A => B < S)(using Frame): B < (STM & S)

    /** Sets a new value for the reference within a transaction.
      *
      * @param v
      *   The new value to set
      */
    def set(v: A)(using Frame): Unit < STM

    /** Gets the current value of the reference within a transaction.
      *
      * @return
      *   The current value
      */
    final def get(using Frame): A < STM = use(identity)

    /** Updates the reference's value by applying a function to the current value within a transaction.
      *
      * @param f
      *   The function to transform the current value into the new value
      * @return
      *   Unit, as this is a modification operation
      */
    final def update[S](f: A => A < S)(using Frame): Unit < (STM & S) = use(f(_).map(set))

    private[kyo] def entry(using AllowUnsafe): Write[A]
    private[kyo] def validate(entry: Entry[A])(using AllowUnsafe): Boolean
    private[kyo] def lock(tick: Tick, entry: Entry[A])(using AllowUnsafe): Boolean
    private[kyo] def commit(tick: Tick, entry: Entry[A])(using AllowUnsafe): Unit
    private[kyo] def unlock(entry: Entry[A])(using AllowUnsafe): Unit

end TRef

/** Implementation of a transactional reference.
  *
  * @param initEntry
  *   The initial value and tick for this reference
  */
final private class TRefImpl[A] private[kyo] (initEntry: Write[A])
    extends TRef.State.Owner
    with TRef[A]
    with Serializable:

    import TRef.State
    import TRef.State.*

    @volatile private var _entry = initEntry

    private[kyo] def entry(using AllowUnsafe): Write[A] = _entry

    // Atomically update readTick to max(current, tick)
    @tailrec private def updateReadTick(tick: Tick): Unit =
        val s = getState()
        if tick.value > s.readTick then
            if !casState(s, s.withReadTick(tick.value)) then
                updateReadTick(tick)
        end if
    end updateReadTick

    def use[B, S](f: A => B < S)(using Frame): B < (STM & S) =
        Var.use[TRefLog] { log =>
            log.get(this) match
                case Present(entry) =>
                    f(entry.value)
                case Absent =>
                    Tick.withCurrent { tick =>
                        val e = _entry
                        if e.tick.value > tick.value then
                            // Early retry if the TRef is concurrently modified
                            STM.retry
                        else
                            // Register read interest - writers with lower tick will yield
                            updateReadTick(tick)
                            // Append Read to the log and return value
                            Var.setWith(log.put(this, Read(e.tick, e.value)))(f(e.value))
                        end if
                    }
            end match
        }

    def set(v: A)(using Frame): Unit < STM =
        Var.use[TRefLog] { log =>
            log.get(this) match
                case Present(prev) =>
                    Var.setDiscard(log.put(this, Write(prev.tick, v)))
                case Absent =>
                    Tick.withCurrent { tick =>
                        val e = _entry
                        if e.tick.value > tick.value || getState().readTick > tick.value then
                            // Early retry if the TRef is concurrently modified or
                            // fresher readers exist (writer would fail at commit anyway)
                            STM.retry
                        else
                            // Append Write to the log
                            Var.setDiscard(log.put(this, Write(e.tick, v)))
                        end if
                    }
        }

    private[kyo] def validate(entry: Entry[A])(using AllowUnsafe): Boolean =
        val current = _entry
        current.tick == entry.tick || (
            entry match
                // Value-based fallback only for reads: if the same reference was written
                // back, the read is still valid (reduces spurious aborts). Not safe for
                // writes since two transactions computing the same value must not both commit.
                case read: Read[?] => current.value.asInstanceOf[AnyRef].eq(read.value.asInstanceOf[AnyRef])
                case _             => false
        )
    end validate

    private[kyo] def lock(tick: Tick, entry: Entry[A])(using AllowUnsafe): Boolean =
        @tailrec def loop(): Boolean =
            validate(entry) && {
                val s = getState()
                entry match
                    case _: Read[?]  => s.acquireReader.exists(next => casState(s, next) || loop())
                    case _: Write[?] => s.acquireWriter(tick.value).exists(next => casState(s, next) || loop())
            }
        val locked = loop()
        if locked && !validate(entry) then
            // This branch handles the race condition where another fiber commits
            // after the initial validate check but before the lock is acquired.
            // If that's the case, roll back the lock.
            unlock(entry)
            false
        else
            locked
        end if
    end lock

    private[kyo] def commit(tick: Tick, entry: Entry[A])(using AllowUnsafe): Unit =
        entry match
            case Write(_, value) =>
                // Only need to commit Write entries
                _entry = Write(tick, value)
                // Reset readTick since value changed - old readers no longer relevant
                // Keep write lock in place (will be released by unlock)
                setState(getState().withoutReadTick)
            case _ =>

    private[kyo] def unlock(entry: Entry[A])(using AllowUnsafe): Unit =
        entry match
            case _: Read[?] =>
                @tailrec def loop(): Unit =
                    val s = getState()
                    if !casState(s, s.releaseReader) then loop()
                end loop
                loop()
            case _: Write[?] =>
                setState(State.free)
        end match
    end unlock

    override def toString() =
        val s = getState()
        s"TRef(state=$_entry, readTick=${s.readTick}, lock=${s.asString})"
    end toString
end TRefImpl

object TRef:

    /** Creates a new TRef with the given initial value.
      *
      * @param value
      *   The initial value to store in the reference
      * @return
      *   A new TRef containing the value, within the Sync effect
      */
    def init[A](value: A)(using Frame): TRef[A] < Sync =
        initWith(value)(identity)

    /** Creates a new TRef and immediately applies a function to it.
      *
      * This is a more efficient way to initialize a TRef and perform operations on it, as it combines initialization and the first
      * operation in a single transaction.
      *
      * @param value
      *   The initial value to store in the reference
      * @param f
      *   The function to apply to the newly created TRef
      * @return
      *   The result of applying the function to the new TRef, within combined Sync and S effects
      */
    inline def initWith[A, B, S](inline value: A)(inline f: TRef[A] => B < S)(using inline frame: Frame): B < (Sync & S) =
        Tick.withCurrentOrNext { tick =>
            f(TRef.Unsafe.init(tick, value))
        }

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    object Unsafe:
        def init[A](value: A)(using AllowUnsafe): TRef[A] =
            new TRefImpl(Write(Tick.next(), value))

        private[kyo] def init[A](tick: Tick, value: A)(using AllowUnsafe): TRef[A] =
            new TRefImpl(Write(tick, value))
    end Unsafe

    /** Packed atomic state for TRef containing both lock state and readTick.
      *
      * Bit layout (64 bits):
      *   - Lower 8 bits: lock state (0=free, 0xFF=writer, 1-0xFE=reader count)
      *   - Upper 56 bits: readTick (highest tick of readers)
      */
    private[kyo] opaque type State = Long

    private[kyo] object State:
        private inline def LockMask      = 0xffL
        private inline def ReadTickShift = 8
        private inline def WriteLock     = 0xffL
        private inline def MaxReaders    = 0xfeL

        val free: State = 0L

        /** Base class providing atomic State operations. */
        sealed abstract class Owner extends AtomicLong(free):
            final protected inline def getState(): State                          = get()
            final protected inline def casState(cur: State, next: State): Boolean = compareAndSet(cur, next)
            final protected inline def setState(next: State): Unit                = set(next)
        end Owner

        extension (self: State)
            // Tick
            inline def readTick: Long                  = self >>> ReadTickShift
            inline def withReadTick(tick: Long): State = (tick << ReadTickShift) | (self & LockMask)
            inline def withoutReadTick: State          = self & LockMask

            // Lock
            inline def acquireReader: Maybe[State] =
                Maybe.when((self & LockMask) < MaxReaders)(self + 1)

            inline def releaseReader: State = self - 1

            inline def acquireWriter(tick: Long): Maybe[State] =
                Maybe.when((self & LockMask) == 0 && self.readTick <= tick)((self & ~LockMask) | WriteLock)

            // Display
            inline def asString: String =
                (self & LockMask) match
                    case 0                   => "free"
                    case n if n == WriteLock => "writer"
                    case n                   => s"$n readers"
        end extension
    end State
end TRef
