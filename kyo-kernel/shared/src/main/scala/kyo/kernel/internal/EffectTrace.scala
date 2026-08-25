package kyo.kernel.internal

import kyo.Arrow
import kyo.Frame
import kyo.Maybe
import kyo.Tag
import kyo.discard
import kyo.kernel.*
import scala.annotation.tailrec
import scala.collection.mutable.ArrayDeque
import scala.util.control.NonFatal
import scala.util.control.NoStackTrace

/** The effect-level frames of a failure, carried as a suppressed exception on the failure itself.
  *
  * The frames are not recorded while the computation runs. They are reconstructed at the boundary the exception crosses, from the failing
  * value and the eval stack the evaluator is already holding, so nothing is paid when nothing throws. The carrier exists for three reasons
  * and no others: its presence among `getSuppressed` marks an exception as already enriched, it accumulates the reconstructions of every
  * boundary an exception crosses, and `getMessage` renders them for a reader that would rather not parse a stack trace.
  */
// TODO This should become the new KyoException. Analyze what we need and if there are blockers
final class EffectTrace extends Exception(null, null, false, false):

    private[kyo] var elements: Array[StackTraceElement]        = EffectTrace.noElements
    private[kyo] var dropped: Int                              = 0
    private[kyo] var physical: Maybe[Array[StackTraceElement]] = Maybe.Absent

    override def getMessage: String =
        val body = elements.iterator.map(e => s"at $e").mkString("\n")
        if dropped == 0 then s"effect trace:\n$body"
        else s"effect trace:\n$body\n... $dropped more not walked"
    end getMessage

end EffectTrace

/** The class and its two entry points are public because `Eval.apply` is `inline`: its body is re-typechecked at every expansion site,
  * including sites outside package `kyo`, so every symbol the eval names has to be reachable there. The carrier's mutable fields stay
  * `private[kyo]`; no inline method touches them.
  */
