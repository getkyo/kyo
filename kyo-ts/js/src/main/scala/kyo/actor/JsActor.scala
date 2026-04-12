package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Actor")
class JsActor[E, A, B](@JSName("$acto") val underlying: Actor[E, A, B]) extends js.Object:
    import kyo.JsFacadeGivens.given
    def ask[B](f: Function1[Subject[B], A]) =
        new JsKyo(underlying.ask(f))

    def await() =
        new JsKyo(underlying.await)

    def close() =
        new JsKyo(underlying.close)

    def fiber() =
        new JsFiber(underlying.fiber)

    def send(message: A) =
        new JsKyo(underlying.send(message))

    def subject() =
        new JsSubject(underlying.subject)

    def trySend(message: A) =
        new JsKyo(underlying.trySend(message))


end JsActor

object JsActor:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def defaultCapacity() =
        Actor.defaultCapacity

    @JSExportStatic
    def receiveAll[A, S](f: Function1[A, `<`[Any, S]]) =
        new JsKyo(Actor.receiveAll(f))

    @JSExportStatic
    def receiveLoop[A, State1, State2, State3, State4, S](state1: State1, state2: State2, state3: State3, state4: State4, f: Function5[A, State1, State2, State3, State4, `<`[Loop.Outcome4[State1, State2, State3, State4, Tuple4[State1, State2, State3, State4]], S]]) =
        new JsKyo(Actor.receiveLoop(state1, state2, state3, state4)(f))

    @JSExportStatic
    def receiveLoop[A, State1, State2, S](state1: State1, state2: State2, f: Function3[A, State1, State2, `<`[Loop.Outcome2[State1, State2, Tuple2[State1, State2]], S]]) =
        new JsKyo(Actor.receiveLoop(state1, state2)(f))

    @JSExportStatic
    def receiveLoop[A, State, S](state: State, f: Function2[A, State, `<`[Loop.Outcome[State, State], S]]) =
        new JsKyo(Actor.receiveLoop(state)(f))

    @JSExportStatic
    def receiveLoop[A, S](f: Function1[A, `<`[Loop.Outcome[Unit, Unit], S]]) =
        new JsKyo(Actor.receiveLoop(f))

    @JSExportStatic
    def receiveLoop[A, State1, State2, State3, S](state1: State1, state2: State2, state3: State3, f: Function4[A, State1, State2, State3, `<`[Loop.Outcome3[State1, State2, State3, Tuple3[State1, State2, State3]], S]]) =
        new JsKyo(Actor.receiveLoop(state1, state2, state3)(f))

    @JSExportStatic
    def reenqueue[A](msg: A) =
        new JsKyo(Actor.reenqueue(msg))

    @JSExportStatic
    def self[A]() =
        new JsKyo(Actor.self)

    @JSExportStatic
    def selfWith[A, B, S](f: Function1[Subject[A], `<`[B, S]]) =
        new JsKyo(Actor.selfWith(f))


end JsActor