package kyo.kernel2.proto

import kyo.Maybe
import kyo.Tag

/** PROTOTYPE of the rotation/handlers design (kernel2-rotation-handlers-design.md).
  *
  * A separate, self-contained implementation of the design for review: typed region nodes, one threaded environment ([[Context]], holding
  * bindings AND handlers), and rotation on suspension. Semantics are pinned against the OLD kernel by the 11 reference programs in
  * RotationProbeMain (jvm test), which runs both implementations in the same process.
  *
  * The execution model is kernel2's, at model scale:
  *   - the strict sync path: `map`/`flatMap` on a settled value run immediately, no node, no drive;
  *   - fusion: every node carries its continuation, and `map`/`flatMap` COMPOSE into it, so a chain of transformations is one node, not a
  *     tree; the drive bounces only at suspension and region boundaries;
  *   - the drive ([[Eval]]) threads the environment and performs the three walks (park with rotation, stop, throw).
  *
  * Reading order: Pending (this file: the pending type and its nodes), Context (the environment), Handler (the five formats),
  * ArrowEffect / ContextEffect / Effect (the public surface), Eval (the drive).
  *
  * Deliberate departures from the real kernel2, the mini-kyo gist's: no automatic lifting (plain values enter via `pure`), no safepoints
  * (no preemption, interruption, tracing, or stack-safety guard), function-composition fusion instead of the Arrow/Offset encoding.
  */
sealed abstract class <[+A, -S]:
    def map[B](f: A => B): B < S
    def flatMap[B, S2](f: A => B < S2): B < (S & S2)
    final def andThen[B, S2](next: => B < S2): B < (S & S2) = flatMap(_ => next)
end <

def pure[A](value: A): A < Any = Pure(value)

extension [A](v: A < Any)
    /** Evaluates to completion. An operation reaching the root unhandled is a defect. */
    def eval: A = Eval.eval(v)

/** A settled value: the strict path. Transformations run immediately, allocate no node, and never enter the drive. */
final private[proto] case class Pure[+A](value: A) extends (A < Any):
    def map[B](f: A => B): B < Any             = Pure(f(value))
    def flatMap[B, S2](f: A => B < S2): B < S2 = f(value)

/** A deferred computation: evaluation is suspended until the drive reaches it (a rescue re-entering the chain, a by-name region body). */
final private[proto] case class Defer[A, S](run: () => A < S) extends (A < S):
    def map[B](f: A => B): B < S                     = Defer(() => run().map(f))
    def flatMap[B, S2](f: A => B < S2): B < (S & S2) = Defer(() => run().flatMap(f))

/** An arrow-effect operation with its fused continuation: transformations compose into `cont`, the node stays one node. */
final private[proto] case class Suspend[I[_], O[_], E <: ArrowEffect[I, O], C, A, S](
    tag: Tag[E],
    input: I[C],
    cont: O[C] => A < S
) extends (A < S):
    def map[B](f: A => B): B < S                     = Suspend(tag, input, o => cont(o).map(f))
    def flatMap[B, S2](f: A => B < S2): B < (S & S2) = Suspend(tag, input, o => cont(o).flatMap(f))
end Suspend

/** A context-effect read with its fused continuation: the degenerate in-place case, answered from a [[Context.Binding]]. */
final private[proto] case class Read[V, E <: ContextEffect[V], A, S](
    tag: Tag[E],
    default: Maybe[() => V],
    cont: V => A < S
) extends (A < S):
    def map[B](f: A => B): B < S                     = Read(tag, default, v => cont(v).map(f))
    def flatMap[B, S2](f: A => B < S2): B < (S & S2) = Read(tag, default, v => cont(v).flatMap(f))
end Read

/** A handler region: scope is structural, the old kernel's wrapper nesting with types. `cont` is the region's fused EXIT continuation,
  * outside the region: transformations after the handler compose into it and cannot leak into the region's scope.
  */
final private[proto] case class Handled[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, C2, S2](
    inner: A < (E & S),
    handler: Handler[I, O, E, A, B, S],
    cont: B => C2 < S2
) extends (C2 < (S & S2)):
    def map[C3](f: C2 => C3): C3 < (S & S2) = Handled[I, O, E, A, B, S, C3, S2](inner, handler, b => cont(b).map(f))
    def flatMap[C3, S3](f: C2 => C3 < S3): C3 < (S & S2 & S3) =
        Handled[I, O, E, A, B, S, C3, S2 & S3](inner, handler, b => cont(b).flatMap(f))
end Handled

/** A context binding region with its fused exit continuation. */
final private[proto] case class Bound[V, E <: ContextEffect[V], A, S, C2, S2](
    inner: A < (E & S),
    tag: Tag[E],
    value: V,
    cont: A => C2 < S2
) extends (C2 < (S & S2)):
    def map[C3](f: C2 => C3): C3 < (S & S2) = Bound[V, E, A, S, C3, S2](inner, tag, value, a => cont(a).map(f))
    def flatMap[C3, S3](f: C2 => C3 < S3): C3 < (S & S2 & S3) =
        Bound[V, E, A, S, C3, S2 & S3](inner, tag, value, a => cont(a).flatMap(f))

    /** rotation re-wrap; the casts are the drive's trampoline currency, typed here where the node's parameters are bound */
    private[proto] def rewrap(inner2: Any < Any, exit: Any => Any < Any): Any < Any =
        Bound[V, E, Any, Any, Any, Any](inner2.asInstanceOf[Any < (E & Any)], tag, value, exit).asInstanceOf[Any < Any]
end Bound

/** A guarded region with its fused exit continuation: throws inside `inner` land in `rescue`, throws in `cont` do not. */
final private[proto] case class Catching[A, S, C2, S2](
    inner: A < S,
    rescue: Throwable => A < S,
    cont: A => C2 < S2
) extends (C2 < (S & S2)):
    def map[C3](f: C2 => C3): C3 < (S & S2)                   = Catching(inner, rescue, a => cont(a).map(f))
    def flatMap[C3, S3](f: C2 => C3 < S3): C3 < (S & S2 & S3) = Catching(inner, rescue, a => cont(a).flatMap(f))
end Catching

/** The clause bracket with its fused exit continuation: runs `inner` under a specific environment (a Resume clause's region-entry scope,
  * design 2.5); `cont` runs back at the operation's site.
  */
final private[proto] case class Scoped[A, S, C2, S2](
    context: Context,
    inner: A < S,
    cont: A => C2 < S2
) extends (C2 < (S & S2)):
    def map[C3](f: C2 => C3): C3 < (S & S2)                   = Scoped(context, inner, a => cont(a).map(f))
    def flatMap[C3, S3](f: C2 => C3 < S3): C3 < (S & S2 & S3) = Scoped(context, inner, a => cont(a).flatMap(f))
end Scoped
