package kyo.proto4

import kyo.Frame
import kyo.Tag

/** The park-time reification of an effect handler: a delimiter in the continuation chain.
  *
  * A delimiter is an ordinary [[Arrow.Transform]]. On values it is at most a completion step (pass-through for most formats, `done` for
  * shallow handlers), so fused chains flow through it transparently. On suspensions the drive searches the chain for the first delimiter
  * whose tag matches the suspension's effect and applies the handler's format: run the clause in place, discard the prefix, or capture the
  * prefix as a first-class [[Arrow]] continuation.
  *
  * Because delimiters are chain elements, the prepend machinery carries installed handlers across parks with no re-wrap: once installed, a
  * handler travels with the computation. Handler clauses are stored erased; the typed public APIs in [[ArrowEffect]] are the only
  * constructors, and each cast in the drive is justified by the tag match that precedes it.
  */
sealed abstract private[kyo] class Handler extends Arrow.Transform[Any, Any, Any]:
    def effectTag: Tag[Any]

private[kyo] object Handler:

    /** Erased shape of a continuation-taking clause. */
    type Clause = [C] => (Any, Arrow[Any, Any, Any]) => Any < Any

    /** Erased shape of an input-only clause. */
    type InputClause = [C] => Any => Any < Any

    /** Deep handler with a first-class continuation (ctl format). */
    final class Cont(val effectTag: Tag[Any], val clause: Clause, val frame: Frame) extends Handler:
        def run[C, S2](v: Any, cont: Arrow[Any, C, S2]): C < (Any & S2) =
            cont(`<`.liftSlow(v))

    /** Deep handler that answers each operation in place (fun format). */
    final class Resume(val effectTag: Tag[Any], val clause: InputClause, val frame: Frame) extends Handler:
        def run[C, S2](v: Any, cont: Arrow[Any, C, S2]): C < (Any & S2) =
            cont(`<`.liftSlow(v))

    /** Deep handler that ends the region at each operation (final ctl format). */
    final class Stop(val effectTag: Tag[Any], val clause: InputClause, val frame: Frame) extends Handler:
        def run[C, S2](v: Any, cont: Arrow[Any, C, S2]): C < (Any & S2) =
            cont(`<`.liftSlow(v))

    /** Erased shape of a stateful loop clause. */
    type LoopClause = [C] => (Any, Any, Arrow[Any, Any, Any]) => Any < Any

    /** Deep stateful handler: state threads through operations, done runs on completion.
      *
      * State evolution never mutates the node: each Outcome.Continue installs a replacement delimiter carrying the next state.
      */
    final class Loop(
        val effectTag: Tag[Any],
        val state: Any,
        val clause: LoopClause,
        val done: (Any, Any) => Any < Any,
        val frame: Frame
    ) extends Handler:
        def run[C, S2](v: Any, cont: Arrow[Any, C, S2]): C < (Any & S2) =
            cont(done(state, v))
    end Loop

    /** Shallow handler: handles only the first operation, then leaves the region. */
    final class First(val effectTag: Tag[Any], val clause: Clause, val done: Any => Any < Any, val frame: Frame) extends Handler:
        def run[C, S2](v: Any, cont: Arrow[Any, C, S2]): C < (Any & S2) =
            cont(done(v))

end Handler
