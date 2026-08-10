package kyo.prototype

import kyo.Frame
import kyo.Tag
import scala.annotation.tailrec

abstract class ContextEffect[A]

object ContextEffect:

    // def suspendWith[A, E <: ContextEffect[A], B, S](tag: Tag[E], default: => A)(f: A => B < S)(using _frame: Frame): B < (E & S) =
    //     new Kyo.Defer[Unit, B, E & S]:
    //         def value = ()
    //         def cont =
    //             new Arrow.Transform[Unit, B, E & S]:
    //                 def frame = _frame
    //                 def apply[C, S2](v: Unit, context: Context, next: Optimized[B, C, S2]): C < (E & S & S2) =
    //                     // f(context.getOrElse(tag, default))
    //                     ???

    // def handle[A, E <: ContextEffect[A], B, S](tag: Tag[E], value: A)(v: B < (E & S))(using frame: Frame): B < S =
    //     val bound = Context.empty.set(tag, value)
    //     @tailrec
    //     def loop(v: B < (E & S)): B < S =
    //         v match
    //             case kyo: Kyo.Defer[?, ?, ?] =>
    //                 val defer = kyo.asInstanceOf[Kyo.Defer[Any, B, E & S]]
    //                 loop(defer.cont(defer.value, bound, Handlers.empty))
    //             case v =>
    //                 v.asInstanceOf[B < S]
    //     val sp    = Safepoint.get
    //     val saved = sp.save()
    //     try loop(v)
    //     finally sp.restore(saved)
    // end handle

end ContextEffect
