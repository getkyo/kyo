package kyo.kernel2.proto

import kyo.Maybe
import kyo.Tag

/** The drive: one loop, one frame stack, the environment threaded as a local that region entry extends and region exit restores.
  *
  * The loop bounces only at suspension points and region boundaries: settled values evaluate strictly at the call site and never arrive
  * here, and a chain of transformations arrives as one node whose continuation the drive applies once.
  *
  * An operation resolves against the environment at its own execution point (design 2.2):
  *   - a [[Read]] takes its [[Context.Binding]] value in place;
  *   - a Resume-format operation runs its clause in place, bracketed by [[Scoped]] so the clause sees its region's ENTRY scope (2.5);
  *   - a Stop-format operation unwinds to its owning region, discarding without capture;
  *   - Cont/First/Loop operations walk outward: every frame crossed contributes to the captured remainder, and region, binding, guard and
  *     bracket frames re-wrap themselves around it (the rotation law), so re-entry re-establishes environment and guards (2.3, 2.6);
  *   - an operation with no entry in scope walks to the root and becomes the boundary [[Eval.Parked]] handed to the runtime.
  *
  * The trampoline currency is `Any < Any`, the old kernel's OX/IX; the typed recoveries live in [[Handler]]'s bridge methods, the node
  * re-wrap methods, and the binding read, each justified by the tag match that precedes it.
  */
