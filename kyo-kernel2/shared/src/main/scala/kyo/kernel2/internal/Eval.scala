package kyo.kernel2.internal

import kyo.kernel2.*
import kyo.kernel2.internal.Kyo
import scala.annotation.tailrec

/** The drive over settled structure: pops Defers, runs brackets, and returns when the computation completes or a suspension
  * crosses every installed handler. The eval extension methods on the pending type are the entry points.
  */
private[kyo] object Eval:

    /** The three drive modes. Preemptible is the slice boundary: it stops on a request and its caller consumes it once at exit.
      * Masked cannot hand back a remainder, so it absorbs requests at each poll and re-issues them at exit. Cascade is an inner drive
      * that neither consumes nor re-issues: it returns the remainder so the request reaches the enclosing slice.
      */
    private[kyo] inline def Preemptible = 0
    private[kyo] inline def Masked      = 1
    private[kyo] inline def Cascade     = 2

    // context and handlers are constants of the drive: parked remainders carry their
    // rotate steps, so plain re-application from the entry parameters reconstructs
    // the in-scope bindings and handlers on every bounce
    private[kyo] def evalLoop(v0: Any < Any, mode: Int, context: Context, handlers: Handlers): Any < Any =
        def recur(v: Any < Any, depth: Int): Any < Any =
            @tailrec def loop(curr: Any < Any): Any < Any =
                // the poll sits at the top of the loop: it covers Defer pops, suspension dispatches, and the bare arm with one
                // site, and every mode must poll, because a drive that ignores a pending request cannot make progress through a
                // lone-transform Defer (every enter refuses and the identical rescue comes back)
                if Safepoint.pollPreempt() then
                    if mode == Masked then Safepoint.maskPreempt()
                    else return curr
                curr match
                    case bracket: Kyo.Bracket[Any, Any, Any] @unchecked =>
                        if depth >= Finalize.BracketDepth then bracket
                        else
                            recur(bracket.acquire, depth + 1) match
                                case suspended: Kyo[Any, Any] @unchecked =>
                                    val wrapped = suspended.map(Finalize.reacquire(bracket))
                                    if depth == 0 then loop(wrapped) else wrapped
                                case acquired =>
                                    val resource = Kyo.unnest(acquired)
                                    val result =
                                        try recur(bracket.cont(acquired, context, handlers), depth + 1)
                                        catch
                                            case t: Throwable =>
                                                Finalize.cleanup(bracket, resource, t)
                                                throw t
                                    result match
                                        case suspended: Kyo[Any, Any] @unchecked =>
                                            val wrapped = suspended.map(new Finalize[Any, Any, Any](bracket, resource))
                                            if depth == 0 then loop(wrapped) else wrapped
                                        case _ =>
                                            // release runs masked, mirroring the current kernel: neither a time slice nor an
                                            // interrupt cuts a finalizer that is already running
                                            Safepoint.maskPreempt()
                                            val released =
                                                try recur(bracket.release(resource), depth + 1)
                                                finally Safepoint.unmaskPreempt()
                                            released match
                                                case suspended: Kyo[Any, Any] @unchecked =>
                                                    val wrapped = suspended.map(Finalize.constant(result))
                                                    if depth == 0 then loop(wrapped) else wrapped
                                                case _ =>
                                                    result
                                            end match
                                    end match
                    case defer: Kyo.Defer[Any, Any, Any] @unchecked =>
                        loop(defer.cont(defer.value, context, handlers))
                    case kyo: Kyo[Any, Any] @unchecked =>
                        // a suspension that reached the drive crossed every installed handler:
                        // the remainder parks (handlePartial's boundary), or eval reports it
                        curr
                    case _ =>
                        curr
                end match
            end loop
            loop(v)
        end recur
        // the drive is a fresh trampoline: it runs with its own depth budget so frames the caller
        // already committed cannot starve it into re-rescuing the same step forever
        val safepoint = Safepoint.get
        val saved     = safepoint.openDrive()
        try
            val result = recur(v0, 0)
            // the Preemptible boundary consumes exactly once, at exit: consuming at the poll site would break the bracket
            // cascade, which decides whether to keep driving by observing that the request is still pending
            if mode == Preemptible then
                val _ = Safepoint.clearPreempt()
            result
        catch
            case ex: Throwable =>
                EffectTrace.install(ex)
                throw ex
        finally
            if mode == Masked then Safepoint.unmaskPreempt()
            safepoint.closeDrive(saved)
        end try
    end evalLoop

end Eval
