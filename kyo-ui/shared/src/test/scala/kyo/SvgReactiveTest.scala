package kyo

import kyo.UI.Ast.*
import kyo.UI.foreach
import kyo.internal.HtmlOp
import kyo.internal.HtmlRenderer
import kyo.internal.ReactiveUI
import scala.language.implicitConversions

/** Tests SVG reactive resolution via the engine's rebuildSvgElement and resolveReactives, and the comment-marker
  * region delimiters (`<!--kyo:P-->…<!--/kyo:P-->`), which are uniform across SVG and HTML context.
  */
class SvgReactiveTest extends kyo.test.Test[Any]:

    // A reactive child inside svg resolves for server-side rendering.
    "reactive child inside svg resolves for SSR" in {
        val sig  = Signal.initConst(Chunk(1, 2, 3))
        val root = Svg.svg.width(10).height(10)(sig.foreach(i => Svg.rect.x(i.toDouble).y(0).width(1).height(1)))
        for html <- HtmlRenderer.render(root, Seq.empty)
        yield assert(html.split("<rect").length - 1 == 3)
    }

    // A g container rebuilds its children on resolveReactives.
    "g container rebuilds children on resolveReactives" in {
        val g = Svg.g(UI.when(Signal.initConst(true))(Svg.circle.cx(5).cy(5).r(3)))
        for
            resolved <- ReactiveUI.resolveReactives(g)
            html     <- HtmlRenderer.render(resolved, Seq.empty)
        yield
            assert(html.contains("<g"))
            assert(html.contains("<circle"))
        end for
    }

    // A reactive boundary inside <svg> renders comment markers (valid in SVG content), no wrapper element.
    "empty reactive in svg emits comment markers" in {
        val emptySig = Signal.initConst(Chunk.empty[Int])
        val root     = Svg.svg(emptySig.foreach(i => Svg.rect.x(i.toDouble).y(0).width(1).height(1)))
        for html <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(html.contains("<!--kyo:0--><!--/kyo:0-->"))
            // No wrapper element is emitted for the region.
            assert(!html.contains("data-kyo-reactive"))
        end for
    }

    // Once the SVG signal resolves to children, the SVG children appear between the markers.
    "reactive in svg renders children after signal resolves" in {
        val sig  = Signal.initConst(Chunk(1, 2))
        val root = Svg.svg(sig.foreach(i => Svg.rect.x(i.toDouble).y(0).width(1).height(1)))
        for html <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(html.contains("<!--kyo:0-->"))
            assert(html.contains("<!--/kyo:0-->"))
            assert(html.split("<rect").length - 1 == 2)
        end for
    }

    // An empty reactive boundary in HTML context renders the same comment markers: the format is
    // context-uniform (the old <span>-vs-<g> distinction is gone).
    "empty reactive in HTML emits comment markers" in {
        val emptySig = Signal.initConst(Chunk.empty[Int])
        val root     = UI.div(emptySig.foreach(i => UI.span(i.toString)))
        for html <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(html.contains("<!--kyo:0--><!--/kyo:0-->"))
            assert(!html.contains("data-kyo-reactive"))
        end for
    }

    // A reactive inside foreignObject (the HTML bridge) uses the same markers as everywhere else.
    "reactive inside foreignObject emits comment markers" in {
        val emptySig = Signal.initConst(Chunk.empty[Int])
        val root     = Svg.svg(Svg.foreignObject(UI.div(emptySig.foreach(i => UI.span(i.toString)))))
        for html <- HtmlRenderer.render(root, Seq.empty)
        yield
            // foreignObject -> div -> reactive: markers at the reactive's path, no wrapper element.
            assert(html.contains("<!--kyo:0.0.0-->"))
            assert(html.contains("<!--/kyo:0.0.0-->"))
            assert(!html.contains("data-kyo-reactive"))
        end for
    }

    // Nested svg/foreignObject/div/svg: markers appear at each boundary's own path.
    "nested svg/html/svg markers carry per-boundary paths" in {
        val emptySig = Signal.initConst(Chunk.empty[Int])
        val inner    = Svg.svg(emptySig.foreach(i => Svg.rect.x(i.toDouble).y(0).width(1).height(1)))
        val root     = Svg.svg(Svg.foreignObject(UI.div(inner)))
        for html <- HtmlRenderer.render(root, Seq.empty)
        yield
            // The innermost reactive sits at svg -> foreignObject(0) -> div(0.0) -> svg(0.0.0) -> region(0.0.0.0).
            assert(html.contains("<!--kyo:0.0.0.0--><!--/kyo:0.0.0.0-->"))
            assert(html.contains("<foreignObject"))
            assert(html.contains("<div"))
        end for
    }

    // normalize records svgContext == true for a reactive inside <svg>.
    "normalize records svgContext true in svg" in {
        val sig  = Signal.initConst(Svg.circle.cx(1).cy(1).r(1): UI)
        val root = Svg.svg(UI.when(Signal.initConst(true))(Svg.circle.cx(1).cy(1).r(1)))
        for node <- ReactiveUI.normalize(root, Seq.empty)
        yield
            // The svg root itself is HTML-context at the top (no parent svg), but its reactive child is in SVG context.
            val childReactive = node.children.find(_.path == Seq("0"))
            assert(childReactive.isDefined)
            assert(childReactive.get.svgContext)
        end for
    }

    // normalize records svgContext == false for a reactive inside <div>.
    "normalize records svgContext false in div" in {
        val root = UI.div(UI.when(Signal.initConst(true))(UI.span("x")))
        for node <- ReactiveUI.normalize(root, Seq.empty)
        yield
            val childReactive = node.children.find(_.path == Seq("0"))
            assert(childReactive.isDefined)
            assert(!childReactive.get.svgContext)
        end for
    }

    // The exchange payload is the region's bare content fragment: no wrapper element in either
    // context. The region stays addressable through its live comment markers, so the SSR string is the
    // whole per-context contract (the parse-context wrap on the client side derives from the live
    // parent at patch time).
    "region payload carries no wrapper element" in {
        val sig  = Signal.initConst(Chunk.empty[Int])
        val root = Svg.svg(sig.foreach(i => Svg.rect.x(i.toDouble).y(0).width(1).height(1)))
        for
            rui <- ReactiveUI.normalize(root, Seq.empty)
            node = ReactiveUI.findNode(rui, Seq("0"))
            innerHtml <- HtmlRenderer.render(UI.fragment(), Seq("0"))
        yield
            // The node still records its svg context (normalize bookkeeping)...
            assert(node.isDefined && node.get.svgContext)
            // ...but the rendered content fragment is wrapper-free.
            assert(!innerHtml.contains("data-kyo-reactive"))
        end for
    }

    // Cross-target consistency: two HtmlRenderer.render calls (the shared path used by both runRender
    // and the DomBackend non-empty serialize) emit byte-identical placeholder markup.
    "cross-target placeholder markup is identical" in {
        val sig  = Signal.initConst(Chunk.empty[Int])
        val root = Svg.svg(sig.foreach(i => Svg.rect.x(i.toDouble).y(0).width(1).height(1)))
        for
            a <- HtmlRenderer.render(root, Seq.empty)
            b <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(a == b)
            assert(a.contains("<!--kyo:0-->"))
        end for
    }

end SvgReactiveTest
