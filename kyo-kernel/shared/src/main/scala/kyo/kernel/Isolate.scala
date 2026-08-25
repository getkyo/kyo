package kyo.kernel

import Isolate.internal.*
import kyo.Ansi.*
import kyo.Arrow
import kyo.Frame
import kyo.Kyo
import kyo.Maybe
import kyo.Maybe.*
import kyo.Span
import kyo.Tag
import kyo.bug
import kyo.kernel.internal.Kyo.Binding
import kyo.kernel.internal.Kyo.Bindings
import kyo.kernel.internal.Kyo.Park
import scala.annotation.nowarn
import scala.quoted.*

/** Provides mechanisms for handling pending effects when forking computations.
  *
  * Isolate enables proper state management across execution boundaries like fibers, parallel operations, and detached computations. When
  * forking execution, effects need special handling to prevent state leakage, ensure consistency, and determine what effects are available
  * after the fork completes.
  *
  * The abstraction uses three type parameters to precisely control effect flow:
  *   - `Remove`: Effects that will be satisfied (handled) by the isolation
  *   - `Keep`: Effects that remain available during isolated execution
  *   - `Restore`: Effects that become available after isolation completes
  *
  * This design unifies two categories of state management:
  *
  * **Simple State Copying** is used for [[ContextEffect]]s, whose bindings are inherited by a forked computation as-is. These are effects
  * like environment variables, configuration settings, or local values: pieces of state that can simply be carried over when the
  * computation forks.
  *
  * **Complex State Management** handles effects that require structured transformation. When forking a computation with these effects, the
  * isolation:
  *
  *   1. Captures a snapshot of the current state
  *   2. Transforms that state during isolated execution
  *   3. Restores the transformed state when the fork completes
  *
  * #### Operations
  *
  * Operations specify their isolation requirements through the type parameters. For example:
  *   - `Fiber.init` requires `Isolate[S, Sync, S2]` - only Sync effects available during initialization
  *   - `Async.foreach` might use `Isolate[S, Abort[E] & Async, S]` - async operations available, same effects restored
  *
  * The distinction between `Remove` and `Restore` is crucial: it allows operations to transform effects during isolation. A fiber might
  * capture `Var[Int]` effects but only restore the final value, not intermediate updates.
  *
  * Most effects provide implicit instances based on their state management needs. Some effects like Var and Emit provide multiple
  * strategies through a dedicated isolate object, allowing users to choose between updating final values, merging changes, or keeping
  * modifications local.
  *
  * For effect intersections like (Env[Config] & Var[State]), instances are automatically derived if each component effect has an instance.
  *
  * #### Explicit Composition
  *
  * While automatic derivation works for most cases, you can explicitly compose isolate instances for precise control over state handling:
  * {{{
  *  // Compose specific isolation strategies
  *  val isolate =
  *     Var.isolate.update[Int]                         // Update final Int value
  *         .andThen(Emit.isolate.merge[String])        // Preserve String emissions
  *
  *  // Use in operations that accept isolation
  *  isolate.use {
  *    Async.foreach(parallelism)(tasks)
  *  }
  * }}}
  *
  * This explicit composition is useful when:
  *
  *   1. The order of state capture/restore matters
  *   2. You need specific isolation strategies for each effect
  *   3. The default derived instance doesn't handle effects as desired
  *
  * **Important**: Effects that short circuit execution like Abort and Choice should not provide isolation since the ordering of the
  * handling in the automatic derivation could produce different results depending on which effect is handled first.
  *
  * @tparam Remove
  *   Effects that will be satisfied (handled) by the isolation
  * @tparam Keep
  *   Effects that remain available during isolated execution
  * @tparam Restore
  *   Effects that become available after isolation completes
  */
