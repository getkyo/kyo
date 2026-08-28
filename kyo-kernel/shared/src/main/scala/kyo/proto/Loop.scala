package kyo.proto

import kyo.proto.kernel.internal.Debugger

object Loop:

    abstract class Outcome2[A, B, C]:
        Debugger.get.onAlloc(this)

    final class Continue[State, A, B](val state: State, val value: A) extends Outcome2[State, A, B]:
        override def toString = s"Continue($state, $value)"
    final class Done[State, A, B](val value: B) extends Outcome2[State, A, B]:
        override def toString = s"Done($value)"

    def continue[State, A, B](state: State, value: A): Outcome2[State, A, B] = Continue(state, value)
    def done[State, A, B](value: B): Outcome2[State, A, B]                   = Done(value)
end Loop
