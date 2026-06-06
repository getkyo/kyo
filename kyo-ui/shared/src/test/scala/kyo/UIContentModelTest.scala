package kyo

import kyo.UI.Ast.*
import kyo.UI.foreach
import kyo.UI.render
import kyo.internal.HtmlRenderer
import scala.language.implicitConversions

/** Tests for the content-model boundary: the `HtmlContent` marker trait and the `AsHtmlChild` typeclass.
  *
  * All HTML container `apply` overloads accept `HtmlChildVal*` gated by an `AsHtmlChild` witness (not `UI*`), enforcing
  * that bare SVG primitives and reactive-wrapped SVG nodes are rejected at compile time while all HTML content
  * (Block, Inline, Text, Svg.Root) and their reactive/foreach/fragment wrappers are accepted.
  * SVG containers enforce the `SvgElement`-bounded equivalents on the SVG side.
  */
class UIContentModelTest extends kyo.test.Test[Any]:

    private def renderHtml(ui: UI)(using Frame): String < Sync =
        HtmlRenderer.render(ui, Seq.empty)

    // Identity on Boolean: returns a plain `Boolean`, not a singleton `true`/`false` type, so an
    // `if flag(true) then a else b` genuinely infers the branch union (no constant-folding).
    private def flag(b: Boolean): Boolean = b

    // existing HTML children still compile and render correctly (no regression)
    "existing HTML children compile and render (no regression)" in {
        val sig    = Signal.initConst(Chunk("a", "b"))
        val cond   = Signal.initConst(true)
        val ui: UI = UI.div(UI.span("a"), UI.p("b"), UI.when(cond)(UI.span("c")), sig.foreach(x => UI.li(x)))
        val result = renderHtml(ui)
        result.map { html =>
            assert(html.contains("<span"))
            assert(html.contains("<p"))
            assert(html.contains("<li"))
        }
    }

    // Svg.Root IS HtmlContent and compiles inside a div
    "div accepts Svg.svg (Root extends HtmlContent and Inline)" in {
        val root: HtmlContent = Svg.svg
        val ui                = UI.div(Svg.svg.width(10).height(10))
        assert(ui.isInstanceOf[Div])
    }

    // Svg.Root is ALSO UI.Ast.Inline
    "Svg.Root extends UI.Ast.Inline" in {
        val root: UI.Ast.Inline = Svg.svg
        assert(root.isInstanceOf[Svg.Root])
    }

    // Svg.svg container itself does NOT accept a bare HTML div (compile-fail)
    "Svg.svg rejects a bare HTML div child (compile-fail)" in {
        typeCheckFailure("""
          import kyo.*
          import scala.language.implicitConversions
          Svg.svg(UI.div("x"))
        """)("Required: kyo.Svg.SvgChild")
    }

    // Text extends HtmlContent
    "Text extends HtmlContent" in {
        val t: HtmlContent = Text("hello")
        assert(t.isInstanceOf[Text])
    }

    // Block elements extend HtmlContent
    "Block elements extend HtmlContent" in {
        val d: HtmlContent = Div()
        val p: HtmlContent = P()
        assert(d.isInstanceOf[HtmlContent])
        assert(p.isInstanceOf[HtmlContent])
    }

    // Inline elements extend HtmlContent
    "Inline elements extend HtmlContent" in {
        val s: HtmlContent  = SpanElement()
        val li: HtmlContent = Li()
        assert(s.isInstanceOf[HtmlContent])
        assert(li.isInstanceOf[HtmlContent])
    }

    // foreach over HtmlContent renders inside a div
    "foreach producing HtmlContent renders inside a div" in {
        val sig    = Signal.initConst(Chunk("x", "y"))
        val fe     = sig.foreach(x => UI.li(x))
        val ui     = UI.ul(fe)
        val result = renderHtml(ui)
        result.map { html =>
            assert(html.contains("<ul"))
            assert(html.contains("<li"))
        }
    }

    // UI.hr and UI.br (Void Block/Inline elements) are HtmlContent
    "void elements are HtmlContent (hr, br, input)" in {
        val hr: HtmlContent = Hr()
        val br: HtmlContent = Br()
        assert(UI.div(UI.hr, UI.br, UI.input).isInstanceOf[Div])
    }

    // HTML re-enters SVG via foreignObject
    "HTML re-enters SVG via foreignObject" in {
        // Svg.foreignObject accepts HtmlContent; Svg.g accepts SvgElement (foreignObject is one)
        val fo: Svg.ForeignObject = Svg.foreignObject(UI.div("x"))
        val g                     = Svg.g(fo)
        assert(g.isInstanceOf[Svg.G])
        // bare HTML div is NOT directly accepted in Svg.g
        typeCheckFailure("""
          import kyo.*
          import scala.language.implicitConversions
          Svg.g(UI.div("x"))
        """)("Required: kyo.Svg.SvgChild")
    }

    // A reactive-wrapped SVG node is rejected in div (compile-fail).
    // With the AsHtmlChild typeclass, when(cond)(circle) infers C = Circle and returns
    // Reactive[Circle]; the compiler needs AsHtmlChild[Reactive[Circle]] which requires
    // AsHtmlChild[Circle], which has no given (Circle does not extend HtmlContent).
    "reactive SVG rejected under div (compile-fail)" in {
        typeCheckFailure("""
          import kyo.*
          import scala.language.implicitConversions
          UI.div(UI.when(Signal.initConst(true))(Svg.circle.cx(1).cy(1).r(1)))
        """)("does not conform to upper bound kyo.UI.Ast.HtmlContent")
    }

    // A foreach over SVG fits inside svg, is rejected inside div.
    // With the AsHtmlChild typeclass, foreach returning SVG gives Foreach[Int, Circle];
    // AsHtmlChild[Foreach[Int, Circle]] requires AsHtmlChild[Circle], which has no given.
    "foreach SVG fits svg, rejected in div" in {
        val chunkSig = Signal.initConst(Chunk(1, 2, 3))
        val fe       = chunkSig.foreach(i => Svg.circle.cx(i.toDouble).cy(0).r(1))
        val svgNode  = Svg.svg(fe)
        assert(svgNode.isInstanceOf[Svg.Root])
        typeCheckFailure("""
          import kyo.*
          import scala.language.implicitConversions
          val chunkSig = Signal.initConst(Chunk(1, 2, 3))
          UI.div(chunkSig.foreach(i => Svg.circle.cx(i.toDouble).cy(0).r(1)))
        """)("does not conform to upper bound kyo.UI.Ast.HtmlContent")
    }

    // A fragment of HTML nests transparently inside a div (compiles under AsHtmlChild).
    "fragment of HTML nests in div" in {
        val d = UI.div(UI.fragment(UI.div("a"), UI.span("b")))
        assert(d.isInstanceOf[UI.Ast.Div])
    }

    // A bare SVG primitive has no AsHtmlChild witness, so div(circle) is a compile error.
    "bare SVG rejected under div (compile-fail)" in {
        typeCheckFailure("""
          import kyo.*
          import scala.language.implicitConversions
          UI.div(Svg.circle.cx(1).cy(1).r(1))
        """)("Required: kyo.UI.Ast.HtmlChildVal")
    }

    // A mixed HTML+SVG vararg widens to UI, which has no AsHtmlChild witness (compile-fail).
    "mixed HTML+SVG rejected under div (compile-fail)" in {
        typeCheckFailure("""
          import kyo.*
          import scala.language.implicitConversions
          UI.div(UI.div("a"), Svg.circle.cx(1).cy(1).r(1))
        """)("Required: kyo.UI.Ast.HtmlChildVal")
    }

    // Crux regression: the `if cond then <htmlElem> else UI.empty` idiom infers the branch
    // union `<Elem> | Fragment[Nothing]`. The `AsHtmlChild.emptyOr` given resolves it as a
    // direct container child, so the common "render this element or render nothing" pattern
    // compiles and renders nothing for the empty branch. `flag` returns a non-constant `Boolean`
    // so the if/else genuinely forms the union type rather than constant-folding to a single branch.
    "if/else UI.empty compiles as a direct div child and renders the element" in {
        val ui = UI.div(if flag(true) then UI.span("x").id("x") else UI.empty)
        renderHtml(ui).map(html => assert(html.contains("<span") && html.contains(">x<")))
    }

    "if/else UI.empty renders nothing for the empty branch" in {
        val ui = UI.div(if flag(false) then UI.span("x").id("x") else UI.empty)
        renderHtml(ui).map(html => assert(!html.contains("<span")))
    }

    // In a reactive body the union must be pinned with an explicit type argument on `render`,
    // because `render`'s `C` is inferred independently of the container (it would otherwise widen
    // to `UI`, which has no `AsHtmlChild`). With `render[Elem | Fragment[Nothing]]` the emptyOr
    // given resolves and the element-or-nothing body compiles.
    "signal.render[union] body with else UI.empty compiles and renders the element" in {
        val sig = Signal.initConst(true)
        val ui  = UI.div(sig.render[SpanElement | Fragment[Nothing]](b => if b then UI.span("y").id("y") else UI.empty))
        renderHtml(ui).map(html => assert(html.contains("<span")))
    }

    // The genuinely-reactive "show this element while the signal is true, else nothing" form is
    // `UI.when(signal)(element)`: `when` renders the element when true and `UI.empty` (nothing)
    // otherwise, so no `else UI.empty` union is needed at the call site.
    "UI.when(signal)(element) renders the element and nothing otherwise" in {
        val sig = Signal.initConst(true)
        val ui  = UI.div(UI.when(sig)(UI.span("z").id("z")))
        renderHtml(ui).map(html => assert(html.contains("<span")))
    }

    // The crux fix must NOT open an SVG escape hatch: a `Svg.Circle | Fragment[Nothing]`
    // branch union is still rejected, because the emptyOr given requires AsHtmlChild[A] for the
    // non-empty disjunct and Svg.Circle has no witness. `c` is a non-constant Boolean so the
    // if/else forms the union `Svg.Circle | Fragment[Nothing]` (no constant-folding to one branch).
    "if/else UI.empty with an SVG element branch is rejected under div (compile-fail)" in {
        typeCheckFailure("""
          import kyo.*
          import scala.language.implicitConversions
          def c: Boolean = util.Random.nextBoolean()
          UI.div(if c then Svg.circle.cx(1).cy(1).r(1) else UI.empty)
        """)("Required: kyo.UI.Ast.HtmlChildVal")
    }

end UIContentModelTest
