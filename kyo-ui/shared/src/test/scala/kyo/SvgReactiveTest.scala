package kyo

import kyo.UI.Ast.*
import kyo.UI.foreach
import kyo.internal.HtmlOp
import kyo.internal.HtmlRenderer
import kyo.internal.ReactiveRegion
import kyo.internal.ReactiveUI
import scala.language.implicitConversions

/** Tests SVG reactive resolution via the engine's rebuildSvgElement and resolveReactives, and the `<g>`-based
  * reactive placeholders used for a reactive boundary in SVG context.
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

    // An empty reactive boundary inside <svg> renders a <g> placeholder, not <span>.
    "empty reactive in svg emits <g> placeholder" in {
        val emptySig = Signal.initConst(Chunk.empty[Int])
        val root     = Svg.svg(emptySig.foreach(i => Svg.rect.x(i.toDouble).y(0).width(1).height(1)))
        for html <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(html.contains("<g data-kyo-path=\"0\" data-kyo-reactive>"))
            assert(html.contains("</g>"))
            // No <span> placeholder is emitted inside the svg.
            assert(!html.contains("<span data-kyo-reactive"))
            assert(!html.contains("data-kyo-reactive></span>"))
        end for
    }

    // Once the SVG signal resolves to children, the SVG children appear under the <g> boundary.
    "reactive in svg renders children after signal resolves" in {
        val sig  = Signal.initConst(Chunk(1, 2))
        val root = Svg.svg(sig.foreach(i => Svg.rect.x(i.toDouble).y(0).width(1).height(1)))
        for html <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(html.contains("<g data-kyo-path=\"0\" data-kyo-reactive>"))
            assert(html.split("<rect").length - 1 == 2)
        end for
    }

    // An empty reactive boundary in HTML context renders a logical comment range.
    "empty reactive in HTML emits comment range" in {
        val emptySig = Signal.initConst(Chunk.empty[Int])
        val root     = UI.div(emptySig.foreach(i => UI.span(i.toString)))
        for html <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(html.contains("<!--kyo-rs:r000000010030--><!--kyo-re:r000000010030-->"))
            assert(!html.contains("data-kyo-reactive"))
        end for
    }

    // A reactive inside foreignObject (the HTML bridge) renders an HTML comment range, not <g>.
    "reactive inside foreignObject resets to an HTML range" in {
        val emptySig = Signal.initConst(Chunk.empty[Int])
        val root     = Svg.svg(Svg.foreignObject(UI.div(emptySig.foreach(i => UI.span(i.toString)))))
        for html <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(html.contains("<!--kyo-rs:"))
            assert(html.contains("<!--kyo-re:"))
            // No <g> placeholder boundary carrying data-kyo-reactive is emitted for the bridged reactive.
            assert(!html.contains("data-kyo-reactive></g>"))
        end for
    }

    // Nested svg/foreignObject/div/svg toggles the placeholder tag per boundary.
    "nested svg/html/svg toggles placeholder tag" in {
        val emptySig = Signal.initConst(Chunk.empty[Int])
        val inner    = Svg.svg(emptySig.foreach(i => Svg.rect.x(i.toDouble).y(0).width(1).height(1)))
        val root     = Svg.svg(Svg.foreignObject(UI.div(inner)))
        for html <- HtmlRenderer.render(root, Seq.empty)
        yield
            // The innermost reactive is back in SVG context (inner svg) so it emits a <g> placeholder.
            assert(html.contains("data-kyo-reactive>") && html.contains("</g>"))
            assert(html.contains("<foreignObject"))
            assert(html.contains("<div"))
        end for
    }

    "normalize records an SVG element region inside svg" in {
        val sig  = Signal.initConst(Svg.circle.cx(1).cy(1).r(1): UI)
        val root = Svg.svg(UI.when(Signal.initConst(true))(Svg.circle.cx(1).cy(1).r(1)))
        for node <- ReactiveUI.normalize(root, Seq.empty)
        yield
            // The svg root itself is HTML-context at the top (no parent svg), but its reactive child is in SVG context.
            val childReactive = node.children.find(_.path == Seq("0"))
            assert(childReactive.isDefined)
            assert(childReactive.get.region == ReactiveRegion.SvgElement(Seq("0")))
        end for
    }

    "normalize records an HTML range inside div" in {
        val root = UI.div(UI.when(Signal.initConst(true))(UI.span("x")))
        for node <- ReactiveUI.normalize(root, Seq.empty)
        yield
            val childReactive = node.children.find(_.path == Seq("0"))
            assert(childReactive.isDefined)
            assert(childReactive.get.region == ReactiveRegion.HtmlRange(ReactiveRegion.htmlId(Seq("0"))))
        end for
    }

    // The exchange wraps reactive updates in <g> for a reactive boundary in SVG context.
    // Calls the real HtmlRenderer.wrapReactiveRegion production function; a bug in that
    // function (wrong tag, missing attribute) makes the assertion fail. Also covers the
    // The typed region prevents an HTML/SVG mismatch at the transport boundary.
    "reactive region wrapper follows the typed region" in {
        val sig  = Signal.initConst(Chunk.empty[Int])
        val root = Svg.svg(sig.foreach(i => Svg.rect.x(i.toDouble).y(0).width(1).height(1)))
        for
            rui <- ReactiveUI.normalize(root, Seq.empty)
            node = rui.children.find(_.path == Seq("0"))
            innerHtml <- HtmlRenderer.render(UI.fragment(), Seq("0"))
        yield
            assert(node.isDefined && node.get.region == ReactiveRegion.SvgElement(Seq("0")))
            val svgWrapped = HtmlRenderer.wrapReactiveRegion(node.get.region, innerHtml)
            assert(svgWrapped.startsWith("<g data-kyo-path="))
            assert(svgWrapped.contains("data-kyo-reactive"))
            assert(svgWrapped.endsWith("</g>"))
            val htmlRegion  = ReactiveRegion.HtmlRange(ReactiveRegion.htmlId(Seq("1", "2")))
            val htmlWrapped = HtmlRenderer.wrapReactiveRegion(htmlRegion, innerHtml)
            assert(htmlWrapped.startsWith("<!--kyo-rs:"))
            assert(!htmlWrapped.contains("data-kyo-reactive"))
            assert(htmlWrapped.endsWith(s"<!--kyo-re:${htmlRegion.id}-->"))
        end for
    }

    "typed SVG region replacement renders nested boundaries in SVG context" in {
        val inner   = UI.Ast.Reactive[Svg.Circle](Signal.initConst(Svg.circle: UI))
        val content = UI.Ast.Fragment[UI](Chunk(inner))
        val path    = Seq("0")
        val context = ReactiveRegion.RegionIdentity.root(path).transparent
        val region  = ReactiveRegion.SvgElement(path)
        for html <- HtmlRenderer.renderRegion(
                content,
                path,
                context,
                region,
                ReactiveRegion.ParentContext.Other,
                ReactiveRegion.BoundaryMode.Emit
            )
        yield
            assert(html.contains("<g data-kyo-path=\"0.0\" data-kyo-reactive>"))
            assert(!html.contains("kyo-rs:"))
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
            assert(a.contains("<g data-kyo-path=\"0\" data-kyo-reactive>"))
        end for
    }

end SvgReactiveTest
