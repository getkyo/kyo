package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Subject")
class JsSubject[A](@JSName("$subj") val underlying: Subject[A]) extends js.Object:
    import kyo.JsFacadeGivens.given
    def ask[B](f: Function1[Subject[B], A]) =
        new JsKyo(underlying.ask(f))

    def send(message: A) =
        new JsKyo(underlying.send(message))

    def trySend(message: A) =
        new JsKyo(underlying.trySend(message))


end JsSubject

object JsSubject:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def init[A](queue: Queue.Unbounded[A]) =
        new JsSubject(Subject.init(queue))

    @JSExportStatic
    def init[A](send: ContextFunction1[Frame, Function1[A, `<`[Unit, `&`[Async, Abort[Closed]]]]], trySend: ContextFunction1[Frame, Function1[A, `<`[Boolean, `&`[Sync, Abort[Closed]]]]]) =
        new JsSubject(Subject.init(send, trySend))

    @JSExportStatic
    def noop[A]() =
        new JsSubject(Subject.noop)


end JsSubject