object EffectTrace:

    /** One total cap across every boundary the exception crosses. The walk emits innermost first and stops here, so a chain deeper than the
      * cap hides the outer regions, which is the failure mode a truncated Java stack already has.
      */
    private inline def MaxFrames = 64

    private val noElements = new Array[StackTraceElement](0)

    // the eval's spelling for the erased operation types
    private type IX[_]
    private type OX[_]
    private type EX <: ArrowEffect[IX, OX]

    /** The eval was about to run `node`: the node, everything it composes, the continuation the eval folded, and the eval stack are all
      * pending.
      */
    def attach(ex: Throwable, node: Kyo[?, ?], cont: Arrow[?, ?, ?], stack: Stack): Unit =
        reconstruct(ex) { builder =>
            builder.node(node)
            builder.arrow(cont)
            builder.entries(stack)
        }

    /** The eval applied `entry` with `cont` folded behind it. `entry` is walked in its arrow role: the eval already took it apart onto the
      * stack, so its node payload, if it has one, is behind the failure rather than ahead of it.
      */
    def attach(ex: Throwable, entry: Arrow[?, ?, ?], cont: Arrow[?, ?, ?], stack: Stack): Unit =
        reconstruct(ex) { builder =>
            builder.arrow(entry)
            builder.arrow(cont)
            builder.entries(stack)
        }

    /** Runs one reconstruction into the exception's carrier.
      *
      * A fatal error is returned unmodified: the test lives here rather than in a catch guard so that every guarded site rethrows
      * unconditionally and propagation is the same for every exception. A non-fatal failure of the walk itself is dropped, because an
      * exception raised while describing a failure would replace the failure, which is strictly worse than describing nothing.
      */
    private inline def reconstruct(ex: Throwable)(inline fill: Builder => Unit): Unit =
        if NonFatal(ex) then
            try
                val carrier = carrierOf(ex)
                val builder = new Builder(MaxFrames - carrier.elements.length)
                fill(builder)
                builder.installInto(carrier)
            catch case failure if NonFatal(failure) => ()
        end if
    end reconstruct

    /** Writes the accumulated frames into the exception's stack trace, synthesized frames first, then the physical trace with the kernel's
      * plumbing removed.
      *
      * Leading with the synthesized frames means there is no splice position to locate. `NoStackTrace` keeps its carrier and skips the
      * splice: the frames stay readable as data on a value that deliberately has no stack.
      */
    def splice(ex: Throwable): Unit =
        if NonFatal(ex) && !ex.isInstanceOf[NoStackTrace] then
            try
                find(ex) match
                    case Maybe.Present(carrier) if carrier.elements.length > 0 =>
                        val physical =
                            carrier.physical match
                                case Maybe.Present(p) => p
                                case Maybe.Absent =>
                                    val p = ex.getStackTrace.filterNot(isPlumbing)
                                    carrier.physical = Maybe(p)
                                    p
                        ex.setStackTrace(carrier.elements ++ physical)
                    case _ => ()
            catch case failure if NonFatal(failure) => ()
        end if
    end splice

    /** The kernel's own frames, which say only that a computation was being evaluated.
      *
      * The per-site `Arrow.Transform` a user's `map` mints is an anonymous class in the user's own compilation unit carrying the user's line
      * numbers, so it is the most informative physical frame present and is never filtered.
      *
      * With `Eval.apply` inline, the eval's own frames no longer appear under `kyo.kernel.internal.Eval`: `loop` expands into the caller
      * and its physical frames carry the caller's class name, so the first entry below filters nothing at a site that expanded the eval.
      * There is no correct fix here: filtering by a mangled local-method name would be guesswork, and filtering by the caller's own class
      * would delete the frames this design exists to keep.
      */
    private def isPlumbing(e: StackTraceElement): Boolean =
        val cls = e.getClassName
        cls.startsWith("kyo.kernel.internal.Eval") ||
        cls.startsWith("kyo.Arrow") ||
        cls.startsWith("kyo.kernel.ArrowEffect") ||
        cls.startsWith("kyo.kernel.internal.Stack") ||
        cls.startsWith("kyo.kernel.internal.Handler") ||
        cls.startsWith("kyo.kernel.internal.Safepoint") ||
        cls.startsWith("kyo.kernel.internal.Nested") ||
        cls.startsWith("kyo.kernel.Loop") ||
        cls.startsWith("kyo.kernel.Pending$package") || cls.startsWith("kyo.kernel.$less") ||
        cls == "kyo.kernel.Effect" || cls.startsWith("kyo.kernel.Effect$")
    end isPlumbing

    private def find(ex: Throwable): Maybe[EffectTrace] =
        val suppressed = ex.getSuppressed
        @tailrec def loop(i: Int): Maybe[EffectTrace] =
            if i == suppressed.length then Maybe.Absent
            else
                suppressed(i) match
                    case carrier: EffectTrace => Maybe(carrier)
                    case _                    => loop(i + 1)
        loop(0)
    end find

    private def carrierOf(ex: Throwable): EffectTrace =
        // the find and the add must be one step: two threads racing the first attach on a shared
        // exception would otherwise both add a carrier. `addSuppressed` and `getSuppressed` already
        // synchronize on the exception, so this takes the same monitor they do, held a few
        // instructions longer; nothing user-written runs inside it
        ex.synchronized {
            find(ex) match
                case Maybe.Present(carrier) => carrier
                case Maybe.Absent =>
                    val carrier = new EffectTrace
                    ex.addSuppressed(carrier)
                    carrier
        }

    /** A value-position node. An arrow-position node is walked as an arrow, which is what makes a self-referential continuation slot emit
      * one frame and stop instead of re-enqueueing itself forever.
      */
    final private class Node(val kyo: Kyo[?, ?])

    private type Item = Arrow[?, ?, ?] | Node

    /** The reconstruction walk.
      *
      * A node in this kernel can be two things at once, and the eval tells the two apart by position. A value position holds a computation
      * the eval will take apart, so it is walked as a node. An arrow position holds something the eval will only ever apply, so it is
      * walked as an arrow: it contributes its frame, and for a handler its region label, and nothing else. Every self-referential slot the
      * kernel mints is a continuation slot, so it is reached in the arrow role and termination is structural rather than defensive.
      *
      * The cap is the walk's stack-safe carrier: emission stops at it and the worklist never holds more than that many items, so a chain or
      * an eval stack of any depth is bounded, and nothing recurses on the Java stack.
      */
    final private class Builder(budget: Int):

        private val out  = new Array[StackTraceElement](Math.max(0, Math.min(budget, MaxFrames)))
        private val work = new ArrayDeque[Item]

        private var size    = 0
        private var last    = Frame.internal
        private var dropped = 0

        private def full: Boolean = size == out.length

        /** Emits one frame, skipping the shared internal placeholder and collapsing a run of the same frame to one element, which is what a
          * tight loop over a single `map` site produces.
          */
        def frame(f: Frame): Unit =
            if (f ne Frame.internal) && (f ne last) then
                if full then dropped += 1
                else
                    val callee = f.calleeName
                    val cls    = if callee.isEmpty then f.className else s"$callee @ ${f.className}"
                    out(size) = new StackTraceElement(cls, f.callerName, f.position.fileName, f.position.lineNumber)
                    size += 1
                    last = f
                end if
        end frame

        /** Emits one region label. A handle frame carries no source position, only the effect it answers. */
        def region[E](tag: Tag[E]): Unit =
            if full then dropped += 1
            else
                out(size) = new StackTraceElement(tag.show, "handle", null, -1)
                size += 1
                last = Frame.internal
            end if
        end region

        /** A value position. It holds exactly the pending union: a settled value, a node, or a `Nested` payload. An `Arrow` is not an arm of
          * that union and never appears here.
          */
        @tailrec private def pushValue(v: Any): Unit =
            v match
                case n: Nested[?] => pushValue(n.value)
                case k: Kyo[?, ?] => push(new Node(k))
                case _            => ()

        private def push(item: Item): Unit =
            if !((item: AnyRef) eq Arrow.Id) then
                if work.size == MaxFrames then
                    // the innermost pending steps are the ones a reader looks at first,
                    // so a full worklist gives up its outermost entry, not the new one
                    discard(work.removeLast())
                    dropped += 1
                end if
                discard(work.prepend(item))
            end if
        end push

        /** A value-position node: the eval was about to run it. */
        def node(k: Kyo[?, ?]): Unit =
            push(new Node(k))
            drain()
        end node

        /** An arrow-position item: an eval-stack entry, or a continuation the eval folded. */
        def arrow(a: Arrow[?, ?, ?]): Unit =
            push(a)
            drain()
        end arrow

        /** The pending continuation held on the eval stack, innermost first. Index 0 is the entry the eval would apply next: `push`
          * decrements `head`, `pop` reads at `head`, `find` scans upward from 0, and `dump` folds `pos-1` down to 0 so that entry 0 ends up
          * leftmost in the chain. Entries the cap keeps the sweep from reaching are counted as dropped.
          */
        def entries(stack: Stack): Unit =
            val n = stack.size
            @tailrec def loop(i: Int): Unit =
                if i < n then
                    if full then dropped += n - i
                    else
                        arrow(stack.entry(i))
                        loop(i + 1)
            loop(0)
        end entries

        @tailrec private def drain(): Unit =
            if work.isEmpty then ()
            else if full then
                dropped += work.size
                work.clear()
            else
                work.removeHead() match
                    case n: Node =>
                        n.kyo match
                            case s: Kyo.Suspend[IX, OX, EX, ?, ?, ?] @unchecked =>
                                // the operation's own site, then whatever it answers into
                                frame(s.frame)
                                push(s.cont)
                            case h: Kyo.Handle[?, ?, ?, ?, ?] =>
                                // mirrors the eval's Handle arm: cont, then the region label, then the
                                // body, so the body drains first and the label follows it
                                push(h.cont)
                                push(h.handler)
                                pushValue(h.value)
                            case b: Kyo.Binding[?, ?, ?, ?] =>
                                // its payload takes what is bound, which this walk does not have, so the
                                // node contributes its own site and nothing under it
                                frame(b.frame)
                            case _: Kyo.Bindings[?, ?] =>
                                // reads the whole context, and its payload takes it, which this walk does
                                // not have either. It carries no site of its own, so nothing is described
                                ()
                            case d: Kyo.Defer[?, ?, ?, ?] =>
                                push(d.contB)
                                push(d.contA)
                                pushValue(d.value)
                            case c: Kyo.Catching[?, ?] =>
                                // the recovery carries no site of its own, and it is only reached by a
                                // failure that this walk is already describing, so only the guarded body
                                // contributes frames
                                pushValue(c.value)
                            case p: Kyo.Park[?, ?] =>
                                // mirrors the eval's Park arm: the parked entries stand above the value, so
                                // they drain after it, and they go on innermost first the way the stack held
                                // them. The finalizers carry no site of their own and are skipped
                                var i = p.entries.size
                                while i > 0 do
                                    i -= 1
                                    push(p.entries(i))
                                pushValue(p.value)
                    case h: Handler[?, ?, ?, ?] =>
                        // before the Arrow arm: Handler extends Arrow.Transform, and a folded
                        // continuation can contain inner handlers
                        region(h.tag)
                    case c: Arrow.Chain[?, ?, ?, ?] =>
                        push(c.b)
                        push(c.a)
                    case a: Arrow.AndThen[?, ?, ?, ?] =>
                        // a folded run of steps, walked for the same reason a chain is: each link carries the
                        // site of the combinator that made it, and the run holds them in order
                        push(a.cont)
                        push(a.t)
                    case a: Arrow[?, ?, ?] =>
                        frame(a.frame)
                end match
                drain()
            end if
        end drain

        def installInto(carrier: EffectTrace): Unit =
            if size > 0 then
                val fresh = out.slice(0, size)
                carrier.elements = if carrier.elements.length == 0 then fresh else carrier.elements ++ fresh
            end if
            carrier.dropped += dropped
        end installInto

    end Builder

end EffectTrace
