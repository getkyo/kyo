package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Console")
class JsConsole(@JSName("$cons") val underlying: Console) extends js.Object:
    import kyo.JsFacadeGivens.given
    def checkErrors() =
        new JsKyo(underlying.checkErrors)

    def flush() =
        new JsKyo(underlying.flush)

    def let_[A, S](v: JsKyo[A, S]) =
        new JsKyo(Console.let(underlying)(v.underlying))

    def print(s: JsText) =
        new JsKyo(underlying.print(s.underlying))

    def printErr(s: JsText) =
        new JsKyo(underlying.printErr(s.underlying))

    def printLineErr(s: JsText) =
        new JsKyo(underlying.printLineErr(s.underlying))

    def println(s: JsText) =
        new JsKyo(underlying.println(s.underlying))

    def readLine() =
        new JsKyo(underlying.readLine)

    def unsafe() =
        underlying.unsafe


end JsConsole

object JsConsole:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def apply(unsafe: Console.Unsafe) =
        new JsConsole(Console.apply(unsafe))

    @JSExportStatic
    def checkErrors() =
        new JsKyo(Console.checkErrors)

    @JSExportStatic
    def get() =
        new JsKyo(Console.get)

    @JSExportStatic
    def live() =
        new JsConsole(Console.live)

    @JSExportStatic
    def print[A](v: A) =
        new JsKyo(Console.print(v))

    @JSExportStatic
    def printErr[A](v: A) =
        new JsKyo(Console.printErr(v))

    @JSExportStatic
    def printLine[A](v: A) =
        new JsKyo(Console.printLine(v))

    @JSExportStatic
    def printLineErr[A](v: A) =
        new JsKyo(Console.printLineErr(v))

    @JSExportStatic
    def readLine() =
        new JsKyo(Console.readLine)

    @JSExportStatic
    def use[A, S](f: Function1[Console, `<`[A, S]]) =
        new JsKyo(Console.use(f))

    @JSExportStatic
    def withIn[A, S](lines: Iterable[Predef.String], v: JsKyo[A, S]) =
        new JsKyo(Console.withIn(lines)(v.underlying))


end JsConsole