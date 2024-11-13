package kyo.kernel

import internal.*
import java.util.concurrent.atomic.AtomicBoolean
import kyo.Frame
import kyo.isNull
import kyo.kernel.Safepoint.*
import scala.annotation.nowarn
import scala.util.control.NonFatal

/** Provides runtime safety guarantees and debugging capabilities for effect execution.
  *
  * Safepoint ensures stack safety, thread isolation, and debugging support for effectful computations. It maintains execution state,
  * manages interceptors for cross-cutting concerns, and provides rich error context for failures.
  *
  * This class is not meant to be used directly by user code, but rather serves as infrastructure for effect handlers and combinators. It's
  * a key part of ensuring effect execution remains practical and debuggable while maintaining good performance characteristics.
  */
final class Safepoint private () extends Trace.Owner:

    private var state: State             = State.init()
    private var interceptor: Interceptor = null

    private[kernel] def enter(frame: Frame, value: Any): Boolean =
        val state    = this.state
        val threadId = Thread.currentThread().getId()
        val proceed =
            state.depth <= maxStackDepth &&
                state.threadId == threadId &&
                (!state.hasInterceptor || interceptor.enter(frame, value))
        if proceed then
            this.state = state.incrementDepth
            pushFrame(frame)
        proceed
    end enter

    private[kernel] def exit(): Unit =
        state = state.decrementDepth

    private[kernel] def getInterceptor(): Interceptor = interceptor

    private[kernel] def setInterceptor(newInterceptor: Interceptor): Unit =
        interceptor = newInterceptor
        state = state.withInterceptor(newInterceptor != null)

end Safepoint

object Safepoint:

    implicit def get: Safepoint = local.get()

    private[kernel] opaque type State = Long

    private[kernel] object State:
        // Bit allocation:
        // Bits 0-15 (16 bits): depth (0-65535)
        // Bit 16 (1 bit): hasInterceptor flag
        // Bits 17-63 (47 bits): threadId

        private inline def DepthMask: Long       = 0xffffL
        private inline def InterceptorMask: Long = 1L << 16
        private inline def ThreadIdMask: Long    = 0xffffffffffff0000L

        require(maxStackDepth <= 65536)

        inline def init(): State =
            (Thread.currentThread().getId() << 17) & ThreadIdMask

        extension (state: State)
            def depth: Int              = (state & DepthMask).toInt
            def threadId: Long          = (state & ThreadIdMask) >>> 17
            def hasInterceptor: Boolean = (state & InterceptorMask) != 0
            def incrementDepth: State   = state + 1
            def decrementDepth: State   = state - 1
            def withInterceptor(hasInterceptor: Boolean): State =
                if hasInterceptor then state | InterceptorMask
                else state & ~InterceptorMask
        end extension
    end State

    abstract private[kyo] class Interceptor:
        def addFinalizer(f: () => Unit): Unit
        def removeFinalizer(f: () => Unit): Unit
        def enter(frame: Frame, value: Any): Boolean
    end Interceptor

    @nowarn("msg=anonymous")
    private[kyo] inline def immediate[A, S](p: Interceptor)(inline v: => A < S)(
        using safepoint: Safepoint
    ): A < S =
        val prev = safepoint.interceptor
        val np =
            if isNull(prev) || (prev eq p) then p
            else
                new Interceptor:
                    override def addFinalizer(f: () => Unit): Unit    = p.addFinalizer(f)
                    override def removeFinalizer(f: () => Unit): Unit = p.removeFinalizer(f)
                    def enter(frame: Frame, value: Any) =
                        p.enter(frame, value) && prev.enter(frame, value)
        safepoint.setInterceptor(np)
        try v
        finally safepoint.setInterceptor(prev)
    end immediate

    private[kyo] inline def propagating[A, S](p: Interceptor)(inline v: => A < S)(
        using
        inline safepoint: Safepoint,
        inline _frame: Frame
    ): A < S =
        @nowarn("msg=anonymous") def loop(v: A < S): A < S =
            v match
                case kyo: KyoSuspend[IX, OX, EX, Any, A, S] @unchecked =>
                    new KyoContinue[IX, OX, EX, Any, A, S](kyo):
                        def frame = _frame
                        def apply(v: OX[Any], context: Context)(using Safepoint): A < S =
                            loop(immediate(p)(kyo(v, context)))
                case _ =>
                    v
        immediate(p)(loop(v))
    end propagating

    abstract private[kyo] class Ensure extends AtomicBoolean with Function0[Unit]:
        def run: Unit
        final def apply(): Unit =
            if compareAndSet(false, true) then
                val safepoint = Safepoint.get
                val prev      = safepoint.interceptor
                safepoint.setInterceptor(null)
                try run
                finally safepoint.setInterceptor(prev)
    end Ensure

    private inline def ensuring[A](ensure: Ensure)(inline thunk: => A)(using safepoint: Safepoint): A =
        val interceptor = safepoint.interceptor
        if !isNull(interceptor) then interceptor.addFinalizer(ensure)
        try thunk
        catch
            case ex if NonFatal(ex) =>
                ensure()
                throw ex
        end try
    end ensuring

    @nowarn("msg=anonymous")
    private[kyo] inline def ensure[A, S](inline f: => Unit)(inline v: => A < S)(using safepoint: Safepoint, inline _frame: Frame): A < S =
        // ensures the function is called once even if an
        // interceptor executes it multiple times
        val ensure = new Ensure:
            def run: Unit = f

        def ensureLoop(v: A < S)(using safepoint: Safepoint): A < S =
            v match
                case kyo: KyoSuspend[IX, OX, EX, Any, A, S] @unchecked =>
                    new KyoContinue[IX, OX, EX, Any, A, S](kyo):
                        def frame = _frame
                        def apply(v: OX[Any], context: Context)(using Safepoint): A < S =
                            ensuring(ensure)(ensureLoop(kyo(v, context)))
                case _ =>
                    val interceptor = safepoint.interceptor
                    if !isNull(interceptor) then interceptor.removeFinalizer(ensure)
                    ensure()
                    v
        ensuring(ensure)(ensureLoop(v))
    end ensure

    private[kernel] inline def eval[A](
        inline f: Safepoint ?=> A
    )(using inline frame: Frame): A =
        val self = Safepoint.get
        self.withNewTrace(f(using self))
    end eval

    private[kernel] inline def handle[V, A, S](value: V)(
        inline suspend: Safepoint ?=> A < S,
        inline continue: => A < S
    )(using inline frame: Frame, self: Safepoint): A < S =
        if !self.enter(frame, value) then
            Effect.defer(suspend)
        else
            try continue
            finally self.exit()
    end handle

    private[kernel] inline def handle[A, B, S](value: Any)(
        inline eval: => A,
        inline continue: A => B < S,
        inline suspend: Safepoint ?=> B < S
    )(using inline frame: Frame, self: Safepoint): B < S =
        if !self.enter(frame, value) then
            Effect.defer(suspend)
        else
            val a =
                try eval
                finally self.exit()
            continue(a)
    end handle

    private[kernel] def enrich(ex: Throwable)(using safepoint: Safepoint): Unit =
        safepoint.enrich(ex)

    private val local =
        new ThreadLocal[Safepoint]:
            override def initialValue() = new Safepoint

end Safepoint
