package kyo.kernel2

import kyo.Frame
import kyo.kernel2.internal.KyoException
import scala.annotation.nowarn
import scala.util.control.NonFatal

/** A type constructor that ignores its argument: the shape of operations whose input or output does not vary with the operation's
  * type index.
  */
type Const[A] = [B] =>> A

/** Identity type constructor: returns its input type unchanged.
  *
  * Commonly used with [[ArrowEffect]] when an effect needs to preserve the exact type it operates on without modification.
  */
type Id[A] = A

/** The common parent of the two effect kinds.
  *
  * An effect kind is a declaration of what a handler provides. [[ArrowEffect]] declares operations, interpreted by a handler clause per
  * operation. [[ContextEffect]] declares a value, provided by a handler for a scope. The kinds differ in boundary semantics: context state
  * is copyable across forks, control state lives in the handler's interpretation.
  */
abstract class Effect private[kernel2] ()

object Effect:

    /** Wraps a computation with error handling.
      *
      * The error handler `f` is called if a non-fatal exception is thrown during the initial evaluation or during any later step of the
      * computation, including steps that run after the computation suspends and resumes: the interception travels with the parked
      * continuation.
      */
    def catching[A, S, B >: A, S2](v: => A < S)(
        f: Throwable => B < S2
    )(using _frame: Frame): B < (S & S2) =
        try
            val w = v
            (w: Any) match
                case kyo: Kyo[?, ?] =>
                    val handler = f.asInstanceOf[Throwable => Any < Any]
                    kyo.prepend(new Catching(handler, _frame)).asInstanceOf[B < (S & S2)]
                case _ =>
                    w.asInstanceOf[B < (S & S2)]
            end match
        catch
            case ex if NonFatal(ex) =>
                KyoException.attach(ex, "catching", _frame)
                f(ex)
        end try
    end catching

    final private[kyo] class Catching(handler: Throwable => Any < Any, _frame: Frame) extends Arrow.Transform[Any, Any, Any]:
        def frame = _frame
        def run[C, S2](v: Any, cont: Arrow[Any, C, S2]): C < (Any & S2) =
            val w =
                try cont(`<`.liftSlow(v))
                catch
                    case ex if NonFatal(ex) =>
                        KyoException.attach(ex, "catching", _frame)
                        return handler(ex).asInstanceOf[C < (Any & S2)]
            (w: Any) match
                case kyo: Kyo[?, ?] =>
                    // re-arm across the park so later steps stay intercepted
                    kyo.prepend(this).asInstanceOf[C < (Any & S2)]
                case _ =>
                    w
            end match
        end run
    end Catching

    /** Suspends a computation so it runs when driven, not when constructed.
      *
      * The thunk is evaluated each time the resulting computation is driven past this point, on the driver's stack, inside the trampoline.
      * This is the kernel's lazy primitive: side effects wrapped in `defer` do not run at construction time.
      */
    @nowarn("msg=anonymous")
    private[kyo] inline def defer[A, S](inline f: => A < S)(using inline _frame: Frame): A < S =
        // TODO can't we have defer with just an Arrow or even just an abstract method like the existing old kernel?
        val thunk = new Arrow.Transform[Unit, A, S]:
            def frame = _frame
            def run[C, S2](v: Any, cont: Arrow[A, C, S2]): C < (S & S2) =
                cont(f)
        Kyo.Defer((), thunk).asInstanceOf[A < S]
    end defer

    /** Acquires a resource, uses it, and guarantees release.
      *
      * `release` runs exactly once when `use` completes, fails with an exception, or when the computation is discarded after a park. If the
      * computation suspends while the resource is held, the release is carried in the parked continuation and runs when the resumed
      * computation completes or is discarded.
      */
    @nowarn("msg=anonymous")
    private[kyo] inline def bracket[R, A, S](
        inline acquireF: => R < S
    )(
        inline releaseF: R => Unit < S
    )(
        inline useF: R => A < S
    )(using inline _frame: Frame): A < S =
        new Kyo.Bracket[R, A, S]:
            val useArrow: Arrow[R, A, S] =
                new Arrow.Transform[R, A, S]:
                    def frame = _frame
                    def run[C, S2](v: Any, cont: Arrow[A, C, S2]): C < (S & S2) =
                        cont(useF(v.asInstanceOf[R]))
            def acquire       = acquireF
            def release(r: R) = releaseF(r)
            def cont          = useArrow
            def frame         = _frame
        .asInstanceOf[A < S]

end Effect
