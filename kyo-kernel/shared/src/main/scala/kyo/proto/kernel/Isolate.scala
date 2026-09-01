package kyo.proto.kernel

import kyo.Ansi.*
import kyo.Frame
import kyo.proto.Arrow
import kyo.proto.kernel.internal.Handler
import kyo.proto.kernel.internal.Kyo
import kyo.proto.kernel.internal.Nested
import kyo.proto.kernel.internal.Pending
import kyo.proto.kernel.internal.Stack
import scala.quoted.*

abstract class Isolate[Remove, -Keep, -Restore]:
    self =>

    type State

    type Transform[_]

    def capture[A, S](f: State => A < S)(using Frame): A < (Remove & S)

    def isolate[A, S](state: State, v: A < (S & Remove))(using Frame): Transform[A] < (Keep & S)

    def restore[A, S](v: Transform[A] < S)(using Frame): A < (Restore & S)

    def nest[A, S](v: A < (Remove & S))(using Frame): A < Restore < (Remove & Keep & S) =
        capture { state =>
            isolate(state, v).map(r => Nested.nest[A < Restore, Any](restore(r)))
        }

    final def run[A, S](v: A < (S & Remove))(using Frame): A < (S & Remove & Keep & Restore) =
        capture(state => run(state, v))

    def run[A, S](state: State, v: A < (S & Remove))(using Frame): A < (Keep & Restore & S) =
        restore(isolate(state, v))

    final def apply[A, S](v: A < (Remove & S))[B, S2](f: (A < (Restore & Keep & S)) => B < S2)(using
        Frame
    ): B < (Remove & Keep & S2) =
        capture(state => f(run(state, v)))

    final def use[A](f: this.type ?=> A): A = f(using this)

    final def andThen[RM2, KP2, RS2](next: Isolate[RM2, KP2, RS2]): Isolate[Remove & RM2, Keep & KP2, Restore & RS2] =
        new Isolate[Remove & RM2, Keep & KP2, Restore & RS2]:
            type State        = (self.State, next.State)
            type Transform[A] = self.Transform[next.Transform[A]]
            def capture[A, S](f: State => A < S)(using Frame) =
                self.capture(s1 => next.capture(s2 => f((s1, s2))))
            def isolate[A, S](state: State, v: A < (S & (Remove & RM2)))(using Frame) =
                self.isolate(state._1, next.isolate(state._2, v))
            def restore[A, S](v: Transform[A] < S)(using Frame) =
                next.restore(self.restore(v))

end Isolate

