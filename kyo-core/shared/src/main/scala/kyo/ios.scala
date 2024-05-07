package kyo

import IOs.internal.*
import core.*
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.util.*
import scala.util.control.NonFatal

type IOs = Defers & SideEffects

object IOs:

    inline def apply[T, S](
        inline f: => T < (IOs & S)
    ): T < (IOs & S) =
        SideEffects.suspend[Unit, T, Defers & S]((), _ => f: T < (Defers & SideEffects & S))

    inline def unit: Unit < IOs = ()

    def run[T: Flat](v: T < IOs): T =
        runLazy(v).pure

    def runLazy[T: Flat, S](v: T < (IOs & S)): T < S =
        Defers.run(SideEffects.handle(handler)((), v))

    def fail[T](ex: Throwable): T < IOs =
        IOs(throw ex)

    def fail[T](msg: String): T < IOs =
        fail(new Exception(msg))

    def fromTry[T, S](v: Try[T] < S): T < (IOs & S) =
        v.map {
            case Success(v) =>
                v
            case Failure(ex) =>
                fail(ex)
        }

    def attempt[T, S](v: => T < S): Try[T] < S =
        eval(v.map(Success(_): Try[T]))((s, k) =>
            try k()
            catch
                case ex if NonFatal(ex) =>
                    Failure(ex)
        )

    def catching[T, S, U >: T, S2](v: => T < S)(
        pf: PartialFunction[Throwable, U < S2]
    ): U < (S & S2) =
        eval(v: U < (S & S2))((s, k) =>
            try k()
            catch
                case ex if NonFatal(ex) && pf.isDefinedAt(ex) =>
                    pf(ex)
        )

    def ensure[T, S](f: => Unit < IOs)(v: T < S): T < (IOs & S) =
        val ensure = new Ensure:
            def run = f
        eval(v: T < (IOs & S))(
            suspend = IOs(_),
            done = (s, v) =>
                Preempt.remove(s, ensure)
                ensure()
                v
            ,
            resume = (s, k) =>
                Preempt.ensure(s, ensure)
                try k()
                catch
                    case ex if NonFatal(ex) =>
                        Preempt.remove(s, ensure)
                        ensure()
                        throw ex
                end try
        )
    end ensure

    private[kyo] object internal:

        val handler = new Handler[Const[Unit], SideEffects, Any]:
            def resume[T, U: Flat, S2](command: Unit, k: T => U < (SideEffects & S2)) =
                Resume((), k(().asInstanceOf[T]))

        class SideEffects extends Effect[SideEffects]:
            type Command[T] = Unit
        object SideEffects extends SideEffects

        abstract class Ensure
            extends AtomicReference[Any]
            with Function0[Unit]:

            protected def run: Unit < IOs

            def apply(): Unit =
                if compareAndSet(null, ()) then
                    try IOs.run(run)
                    catch
                        case ex if NonFatal(ex) =>
                            Logs.unsafe.error(s"IOs.ensure function failed", ex)
        end Ensure

        trait Preempt extends Safepoint[SideEffects]:
            def ensure(f: () => Unit): Unit
            def remove(f: () => Unit): Unit
            def suspend[T, S](v: => T < S) =
                SideEffects.suspend((), _ => v)
        end Preempt

        object Preempt:
            def ensure(s: Safepoint[?], e: Ensure): Unit =
                s match
                    case p: Preempt => p.ensure(e)
                    case _          =>

            def remove(s: Safepoint[?], e: Ensure): Unit =
                s match
                    case p: Preempt => p.remove(e)
                    case _          =>

            val never: Preempt =
                new Preempt:
                    def ensure(f: () => Unit) = ()
                    def remove(f: () => Unit) = ()
                    def preempt()             = false
        end Preempt
    end internal
end IOs