abstract class Isolate[Remove, -Keep, -Restore]:
    self =>

    /** The type of state being managed */
    type State

    /** How state is transformed during isolated execution */
    type Transform[_]

    /** Captures the current state for isolation.
      *
      * This is the first phase of isolation, obtaining the state that will be managed during the isolated execution. The state is read
      * through the effects being isolated, so the row is `Remove & S` and nothing more: capture runs where the fork happens, and a fork is
      * possible from any context that can handle the isolated effects, including ones that cannot handle Keep. Keep effects belong to the
      * later phases, which run inside the fork and at the join.
      *
      * @param f
      *   Function that receives the captured state
      * @return
      *   Computation with Remove and additional effects
      */
    def capture[A, S](f: State => A < S)(using Frame): A < (Remove & S)

    /** Executes a computation with isolated state.
      *
      * This is the second phase where the computation runs in an isolated context. Only Keep effects and additional effects S are available -
      * Remove effects have been captured and isolated. The result is wrapped in Transform to track any state changes.
      *
      * @param state
      *   The captured state from phase 1
      * @param v
      *   The computation to run in isolation
      * @return
      *   Transformed result with only Keep and additional effects
      */
    def isolate[A, S](state: State, v: A < (S & Remove))(using Frame): Transform[A] < (Keep & S)

    /** Restores state after isolated execution.
      *
      * This is the final phase that determines how the transformed state is propagated back. The Transform wrapper is unwrapped and Restore
      * effects become available, which may differ from the original Remove effects.
      *
      * @param v
      *   The transformed computation from phase 2
      * @return
      *   Final result with Restore and additional effects
      */
    def restore[A, S](v: Transform[A] < S)(using Frame): A < (Restore & S)

    /** Isolates 'Remove' effects while exposing them as nested 'Restore' effects.
      *
      * This method "tunnels" the Remove effects through the isolation, transforming them into 'Restore' effects that appear nested in the
      * result type. Unlike 'run' which directly applies the 'Restore' effects, 'nest' preserves them as a nested effect layer.
      *
      * This is useful when you want to isolate effects for a specific operation but need to control when and how the resulting 'Restore'
      * effects are applied in your program.
      *
      * @param v
      *   The computation containing effects to tunnel through isolation
      * @return
      *   A computation with 'Restore' effects nested in the result type
      */
    def nest[A, S](v: A < (Remove & S))(using Frame): A < Restore < (Remove & Keep & S) =
        // Kyo.lift is the deliberate nesting: at its generic position the implicit lift's runtime analysis
        // nests the computation payload exactly once. A concrete `A < Restore` argument cannot take the
        // conversion directly, since the lift's guard rejects syntactically pending types
        capture { state =>
            isolate(state, v).map(r => Kyo.lift[A < Restore, Any](restore(r)))
        }

    /** Runs a computation with full state lifecycle management.
      *
      * Convenience method that composes all three phases: capture, isolate, and restore. This handles the complete isolation lifecycle in
      * one call.
      *
      * @param v
      *   The computation to run with isolation
      * @return
      *   Result with original Remove effects handled and Restore effects available
      */
    final def run[A, S](v: A < (S & Remove))(using Frame): A < (S & Remove & Keep & Restore) =
        capture(state => run(state, v))

    /** Runs a computation against a state already captured, and brings it back.
      *
      * The half of [[run]] that is not the capture, for the callers that cannot have one fused in. A fork
      * captures once and isolates many times, one per branch, so the capture is hoisted above them and each
      * branch takes the state as given. The row is why the split matters rather than being a convenience:
      * what this answers with has no `Remove` in it, so it is what crosses to an evaluation that cannot
      * handle those effects, while the capture's own `Remove` stays with the computation that forked.
      *
      * Overridable, and the composition below is the meaning rather than the implementation: an isolate that
      * can carry a value across without building the `Transform` between the two halves is free to say so
      * here, and every caller gets it without the protocol changing.
      *
      * @param state
      *   what [[capture]] answered with
      * @param v
      *   the computation to run isolated
      */
    def run[A, S](state: State, v: A < (S & Remove))(using Frame): A < (Keep & Restore & S) =
        restore(isolate(state, v))

    /** Prepares a computation to run in an evaluation of its own, crossing everything that crosses.
      *
      * The two halves of isolation, in one operation. The handled effects cross through this instance, which
      * captures their state here and restores it there; the values bound around the fork cross through the
      * bindings themselves, each asked by its own strategy what a forked computation receives. Neither half
      * is optional and neither is the caller's to remember, which is why this is an instance method: an
      * operation that forks needs an isolate, and having one is what makes the crossing complete.
      *
      * What `f` receives is a complete value: the computation with everything it inherited attached, valid in
      * any evaluation and on any thread, however long after this. Nothing is taken from the forking
      * computation, which carries on with what it had.
      *
      * The consumer is fused rather than mapped: the node is the arrow the crossing flows into, so the
      * crossed computation is handed straight to whoever asked for it and is never a value in its own right.
      *
      * @param v
      *   The computation to prepare
      * @param f
      *   What consumes it, given everything it inherited attached
      */
    final def apply[A, S](v: A < (Remove & S))[B, S2](f: (A < (Restore & Keep & S)) => B < S2)(using
        Frame
    ): B < (Remove & Keep & S2) =
        capture(state => f(run(state, v)))

    /** Applies this isolate to a computation that requires it.
      *
      * Provides a more ergonomic way to use isolates with operations:
      * {{{
      * Var.isolate.update[Int].use {
      *   Async.mask {
      *     // computation with isolated Var[Int] effect
      *   }
      * }
      * }}}
      *
      * @param f
      *   The computation requiring this isolate
      * @return
      *   The result of running the computation with this isolate
      */
    final def use[A](f: this.type ?=> A): A = f(using this)

    /** Composes this isolate with another, managing both states.
      *
      * Creates a new isolate that handles the state lifecycles of both this isolate and the next one, maintaining proper ordering and
      * effect tracking. The composition:
      *   - Combines Remove effects: `Remove & RM2`
      *   - Intersects Keep effects: `Keep & KP2`
      *   - Combines Restore effects: `Restore & RS2`
      *
      * @param next
      *   The isolate to compose with this one
      * @return
      *   A new isolate handling both state managements
      */
    final def andThen[RM2, KP2, RS2](next: Isolate[RM2, KP2, RS2]): Isolate[Remove & RM2, Keep & KP2, Restore & RS2] =
        // neither side is dropped, not even `Contextual`: it manages the scope the effects are read in,
        // which every composition carries too, so discarding it would leave a fork that handles something
        // inheriting nothing. It was discardable while it did nothing, and it no longer does nothing
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

    /** Gets the Isolate instance for given effect types. */
    def apply[Remove, Keep, Restore](using i: Isolate[Remove, Keep, Restore]): Isolate[Remove, Keep, Restore] = i

    /** The bindings whose crossings came back, with what the isolation ended holding for each.
      *
      * Each ending binding is matched to the crossed entry it descends from, by origin rather than by tag:
      * two bindings of one tag are the normal case, and a tag would carry a binding the crossing never
      * held. Both spans stand innermost first with their order preserved, so each search starts where the
      * previous match ended and two entries of one origin pair in order.
      */
    private def carried[A](
        crossed: Span[Arrow[?, ?, ?]],
        bindings: Span[Binding[?, ?, ?, ?]],
        held: Span[Maybe[Any]],
        a: A
    ): (Span[Binding[?, ?, ?, ?]], Span[Maybe[Any]], A) =
        val bs     = new Array[Binding[?, ?, ?, ?]](crossed.size)
        val hs     = new Array[Maybe[Any]](crossed.size)
        var w      = 0
        var cursor = 0
        var i      = 0
        while i < bindings.size do
            val j = crossedIndex(crossed, bindings(i).origin, cursor)
            if j >= 0 then
                bs(w) = bindings(i)
                hs(w) = held(i)
                w += 1
                cursor = j + 1
            end if
            i += 1
        end while
        if w == 0 then (Span.empty, Span.empty, a)
        else if w == crossed.size then (Span.fromUnsafe(bs), Span.fromUnsafe(hs), a)
        else
            val bs2 = new Array[Binding[?, ?, ?, ?]](w)
            val hs2 = new Array[Maybe[Any]](w)
            var j   = 0
            while j < w do
                bs2(j) = bs(j)
                hs2(j) = hs(j)
                j += 1
            end while
            (Span.fromUnsafe(bs2), Span.fromUnsafe(hs2), a)
        end if
    end carried

    /** Where the crossed entries hold the one descending from the origin, or -1 where none does. */
    private def crossedIndex(crossed: Span[Arrow[?, ?, ?]], origin: Binding[?, ?, ?, ?], from: Int): Int =
        var i   = from
        var out = -1
        while out < 0 && i < crossed.size do
            crossed(i) match
                case b: Binding[?, ?, ?, ?] if b.origin eq origin => out = i
                case _                                            => ()
            i += 1
        end while
        out
    end crossedIndex

    /** Asks each binding what it holds now that the fork has ended, and writes the answers.
      *
      * A recursion for the reason the fork walk is one: a strategy is a computation, so each answer is
      * awaited before the next is asked, and each runs where its binding was defined. A binding whose
      * crossing did not come back is left alone, having nothing to be joined with.
      *
      * Each binding is paired with the fork's entry descending from it, by origin rather than by tag: two
      * bindings of one tag are the normal case, and a tag would hand an answer to a scope that never
      * crossed. Both spans stand innermost first with their order preserved, so each search starts where
      * the previous match ended.
      *
      * The writes go out together, in one visit, rather than one at a time: a strategy that reads the
      * context would otherwise see it half joined.
      */
    private def join[A, S](
        mine: Span[Binding[?, ?, ?, ?]],
        held: Span[Maybe[Any]],
        forked: Span[Binding[?, ?, ?, ?]],
        forkedHeld: Span[Maybe[Any]],
        i: Int,
        cursor: Int,
        updates: Array[Binding[?, ?, ?, ?]],
        w: Int,
        a: A
    )(using _frame: Frame): A < S =
        if i == mine.size then
            if w == 0 then a
            else
                val ups = new Array[Binding[?, ?, ?, ?]](w)
                var j   = 0
                while j < w do
                    ups(j) = updates(j)
                    j += 1
                new Bindings[A, S]:
                    def updates                                                                    = Span.fromUnsafe(ups)
                    def resume(bindings: Span[Binding[?, ?, ?, ?]], held: Span[Maybe[Any]]): A < S = a
            end if
        else
            // erasure-forced, as in the fork walk: one span holds bindings of every value type
            val binding = mine(i).asInstanceOf[Binding[Any, Nothing, Any, Any]]
            held(i) match
                case Present(h) =>
                    val j = forkedIndex(forked, binding.origin, cursor)
                    if j < 0 then join(mine, held, forked, forkedHeld, i + 1, cursor, updates, w, a)
                    else
                        forkedHeld(j) match
                            case Present(f) =>
                                binding.join(h, f).map { joined =>
                                    updates(w) = frozen(binding, joined)
                                    join(mine, held, forked, forkedHeld, i + 1, j + 1, updates, w + 1, a)
                                }
                            case Absent =>
                                join(mine, held, forked, forkedHeld, i + 1, j + 1, updates, w, a)
                        end match
                    end if
                case Absent =>
                    join(mine, held, forked, forkedHeld, i + 1, cursor, updates, w, a)
            end match
        end if
    end join

    /** Where the fork's bindings hold the one descending from the origin, or -1 where none does. */
    private def forkedIndex(forked: Span[Binding[?, ?, ?, ?]], origin: Binding[?, ?, ?, ?], from: Int): Int =
        var i   = from
        var out = -1
        while out < 0 && i < forked.size do
            if forked(i).origin eq origin then out = i
            i += 1
        out
    end forkedIndex

    /** Asks each binding for its crossing, innermost first.
      *
      * A loop written as a recursion because a crossing is a computation: each answer is awaited before the
      * next is asked, so a strategy that reads or suspends runs where it was defined, with the forking
      * computation's handlers still in place.
      *
      * What is built is a binding of the same name holding what crossed, rather than the one that was asked.
      * The original derives its value from what encloses it, and re-deriving it in the evaluation that resumes
      * the fork would answer with the forking computation's value again, discarding the crossing. Freezing it
      * is what makes `fork` mean anything. The strategies come along, so a fork of the fork asks the same
      * questions.
      */
    private def cross[A, S](
        bindings: Span[Binding[?, ?, ?, ?]],
        held: Span[Maybe[Any]],
        i: Int,
        entries: Array[Arrow[?, ?, ?]],
        states: Array[Maybe[Any]],
        w: Int,
        f: ((Span[Arrow[?, ?, ?]], Span[Maybe[Any]])) => A < S
    )(using _frame: Frame): A < S =
        if i == bindings.size then
            if w == 0 then f((Span.empty, Span.empty))
            else
                // trimmed by hand rather than copied through the array utilities: the slot type is opaque,
                // so it is not one of the shapes they are written for
                val es  = new Array[Arrow[?, ?, ?]](w)
                val sts = new Array[Maybe[Any]](w)
                var j   = 0
                while j < w do
                    es(j) = entries(j)
                    sts(j) = states(j)
                    j += 1
                end while
                f((Span.fromUnsafe(es), Span.fromUnsafe(sts)))
            end if
        else
            // erasure-forced: one span holds the bindings of every value type, and each was written with its
            // own. The value read from the slot is the one this binding put there, so the pair lines up
            val binding = bindings(i).asInstanceOf[Binding[Any, Nothing, Any, Any]]
            if shadowed(entries, w, binding) then cross(bindings, held, i + 1, entries, states, w, f)
            else
                held(i) match
                    case Present(h) =>
                        binding.fork(h).map { crossed =>
                            crossed match
                                case Present(value) =>
                                    entries(w) = frozen(binding, value)
                                    states(w) = Present(value)
                                    cross(bindings, held, i + 1, entries, states, w + 1, f)
                                case Absent =>
                                    cross(bindings, held, i + 1, entries, states, w, f)
                        }
                    case Absent =>
                        cross(bindings, held, i + 1, entries, states, w, f)
                end match
            end if
        end if
    end cross

    /** Whether a crossing for this name was already taken, which the innermost occurrence was.
      *
      * A fork inherits what it can read, and a read takes the innermost binding of a name, so the walk
      * keeps the first occurrence it meets and drops the ones it shadows: the child could never read them,
      * and an extent ending is what discards its layer, so nothing joins them back either.
      */
    private def shadowed(entries: Array[Arrow[?, ?, ?]], w: Int, binding: Binding[?, ?, ?, ?]): Boolean =
        binding.tag match
            case Present(t) =>
                var i   = 0
                var out = false
                while !out && i < w do
                    entries(i) match
                        case b: Binding[?, ?, ?, ?] => out = b.tag.exists(_ =:= t.asInstanceOf[Tag[Any]])
                        case _                      => ()
                    i += 1
                end while
                out
            case Absent => false

    /** A frozen binding, carrying the one whose strategies it answers with.
      *
      * Freezing borrows the strategies rather than defining them, so freezing a frozen binding would layer
      * one borrow on another and every crossing would leave `fork` and `join` another level to walk. A name
      * that crosses on each of many rounds accumulates a level per round, which is a stack overflow rather
      * than a slow walk. Naming the origin keeps the next freeze starting from the binding that owns the
      * strategies, so the borrow is one deep however many times the name crosses.
      */
    abstract private class Frozen(override val origin: Binding[Any, Nothing, Any, Any]) extends Binding[Any, Nothing, Any, Any]

    /** A binding of the same name holding what crossed, with the strategies of the one it came from. */
    @nowarn("msg=anonymous")
    private def frozen(binding: Binding[Any, Nothing, Any, Any], value: Any)(using
        _frame: Frame
    ): Binding[Any, Nothing, Any, Any] =
        val owner =
            binding match
                case f: Frozen => f.origin
                case b         => b
        new Frozen(owner):
            def frame                                 = _frame
            val tag                                   = owner.tag
            val bound                                 = Maybe((_: Maybe[Any]) => value)
            override def fork(held: Any)              = owner.fork(held)
            override def join(held: Any, forked: Any) = owner.join(held, forked)
            // never reached: this is built to stand on a stack, and an entry answers a value flowing back
            // through it with `apply`, which is identity for a binding. Only a binding the eval meets as a
            // computation resumes, and this one is never that
            def resume(held: Maybe[Any]) = bug("a crossed binding was evaluated rather than installed")
        end new
    end frozen

    /** Derives an Isolate instance based on available instances.
      *
      * The derivation automatically composes isolates for intersection types. For example, if isolates exist for `Var[Int]` and
      * `Emit[String]`, it will automatically derive an isolate for `Var[Int] & Emit[String]`.
      *
      * The derived instance will:
      *   - Remove all effects in the Remove type that aren't in Keep
      *   - Only derive if isolates exist for all non-Keep effects in Remove
      *   - Compose isolates using andThen in the order they appear
      */
    inline def derive[Remove, Keep, Restore]: Isolate[Remove, Keep, Restore] = ${ deriveImpl[Remove, Keep, Restore] }

    // `Restore <: Remove` is used to help with implicit resolution. Without it, the compiler infers `Restore` as `Any` in some cases.
    inline given [Remove, Keep, Restore <: Remove]: Isolate[Remove, Keep, Restore] = ${ deriveImpl[Remove, Keep, Restore] }

    private[kyo] object internal:

        /** The isolate of the values bound around a fork, which every fork crosses whatever else it handles.
          *
          * It handles no effect, which is what `Any` in all three positions says, and that makes it the neutral
          * element of composition: what it manages is not an effect but the scope effects are read in, and every
          * composition manages that too. The three phases are the crossing's, and the strategies are the
          * bindings' own:
          *
          *   - capturing reads what is bound here and asks each binding's `fork` what a computation forked from
          *     here receives, keeping what crosses as bindings that hold it
          *   - isolating attaches those to the computation, so running it installs them first, and makes it end
          *     by reading its own bindings back, which is what gives the way home something to carry
          *   - restoring asks each binding's `join` what it holds now that the fork has ended, given what it
          *     holds and what the fork ended with, and writes the answers into the scopes that own them
          */
        // private to the kernel: it is reached through an isolate, never named. Every derived instance
        // folds from it, so an operation that forks crosses the context by holding one
        private[kernel] object Contextual extends Isolate[Any, Any, Any]:

            /** The bindings a forked computation inherits, and what each holds: what a park is made of. */
            type State = (Span[Arrow[?, ?, ?]], Span[Maybe[Any]])

            /** What the fork ended with: the bindings it held at the end, and its value. */
            type Transform[A] = (Span[Binding[?, ?, ?, ?]], Span[Maybe[Any]], A)

            def capture[A, S](f: State => A < S)(using Frame): A < S =
                new Bindings[A, S]:
                    def updates = Span.empty
                    def resume(bindings: Span[Binding[?, ?, ?, ?]], held: Span[Maybe[Any]]): A < S =
                        if bindings.isEmpty then f((Span.empty, Span.empty))
                        else cross(bindings, held, 0, new Array(bindings.size), new Array(bindings.size), 0, f)

            def isolate[A, S](state: State, v: A < S)(using Frame): Transform[A] < S =
                val body =
                    v.map(a =>
                        new Bindings[Transform[A], S]:
                            def updates = Span.empty
                            def resume(bindings: Span[Binding[?, ?, ?, ?]], held: Span[Maybe[Any]]): Transform[A] < S =
                                // only what crossed comes back. A name the isolation read without inheriting
                                // has nothing to be joined with, and a name it bound itself belongs to the
                                // scope that ended with it
                                carried(state._1, bindings, held, a)
                    )
                if state._1.isEmpty then body
                else new Park[Transform[A], S](body, state._1, state._2, Span.fromUnsafe(new Array[Int](state._1.size)), Span.empty)
                end if
            end isolate

            def restore[A, S](v: Transform[A] < S)(using Frame): A < S =
                v.map { t =>
                    val (forked, forkedHeld, a) = t
                    if forked.isEmpty then a
                    else
                        new Bindings[A, S]:
                            def updates = Span.empty
                            def resume(mine: Span[Binding[?, ?, ?, ?]], held: Span[Maybe[Any]]): A < S =
                                if mine.isEmpty then a
                                else join(mine, held, forked, forkedHeld, 0, 0, new Array(mine.size), 0, a)
                    end if
                }
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