object Isolate:

    def apply[Remove, Keep, Restore](using i: Isolate[Remove, Keep, Restore]): Isolate[Remove, Keep, Restore] = i

    inline def derive[Remove, Keep, Restore]: Isolate[Remove, Keep, Restore] = ${ internal.deriveImpl[Remove, Keep, Restore] }

    inline given [Remove, Keep, Restore <: Remove]: Isolate[Remove, Keep, Restore] = ${ internal.deriveImpl[Remove, Keep, Restore] }

    private[kyo] object internal:

        private[kernel] object Contextual extends Isolate[Any, Any, Any]:
            type State        = Stack.Snapshot
            type Transform[A] = (Stack.Snapshot, Stack.Snapshot, A)

            def capture[A, S](f: Stack.Snapshot => A < S)(using _frame: Frame): A < S =
                new Kyo.SnapshotWith[A, S]:
                    override def frame = _frame
                    def cont           = this
                    override def apply[C, S2](v: Stack.Snapshot < S2, cont2: Arrow[A, C, S2]) =
                        v match
                            case p: Pending[Stack.Snapshot, S2] @unchecked => Effect.defer(p, this, cont2)
                            case _                                         => cont2(f(Nested.unnest[Stack.Snapshot](v)), Arrow.id)

            def isolate[A, S](state: Stack.Snapshot, v: A < S)(using Frame): (Stack.Snapshot, Stack.Snapshot, A) < S =
                val forked = fork(state)
                // The child's final region states are read before the park exits, so join sees
                // where the child ended, not only where it started.
                val inner: (Stack.Snapshot, A) < S  = v.map(a => capture(finals => (finals, a)))
                val parked: (Stack.Snapshot, A) < S = Kyo.Park[(Stack.Snapshot, A), S](inner.asInstanceOf[Any < Any], forked)
                parked.map((finals, a) => (forked, finals, a))
            end isolate

            def restore[A, S](v: (Stack.Snapshot, Stack.Snapshot, A) < S)(using _frame: Frame): A < S =
                v.map { (forked, finals, a) =>
                    new Kyo.SnapshotWith[A, S]:
                        override def frame = _frame
                        def cont           = this
                        override def apply[C, S2](cur: Stack.Snapshot < S2, cont2: Arrow[A, C, S2]) =
                            cur match
                                case p: Pending[Stack.Snapshot, S2] @unchecked => Effect.defer(p, this, cont2)
                                case _ =>
                                    val av: A < Any = a
                                    merge(forked, finals, Nested.unnest[Stack.Snapshot](cur)) match
                                        case kyo.Maybe.Present(joined) =>
                                            // The continuation delivers inside the park, so the
                                            // joined bindings shadow the rest of the parent's
                                            // extent up to the enclosing region boundary.
                                            Kyo.Park[C, S2](cont2(av, Arrow.id).asInstanceOf[Any < Any], joined)
                                        case _ =>
                                            cont2(av, Arrow.id)
                                    end match
                }

            // Park currency: the snapshot carries erased handlers and states, so the casts
            // reinterpret at that boundary and check nothing at runtime. fork is pure and runs
            // strictly; a fold where every region keeps its state by reference shares the
            // origin snapshot.
            private def fork(entries: Stack.Snapshot): Stack.Snapshot =
                if entries.isEmpty then entries
                else
                    val out     = Stack.Snapshot.Builder(entries.regions)
                    var changed = false
                    var i       = 0
                    while i < entries.regions do
                        val parent = entries.state(i)
                        val child =
                            entries.handler(i).asInstanceOf[Handler.ContextHandler[Any, ContextEffect[Any], Any, Any]].fork(parent)
                        out.add(entries.handler(i), child)
                        if child.asInstanceOf[AnyRef] ne parent.asInstanceOf[AnyRef] then changed = true
                        i += 1
                    end while
                    if changed then out.result() else entries
            end fork

            // The pure merge: joins run in entry order; each forked region's parent is the
            // topmost live region with the same handler, so chained restores read through
            // earlier merges, and a region the parent has already exited is skipped. Only
            // bindings the join actually moved install; Absent means nothing changed.
            private def merge(forked: Stack.Snapshot, finals: Stack.Snapshot, current: Stack.Snapshot): kyo.Maybe[Stack.Snapshot] =
                var out = kyo.Maybe.empty[Stack.Snapshot.Builder]
                var i   = 0
                while i < forked.regions do
                    val hc = forked.handler(i).asInstanceOf[Handler.ContextHandler[Any, ContextEffect[Any], Any, Any]]
                    var j  = current.regions - 1
                    while j >= 0 && !(current.handler(j) eq hc) do j -= 1
                    if j >= 0 then
                        val parent = current.state(j)
                        var child  = forked.state(i)
                        var k      = finals.regions - 1
                        while k >= 0 do
                            if finals.handler(k) eq hc then
                                child = finals.state(k)
                                k = -1
                            else k -= 1
                        end while
                        val joined = hc.join(parent, forked.state(i), child)
                        if joined.asInstanceOf[AnyRef] ne parent.asInstanceOf[AnyRef] then
                            val builder =
                                out match
                                    case kyo.Maybe.Present(builder) => builder
                                    case _ =>
                                        val builder = Stack.Snapshot.Builder(forked.regions)
                                        out = kyo.Maybe(builder)
                                        builder
                            builder.add(hc, joined)
                        end if
                    end if
                    i += 1
                end while
                out.map(_.result())
            end merge
        end Contextual

        def deriveImpl[Remove: Type, Keep: Type, Restore: Type](using Quotes): Expr[Isolate[Remove, Keep, Restore]] =
            import quotes.reflect.*

            def flatten(tpe: TypeRepr): List[TypeRepr] =
                tpe match
                    case AndType(left, right)        => flatten(left) ++ flatten(right)
                    case t if t =:= TypeRepr.of[Any] => Nil
                    case t                           => List(t)

            val keep = flatten(TypeRepr.of[Keep])

            val isolates =
                flatten(TypeRepr.of[Remove])
                    .filterNot(t => keep.exists(t =:= _))
                    .filterNot(_ <:< TypeRepr.of[ContextEffect[Any]])
                    .map { t =>
                        t.asType match
                            case '[tpe] =>
                                t -> Expr.summon[Isolate[tpe, Keep, Restore]]
                    }

            val missing = isolates.filter(_._2.isEmpty).map(_._1)

            if missing.nonEmpty then
                report.errorAndAbort(
                    s"""|This operation requires isolation for effects:
                        |
                        |  ${missing.map(_.show.red).mkString(" & ")}
                        |
                        |Common mistake: Using effects in parallel operations without handling how their state
                        |should be managed across boundaries.
                        |
                        |You have a few options, from simplest to most advanced:
                        |
                        |1. Handle these effects before the operation:
                        |   Async.foreach(parallelism)(tasks.map(MyEffect.run(_)))
                        |
                        |2. Some effects like Var and Emit provide options through their isolate object:
                        |   Var.isolate.update[Int].use {
                        |     Async.foreach(parallelism)(tasks)
                        |   }
                        |
                        |3. For multiple effects, compose isolates with andThen:
                        |   Var.isolate.update[Int]
                        |     .andThen(Emit.isolate.merge[String])
                        |     .use {
                        |       Async.foreach(parallelism)(tasks)
                        |     }
                        |
                        |4. For custom state management:
                        |   val isolate = new Isolate[MyEffect, Any, Any] {
                        |     type State = MyState        // Your effect's state
                        |     type Transform[A] = (State, A)
                        |     ...
                        |   }
                        |   isolate.use {
                        |     Async.foreach(parallelism)(tasks)
                        |   }
                        |
                        |Failed to materialize `Isolate[${TypeRepr.of[Remove].show}, ${TypeRepr.of[Keep].show}, ${TypeRepr.of[
                           Restore
                       ].show}]`.
                        |""".stripMargin
                )
            end if

            isolates.flatMap(_._2).foldLeft('{ Contextual.asInstanceOf[Isolate[Remove, Keep, Restore]] })((prev, next) =>
                '{ $prev.andThen($next.asInstanceOf[Isolate[Remove, Keep, Restore]]) }
            )
        end deriveImpl
    end internal

end Isolate
