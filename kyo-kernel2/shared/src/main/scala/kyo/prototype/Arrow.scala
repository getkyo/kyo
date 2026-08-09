package kyo.prototype

import kyo.Frame
import scala.annotation.tailrec

sealed abstract class Arrow[-A, +B, -S]:

    final def andThen[C, S2](next: Arrow[B, C, S2]): Arrow[A, C, S & S2] =
        if this eq Arrow.empty then next.asInstanceOf[Arrow[A, C, S & S2]]
        else if next eq Arrow.empty then this.asInstanceOf[Arrow[A, C, S & S2]]
        else Arrow.AndThen(this, next.asInstanceOf[Arrow[B, C, S & S2]])

    final private[prototype] def apply[S2](v: A < S2, context: Context, handlers: Handlers): B < (S & S2) =
        Arrow.run(this.asInstanceOf[Arrow[Any, Any, Any]], v.asInstanceOf[Any < Any], context, handlers, Safepoint.get)
            .asInstanceOf[B < (S & S2)]

end Arrow

object Arrow:

    abstract class Transform[-A, +B, -S] extends Arrow[A, B, S]:
        def frame: Frame
        def run(v: A, context: Context, handlers: Handlers): B < S
        override def toString = s"Transform(${frame.position.show})"
    end Transform

    final private[prototype] case class AndThen[-A, B, +C, -S](
        a: Arrow[A, B, S],
        b: Arrow[B, C, S]
    ) extends Arrow[A, C, S]:
        override def toString = s"AndThen($a, $b)"
    end AndThen

    private[prototype] val empty: Arrow[Any, Any, Any] =
        new Transform[Any, Any, Any]:
            def frame                                             = Frame.internal
            def run(v: Any, context: Context, handlers: Handlers) = v.asInstanceOf[Any < Any]

    def apply[A]: Arrow[A, A, Any] = empty.asInstanceOf[Arrow[A, A, Any]]

    def lift[A, B, S](f: A => B < S)(using _frame: Frame): Arrow[A, B, S] =
        new Transform[A, B, S]:
            def frame                                           = _frame
            def run(v: A, context: Context, handlers: Handlers) = f(v)

    @tailrec private def run(
        arrow: Arrow[Any, Any, Any],
        value: Any < Any,
        context: Context,
        handlers: Handlers,
        sp: Safepoint
    ): Any < Any =
        if arrow eq empty then value
        else
            value match
                case kyo: Kyo[Any, Any] @unchecked =>
                    kyo.append(arrow)
                case v =>
                    arrow match
                        case at: AndThen[Any, Any, Any, Any] @unchecked =>
                            at.a match
                                case nested: AndThen[Any, Any, Any, Any] @unchecked =>
                                    run(AndThen(nested.a, nested.b.andThen(at.b)), v, context, handlers, sp)
                                case t: Transform[Any, Any, Any] @unchecked =>
                                    if !sp.enter() then new Kyo.Defer(v.asInstanceOf[Any < Any], arrow)
                                    else
                                        val out =
                                            try t.run(Kyo.unnest(v), context, handlers)
                                            finally sp.exit()
                                        run(at.b, out, context, handlers, sp)
                        case t: Transform[Any, Any, Any] @unchecked =>
                            if !sp.enter() then new Kyo.Defer(v.asInstanceOf[Any < Any], arrow)
                            else
                                val out =
                                    try t.run(Kyo.unnest(v), context, handlers)
                                    finally sp.exit()
                                run(empty, out, context, handlers, sp)

end Arrow
