package kyo.kernel2.proto

import kyo.Maybe
import kyo.Tag

/** PROTOTYPE of the rotation/handlers design (kernel2-rotation-handlers-design.md).
  *
  * A separate, self-contained implementation of the design for review: typed region nodes, one threaded environment ([[Context]], holding
  * bindings AND handlers), and rotation on suspension. Semantics are pinned against the OLD kernel by the 11 reference programs in
  * RotationProbeMain (jvm test), which runs both implementations in the same process.
  *
  * Reading order: Pending (this file: the pending type and its nodes), Context (the environment), Handler (the five formats),
  * ArrowEffect / ContextEffect / Effect (the public surface), Eval (the drive with the three walks).
  *
  * Deliberate departures from the real kernel2, kept out so the design stays visible (the same departures as the mini-kyo gist):
  *   - no automatic lifting: plain values enter via `pure`
  *   - no safepoints: no preemption, interruption, or tracing
  *   - no fused chains: [[Transform]] here is kernel2's fused continuation, one node per step
  *   - single effect row per handler (no S/S2 split), no allocation tuning beyond the E2b environment shape
  */
sealed abstract class <[+A, -S]:
    def map[B](f: A => B): B < S                      = flatMap(a => Pure(f(a)))
    def flatMap[B, S2](f: A => B < S2): B < (S & S2)  = Transform(this, f)
    def andThen[B, S2](next: => B < S2): B < (S & S2) = flatMap(_ => next)
end <

def pure[A](value: A): A < Any = Pure(value)

extension [A](v: A < Any)
    /** Evaluates to completion. An operation reaching the root unhandled is a defect. */
    def eval: A = Eval.eval(v)

/** A settled value. */
final private[proto] case class Pure[+A](value: A) extends (A < Any)

/** One continuation step: kernel2's fused chain, as a node. */
final private[proto] case class Transform[A, B, S](v: A < S, f: A => B < S) extends (B < S)

/** An arrow-effect operation, suspended at its site. */
final private[proto] case class Suspend[I[_], O[_], E <: ArrowEffect[I, O], C](tag: Tag[E], input: I[C]) extends (O[C] < E)

/** A context-effect read: the degenerate in-place case, answered from a [[Context.Binding]]. */
final private[proto] case class Read[V, E <: ContextEffect[V]](tag: Tag[E], default: Maybe[() => V]) extends (V < E)

/** A handler region: scope is structural, the old kernel's wrapper nesting with types. Installation discharges the effect from the row,
  * which is its meaning: every operation of `E` inside `inner` is interpreted by `handler`.
  */
final private[proto] case class Handled[I[_], O[_], E <: ArrowEffect[I, O], A, B, S](
    inner: A < (E & S),
    handler: Handler[I, O, E, A, B, S]
) extends (B < S)

/** A context binding region: the value is visible to reads inside `inner`, and to handler clauses whose region it encloses. */
final private[proto] case class Bound[V, E <: ContextEffect[V], A, S](
    inner: A < (E & S),
    tag: Tag[E],
    value: V
) extends (A < S):
    /** rotation re-wrap; the cast is the drive's trampoline currency, typed here where the node's parameters are bound */
    private[proto] def rewrap(v: Any < Any): Any < Any =
        Bound[V, E, Any, Any](v.asInstanceOf[Any < (E & Any)], tag, value).asInstanceOf[Any < Any]
end Bound

/** A guarded region: throws inside `inner` land in `rescue`, throws outside do not. */
final private[proto] case class Catching[A, S](inner: A < S, rescue: Throwable => A < S) extends (A < S)

/** The clause bracket: runs `inner` under a specific environment (a Resume clause's region-entry scope, design 2.5). */
final private[proto] case class Scoped[A, S](context: Context, inner: A < S) extends (A < S)
