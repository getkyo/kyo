package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Fiber")
class JsFiber[A, S](@JSName("$fibe") val underlying: Fiber[A, S]) extends js.Object:
    import kyo.JsFacadeGivens.given
    def block[E](timeout: JsDuration) =
        new JsKyo(Fiber.block(underlying)(timeout.underlying))

    def done() =
        new JsKyo(Fiber.done(underlying))

    def flatMap[B, S2](f: Function1[`<`[A, S], `<`[Fiber[B, S2], Sync]]) =
        new JsKyo(Fiber.flatMap(underlying)(f))

    def get[E]() =
        new JsKyo(Fiber.get(underlying))

    def getResult[E]() =
        new JsKyo(Fiber.getResult(underlying))

    def interrupt[E]() =
        new JsKyo(Fiber.interrupt(underlying))

    def interrupt[E](error: Result.Error[E]) =
        new JsKyo(Fiber.interrupt(underlying)(error))

    def interruptDiscard[E](error: Result.Error[E]) =
        new JsKyo(Fiber.interruptDiscard(underlying)(error))

    def map[B](f: Function1[A, `<`[B, Sync]]) =
        new JsKyo(Fiber.map(underlying)(f))

    def mapResult[E, E2, B, S2](f: Function1[Result[E, `<`[A, S]], `<`[Result[E2, `<`[B, S2]], Sync]]) =
        new JsKyo(Fiber.mapResult(underlying)(f))

    def mask() =
        new JsKyo(Fiber.mask(underlying))

    def onComplete[E](f: Function1[Result[E, `<`[A, S]], `<`[Any, Sync]]) =
        new JsKyo(Fiber.onComplete(underlying)(f))

    def onInterrupt[E](f: Function1[Result.Error[E], `<`[Any, Sync]]) =
        new JsKyo(Fiber.onInterrupt(underlying)(f))

    def poll[E]() =
        new JsKyo(Fiber.poll(underlying))

    def unsafe() =
        Fiber.unsafe(underlying)

    def use[E, B, S2](f: Function1[A, `<`[B, S2]]) =
        new JsKyo(Fiber.use(underlying)(f))

    def useResult[E, B, S2](f: Function1[Result[E, `<`[A, S]], `<`[B, S2]]) =
        new JsKyo(Fiber.useResult(underlying)(f))

    def waiters() =
        new JsKyo(Fiber.waiters(underlying))


end JsFiber

object JsFiber:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def fail[E](ex: E) =
        new JsFiber(Fiber.fail(ex))

    @JSExportStatic
    def never() =
        new JsKyo(Fiber.never)

    @JSExportStatic
    def panic(ex: Throwable) =
        new JsFiber(Fiber.panic(ex))

    @JSExportStatic
    def succeed[A](v: A) =
        new JsFiber(Fiber.succeed(v))

    @JSExportStatic
    def unit() =
        new JsFiber(Fiber.unit)


end JsFiber