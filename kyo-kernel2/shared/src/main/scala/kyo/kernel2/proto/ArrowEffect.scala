package kyo.kernel2.proto

import kyo.Tag

/** An effect whose operations take an `I[C]` and answer with an `O[C]`, interpreted by a [[Handler]] installed with one of the `handle`
  * methods below. Each method wraps a typed region node; nothing evaluates at installation.
  */
abstract class ArrowEffect[I[_], O[_]]

object ArrowEffect:

    def suspend[I[_], O[_], E <: ArrowEffect[I, O], A](tag: Tag[E], input: I[A]): O[A] < E =
        Suspend(tag, input)

    /** Deep handler, continuation as a function (ctl format). */
    def handle[I[_], O[_], E <: ArrowEffect[I, O], A, S](effectTag: Tag[E], v: A < (E & S))(
        clause: [C] => (I[C], O[C] => A < (E & S)) => A < (E & S)
    ): A < S =
        Handled[I, O, E, A, A, S](v, Handler.Cont[I, O, E, A, S](effectTag, clause))

    /** Deep handler that answers each operation in place (fun format): no continuation is ever produced. */
    def handleResume[I[_], O[_], E <: ArrowEffect[I, O], A, S](effectTag: Tag[E], v: A < (E & S))(
        clause: [C] => I[C] => O[C] < (E & S)
    ): A < S =
        Handled[I, O, E, A, A, S](v, Handler.Resume[I, O, E, A, S](effectTag, clause))

    /** Deep handler that ends the region at each operation (final ctl format): the remainder is discarded, never captured. */
    def handleStop[I[_], O[_], E <: ArrowEffect[I, O], A, S](effectTag: Tag[E], v: A < (E & S))(
        clause: [C] => I[C] => A < (E & S)
    ): A < S =
        Handled[I, O, E, A, A, S](v, Handler.Stop[I, O, E, A, S](effectTag, clause))

    /** Shallow handler: the first operation only, then the handler leaves. */
    def handleFirst[I[_], O[_], E <: ArrowEffect[I, O], A, B, S](effectTag: Tag[E], v: A < (E & S))(
        clause: [C] => (I[C], O[C] => A < (E & S)) => B < S,
        done: A => B < S
    ): B < S =
        Handled[I, O, E, A, B, S](v, Handler.First[I, O, E, A, B, S](effectTag, clause, done))

    /** Deep stateful handler: state threads through the operations, `done` runs on completion. */
    def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, State](effectTag: Tag[E], state: State, v: A < (E & S))(
        clause: [C] => (I[C], State, O[C] => A < (E & S)) => Handler.Loop.Outcome[State, A < (E & S), B] < S,
        done: (State, A) => B < S
    ): B < S =
        Handled[I, O, E, A, B, S](v, Handler.Loop[I, O, E, A, B, S, State](effectTag, state, clause, done))

end ArrowEffect
