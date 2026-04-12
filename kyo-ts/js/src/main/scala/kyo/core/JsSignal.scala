package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Signal")
class JsSignal[A](@JSName("$sign") val underlying: Signal[A]) extends js.Object:
    import kyo.JsFacadeGivens.given
    def asRef() =
        new JsKyo(underlying.asRef)

    def current() =
        new JsKyo(underlying.current)

    def currentWith[B, S](f: Function1[A, `<`[B, S]]) =
        new JsKyo(underlying.currentWith(f))

    def flatMap[B](f: Function1[A, Signal[B]]) =
        new JsSignal(underlying.flatMap(f))

    def map[B](f: Function1[A, B]) =
        new JsSignal(underlying.map(f))

    def next() =
        new JsKyo(underlying.next)

    def nextWith[B, S](f: Function1[A, `<`[B, S]]) =
        new JsKyo(underlying.nextWith(f))

    def streamChanges() =
        new JsStream(underlying.streamChanges)

    def streamCurrent() =
        new JsStream(underlying.streamCurrent)

    def zip[B](other: JsSignal[B]) =
        new JsSignal(underlying.zip(other.underlying))

    def zipLatest[B](other: JsSignal[B]) =
        new JsSignal(underlying.zipLatest(other.underlying))


end JsSignal

object JsSignal:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def awaitAny(signals: Seq[Signal[?]]) =
        new JsKyo(Signal.awaitAny(signals))

    @JSExportStatic
    def collectAll[A](signals: Seq[Signal[A]]) =
        new JsSignal(Signal.collectAll(signals))

    @JSExportStatic
    def collectAllLatest[A](signals: Seq[Signal[A]]) =
        new JsSignal(Signal.collectAllLatest(signals))

    @JSExportStatic
    def initConst[A](value: A) =
        new JsSignal(Signal.initConst(value))

    @JSExportStatic
    def initConstWith[A, B, S](value: A, f: Function1[Signal[A], `<`[B, S]]) =
        new JsKyo(Signal.initConstWith(value)(f))

    @JSExportStatic
    def initRef[A](initial: A) =
        new JsKyo(Signal.initRef(initial))

    @JSExportStatic
    def initRefWith[A, B, S](initial: A, f: Function1[Signal.SignalRef[A], `<`[B, S]]) =
        new JsKyo(Signal.initRefWith(initial)(f))


end JsSignal