private[kernel2] object Eval:

    /** An operation that reached the root with no entry: the boundary handoff (what handlePartial gives the fiber runtime). */
    final case class Parked(tag: Tag[Any], input: Any, cont: Any => Any < Any)

    // carries a boundary park out of the loop; not an exception, so guards never rescue it
    final private case class ParkSignal(parked: Parked) extends RuntimeException with scala.util.control.NoStackTrace

    /** Each frame carries its region's fused EXIT continuation, applied when the region completes. */
    sealed private trait Frame
    final private case class Enter(handler: Handler[?, ?, ?, ?, ?, ?], exit: Any => Any < Any, saved: Context) extends Frame
    final private case class Bind(node: Bound[?, ?, ?, ?, ?, ?], exit: Any => Any < Any, saved: Context)       extends Frame
    final private case class Guard(rescue: Throwable => Any < Any, exit: Any => Any < Any)                     extends Frame
    final private case class Scope(installed: Context, saved: Context, exit: Any => Any < Any)                 extends Frame

    def eval[A](v: A < Any): A =
        evalPartial(v, Context.empty) match
            case Right(a) => a
            case Left(p)  => throw new IllegalStateException("unhandled effect at the root: " + p.tag)

    def evalPartial[A, S](v: A < S, boundary: Context): Either[Parked, A] =
        var cur: Any < Any         = v.asInstanceOf[Any < Any] // trampoline currency
        var context                = boundary
        var stack: List[Frame]     = Nil
        var out: Either[Parked, A] = null

        def tagMatches(a: Tag[Any], b: Tag[Any]): Boolean =
            val x = a.asInstanceOf[AnyRef]
            val y = b.asInstanceOf[AnyRef]
            (x eq y) || x.equals(y)
        end tagMatches

        // the park walk: starts from the operation's own fused continuation and accumulates
        // outward; crossed frames re-wrap themselves around it (rotation), the owning region's
        // handler receives it, or the root parks
        def parkTo(t: Tag[Any], input: Any, opCont: Any => Any < Any, owned: Boolean): Unit =
            var cont    = opCont
            var s       = stack
            var settled = false
            while !settled do
                s match
                    case Nil =>
                        if owned then throw new IllegalStateException("owning region missing for " + t)
                        stack = Nil
                        throw ParkSignal(Parked(t, input, cont))
                    case f :: rest =>
                        s = rest
                        f match
                            case Scope(installed, saved, exit) =>
                                val c0 = cont
                                cont = a => Scoped[Any, Any, Any, Any](installed, c0(a), exit)
                                context = saved
                            case Guard(rescue, exit) =>
                                // the strict path makes c0(a) run user code at application
                                // time, so it must be DEFERRED into the re-established guard:
                                // the old kernel's try-wrapped continuation application
                                val c0 = cont
                                cont = a => Catching[Any, Any, Any, Any](Defer[Any, Any](() => c0(a)), rescue, exit)
                            case Bind(node, exit, saved) =>
                                val c0 = cont
                                cont = a => node.rewrap(c0(a), exit)
                                context = saved
                            case Enter(h, exit, saved) =>
                                context = saved
                                if tagMatches(h.effectTag.erased, t) then
                                    cur = h.dispatch(input, cont).flatMap(exit)
                                    settled = true
                                else
                                    val c0 = cont
                                    cont = a => h.rewrap(c0(a), exit)
                                end if
                        end match
            end while
            stack = s
        end parkTo

        // the stop walk: discards to the owning region; crossed regions, bindings and guards
        // are dropped and their completion steps never run
        def stopTo(hs: Handler.Stop[?, ?, ?, ?, ?], input: Any, t: Tag[Any]): Unit =
            var s    = stack
            var done = false
            while !done do
                s match
                    case Nil => throw new IllegalStateException("owning region missing for " + t)
                    case f :: rest =>
                        s = rest
                        f match
                            case Enter(h, exit, saved) =>
                                context = saved
                                if tagMatches(h.effectTag.erased, t) then
                                    cur = hs.stopWith(input).flatMap(exit)
                                    done = true
                            case Bind(_, _, saved)  => context = saved
                            case Scope(_, saved, _) => context = saved
                            case _                  => ()
                        end match
            end while
            stack = s
        end stopTo

        // the throw unwind: to the nearest guard; the rescue re-enters THROUGH the computation
        // (deferred via a Defer node), so a throwing rescue is seen by the remaining outer guards
        def unwind(ex: Throwable): Unit =
            var s     = stack
            var found = false
            while !found do
                s match
                    case Nil => throw ex
                    case f :: rest =>
                        s = rest
                        f match
                            case Guard(rescue, exit) =>
                                cur = Defer[Any, Any](() => rescue(ex)).flatMap(exit)
                                found = true
                            case Enter(_, _, saved) => context = saved
                            case Bind(_, _, saved)  => context = saved
                            case Scope(_, saved, _) => context = saved
                        end match
            end while
            stack = s
        end unwind

        def step(): Unit =
            cur match
                case Pure(a) =>
                    stack match
                        case Nil => out = Right(a.asInstanceOf[A])
                        case f :: rest =>
                            stack = rest
                            f match
                                case Enter(h, exit, saved) => context = saved; cur = h.dispatchComplete(a).flatMap(exit)
                                case Bind(_, exit, saved)  => context = saved; cur = exit(a)
                                case Guard(_, exit)        => cur = exit(a) // the guard expires with its region
                                case Scope(_, saved, exit) => context = saved; cur = exit(a)
                            end match
                case d: Defer[?, ?] =>
                    cur = d.run().asInstanceOf[Any < Any]
                case h: Handled[?, ?, ?, ?, ?, ?, ?, ?] =>
                    val region = new Context.Region(h.handler, context)
                    stack = Enter(h.handler, h.cont.asInstanceOf[Any => Any < Any], context) :: stack
                    context = region.clauseContext
                    cur = h.inner.asInstanceOf[Any < Any]
                case b: Bound[?, ?, ?, ?, ?, ?] =>
                    stack = Bind(b, b.cont.asInstanceOf[Any => Any < Any], context) :: stack
                    context = context.set(b.tag.erased, new Context.Binding(b.value))
                    cur = b.inner.asInstanceOf[Any < Any]
                case c: Catching[?, ?, ?, ?] =>
                    stack = Guard(c.rescue.asInstanceOf[Throwable => Any < Any], c.cont.asInstanceOf[Any => Any < Any]) :: stack
                    cur = c.inner.asInstanceOf[Any < Any]
                case sc: Scoped[?, ?, ?, ?] =>
                    stack = Scope(sc.context, context, sc.cont.asInstanceOf[Any => Any < Any]) :: stack
                    context = sc.context
                    cur = sc.inner.asInstanceOf[Any < Any]
                case r: Read[?, ?, ?, ?] =>
                    val rc = r.cont.asInstanceOf[Any => Any < Any]
                    context.resolve(r.tag.erased) match
                        case Maybe.Present(b: Context.Binding) => cur = rc(b.value)
                        case Maybe.Present(_) => throw new IllegalStateException("a handler region under a context tag: " + r.tag)
                        case Maybe.Absent =>
                            r.default match
                                case Maybe.Present(d) => cur = rc(d())
                                case Maybe.Absent     => throw new IllegalStateException("missing binding for " + r.tag)
                    end match
                case op: Suspend[?, ?, ?, ?, ?, ?] =>
                    val t      = op.tag.erased
                    val opCont = op.cont.asInstanceOf[Any => Any < Any]
                    context.resolve(t) match
                        case Maybe.Present(region: Context.Region) =>
                            region.handler match
                                case hr: Handler.Resume[?, ?, ?, ?, ?] =>
                                    // in place: no park, no capture; the clause runs at the
                                    // region's entry scope via the Scoped bracket (2.5), and
                                    // the operation's fused continuation resumes at the site
                                    stack = Scope(region.clauseContext, context, opCont) :: stack
                                    context = region.clauseContext
                                    cur = hr.clauseFor(op.input)
                                case hs: Handler.Stop[?, ?, ?, ?, ?] =>
                                    stopTo(hs, op.input, t)
                                case _ =>
                                    parkTo(t, op.input, opCont, owned = true)
                        case Maybe.Present(_) => throw new IllegalStateException("a binding under an arrow tag: " + t)
                        case Maybe.Absent     => parkTo(t, op.input, opCont, owned = false)
                    end match
        end step

        while out == null do
            try step()
            catch
                case ps: ParkSignal => out = Left(ps.parked)
                case ex: Throwable  => unwind(ex)
        end while
        out
    end evalPartial

end Eval
