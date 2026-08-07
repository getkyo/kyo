package kyo.kernel2

import kyo.Frame
import scala.annotation.nowarn

/** A type constructor that ignores its argument: the shape of operations whose input or output does not vary with the operation's
  * type index.
  */
type Const[A] = [B] =>> A

/** The common parent of the two effect kinds.
  *
  * An effect kind is a declaration of what a handler provides. [[ControlEffect]] declares operations, interpreted by a handler clause per
  * operation. [[ContextEffect]] declares a value, provided by a handler for a scope. The kinds differ in boundary semantics: context state
  * is copyable across forks, control state lives in the handler's interpretation.
  */
abstract class Effect

object Effect:

    /** Suspends a computation so it runs when driven, not when constructed.
      *
      * The thunk is evaluated each time the resulting computation is driven past this point, on the driver's stack, inside the trampoline.
      * This is the kernel's lazy primitive: side effects wrapped in `defer` do not run at construction time.
      */
    @nowarn("msg=anonymous")
    inline def defer[A, S](inline f: => A < S)(using inline _frame: Frame): A < S =
        Kyo.Defer(
            (),
            Arrow.of(
                new Arrow.Transform[Unit, A, S]:
                    def frame = _frame
                    def run[C, S2](v: Any, cont: Arrow[A, C, S2]): C < (S & S2) =
                        cont(f)
            )
        ).asInstanceOf[A < S]

    /** Acquires a resource, uses it, and guarantees release.
      *
      * `release` runs exactly once when `use` completes, fails with an exception, or when the computation is discarded after a park. If the
      * computation suspends while the resource is held, the release is carried in the parked continuation and runs when the resumed
      * computation completes or is discarded.
      */
    @nowarn("msg=anonymous")
    inline def bracket[R, A, S](
        inline acquireF: => R < S
    )(
        inline releaseF: R => Unit < S
    )(
        inline useF: R => A < S
    )(using inline _frame: Frame): A < S =
        new Kyo.Bracket[R, A, S]:
            val useArrow: Arrow[R, A, S] =
                Arrow.of(
                    new Arrow.Transform[R, A, S]:
                        def frame = _frame
                        def run[C, S2](v: Any, cont: Arrow[A, C, S2]): C < (S & S2) =
                            cont(useF(v.asInstanceOf[R]))
                )
            def acquire       = acquireF
            def release(r: R) = releaseF(r)
            def cont          = useArrow
            def frame         = _frame
        .asInstanceOf[A < S]

end Effect
