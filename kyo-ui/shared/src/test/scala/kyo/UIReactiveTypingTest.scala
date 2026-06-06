package kyo

import kyo.UI.Ast.*
import kyo.UI.foreach
import kyo.UI.render
import kyo.internal.HtmlRenderer
import scala.language.implicitConversions

/** Tests for the reactive-boundary typing: parameterized `Reactive[C]`, `Foreach[A, C]`, `Fragment[C]`,
  * and the narrowed return types on `when`, `render`, `foreach*`, and the Signal-overloaded setters.
  */
class UIReactiveTypingTest extends kyo.test.Test[Any]:

    private def renderHtml(ui: UI)(using Frame): String < Sync =
        HtmlRenderer.render(ui, Seq.empty)

    // UI.when returns Reactive[C] at the static type
    "UI.when returns Reactive typed to its body" in {
        val cond: Signal[Boolean]    = Signal.initConst(true)
        val r: Reactive[SpanElement] = UI.when(cond)(UI.span("x"))
        assert(r.isInstanceOf[Reactive[?]])
        val html = renderHtml(r)
        html.map { h => assert(h.contains("<span")) }
    }

    // Signal.render returns Reactive[C]
    "Signal.render returns Reactive typed to render function result" in {
        val sig                      = Signal.initConst("hello")
        val r: Reactive[SpanElement] = sig.render(s => UI.span(s))
        assert(r.isInstanceOf[Reactive[?]])
    }

    // Element.hidden(Signal) returns Reactive[Self]
    "Element.hidden(Signal) returns Reactive[Self]" in {
        val sig                      = Signal.initConst(false)
        val r: Reactive[SpanElement] = UI.span("x").hidden(sig)
        assert(r.isInstanceOf[Reactive[?]])
    }

    // Element.style(Signal) returns Reactive[Self]
    "Element.style(Signal) returns Reactive[Self]" in {
        val sig                      = Signal.initConst(Style.empty)
        val r: Reactive[SpanElement] = UI.span("x").style(sig)
        assert(r.isInstanceOf[Reactive[?]])
    }

    // UI.when runtime renders true branch
    "UI.when runtime renders true branch" in {
        val cond = Signal.initConst(true)
        val r    = UI.when(cond)(UI.span("visible"))
        val html = renderHtml(r)
        html.map { h => assert(h.contains("<span")) }
    }

    // foreach returns Foreach[A, C] typed to render function result
    "Signal.foreach returns Foreach typed to render function result" in {
        val sig                           = Signal.initConst(Chunk(1, 2, 3))
        val fe: Foreach[Int, SpanElement] = sig.foreach(n => UI.span(n.toString))
        assert(fe.isInstanceOf[Foreach[?, ?]])
    }

    // Checkbox.indeterminate(Signal) returns Reactive[Checkbox]
    "Checkbox.indeterminate(Signal) returns Reactive[Checkbox]" in {
        for ref <- Signal.initRef(true)
        yield
            val r: Reactive[Checkbox] = UI.checkbox.indeterminate(ref: Signal[Boolean])
            assert(r.isInstanceOf[Reactive[?]])
    }

    // when[C] infers C as Svg.Circle in SVG context
    "when infers C in SVG context" in {
        val cond                    = Signal.initConst(true)
        val r: Reactive[Svg.Circle] = UI.when(cond)(Svg.circle.cx(1).cy(1).r(1))
        assert(r.isInstanceOf[Reactive[?]])
        // the typed reactive fits in an svg container
        val svgNode = Svg.svg(r)
        assert(svgNode.isInstanceOf[Svg.Root])
    }

    // implicit lifts return the narrowed Reactive[C]
    "implicit lifts return typed Reactive" in {
        val strSig: Signal[String] = Signal.initConst("x")
        val r1: Reactive[Text]     = strSig
        assert(r1.isInstanceOf[Reactive[?]])
        val spanSig: Signal[SpanElement] = Signal.initConst(UI.span("y"))
        val r2: Reactive[SpanElement]    = spanSig
        assert(r2.isInstanceOf[Reactive[?]])
    }

    // SVG element inherits Signal setter returning Reactive[Self]
    "SVG element inherits Signal setter as Reactive[Self]" in {
        val boolSig                 = Signal.initConst(false)
        val r: Reactive[Svg.Circle] = Svg.circle.cx(1).cy(1).r(1).hidden(boolSig)
        assert(r.isInstanceOf[Reactive[?]])
    }

end UIReactiveTypingTest
