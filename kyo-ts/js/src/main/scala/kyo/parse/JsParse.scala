package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Parse")
class JsParse[In](@JSName("$pars") val underlying: Parse[In]) extends js.Object:
    import kyo.JsFacadeGivens.given

end JsParse

object JsParse:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def any[A]() =
        new JsKyo(Parse.any)

    @JSExportStatic
    def anyIf[A](f: Function1[A, Boolean], errorMessage: Function1[A, Predef.String]) =
        new JsKyo(Parse.anyIf(f)(errorMessage))

    @JSExportStatic
    def anyIn[A](values: Seq[A]) =
        new JsKyo(Parse.anyIn(values*))

    @JSExportStatic
    def anyMatch[A, In](pf: PartialFunction[In, A]) =
        new JsKyo(Parse.anyMatch(pf))

    @JSExportStatic
    def anyNotIn[A](values: Seq[A]) =
        new JsKyo(Parse.anyNotIn(values))

    @JSExportStatic
    def boolean() =
        new JsKyo(Parse.boolean)

    @JSExportStatic
    def decimal() =
        new JsKyo(Parse.decimal)

    @JSExportStatic
    def end[In]() =
        new JsKyo(Parse.end)

    @JSExportStatic
    def firstOf[In, Out, S](parsers: Seq[Function0[`<`[Out, `&`[Parse[In], S]]]]) =
        new JsKyo(Parse.firstOf(parsers))

    @JSExportStatic
    def identifier() =
        new JsKyo(Parse.identifier)

    @JSExportStatic
    def int() =
        new JsKyo(Parse.int)

    @JSExportStatic
    def literal[A](value: A) =
        new JsKyo(Parse.literal(value))

    @JSExportStatic
    def modifyState[Out, In](modify: Function1[ParseState[In], Tuple2[ParseState[In], Maybe[Out]]]) =
        new JsKyo(Parse.modifyState(modify))

    @JSExportStatic
    def position[In]() =
        new JsKyo(Parse.position)

    @JSExportStatic
    def read[In, Out](f: Function1[ParseInput[In], Result[Chunk[ParseFailure], Tuple2[ParseInput[In], Out]]]) =
        new JsKyo(Parse.read(f))

    @JSExportStatic
    def readOne[In, Out](f: Function1[In, Result[Chunk[Predef.String], Out]]) =
        new JsKyo(Parse.readOne(f))

    @JSExportStatic
    def readWhile[A](f: Function1[A, Boolean]) =
        new JsKyo(Parse.readWhile(f))

    @JSExportStatic
    def regex(pattern: scala.util.matching.Regex) =
        new JsKyo(Parse.regex(pattern))

    @JSExportStatic
    def whitespaces() =
        new JsKyo(Parse.whitespaces)


end JsParse