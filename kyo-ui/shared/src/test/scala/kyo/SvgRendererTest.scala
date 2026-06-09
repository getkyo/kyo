package kyo

import kyo.UI.Ast.*
import kyo.UI.foreach
import kyo.internal.HtmlRenderer

/** Tests for the SVG renderer branch: tag name dispatch, attribute serialization, text-bearing elements,
  * typed-value encoders, deterministic ids, and dispatch exhaustiveness for svgTagName and rebuildSvgElement.
  */
class SvgRendererTest extends kyo.test.Test[Any]:

    // rect renders geometry and presentation
    "rect renders geometry and presentation attrs" in {
        val r = Svg.rect.x(0).y(0).width(640).height(400).fill(Svg.Paint.Color(Style.Color.Hex("#3b82f6")))
        for html <- HtmlRenderer.render(r, Seq.empty)
        yield
            assert(html.contains("""x="0""""))
            assert(html.contains("""y="0""""))
            assert(html.contains("""width="640""""))
            assert(html.contains("""height="400""""))
            assert(html.contains("""fill="#3b82f6""""))
            assert(html.startsWith("<rect"))
        end for
    }

    // circle tag + cx/cy/r
    "circle tag renders cx cy r" in {
        val c = Svg.circle.cx(32).cy(32).r(28)
        for html <- HtmlRenderer.render(c, Seq.empty)
        yield
            assert(html.startsWith("<circle"))
            assert(html.contains("""cx="32""""))
            assert(html.contains("""cy="32""""))
            assert(html.contains("""r="28""""))
        end for
    }

    // path d encodes letters
    "path d encodes SVG path commands" in {
        val d = Svg.PathData.from(50, 50).lineTo(90, 50).arcTo(40, 40, 0, false, true, 50, 90).close
        val p = Svg.path.d(d)
        for html <- HtmlRenderer.render(p, Seq.empty)
        yield assert(html.contains("""d="M50 50 L90 50 A40 40 0 0 1 50 90 Z""""))
    }

    // transform list joins
    "transform list renders as space-separated functions" in {
        val g = Svg.g.transform(Svg.Transform.Translate(10, 20), Svg.Transform.Scale(2))
        for html <- HtmlRenderer.render(g, Seq.empty)
        yield assert(html.contains("""transform="translate(10 20) scale(2)""""))
    }

    // points encoding
    "polyline points encode as x,y pairs" in {
        val pl = Svg.polyline.points(Svg.Points((0, 0), (10, 8), (20, 3)))
        for html <- HtmlRenderer.render(pl, Seq.empty)
        yield assert(html.contains("""points="0,0 10,8 20,3""""))
    }

    // SvgLength units suffix
    "SvgLength renders with correct units suffix" in {
        val r = Svg.rect.width(Svg.SvgLength.pct(50)).strokeWidth(Svg.SvgLength.px(2))
        for html <- HtmlRenderer.render(r, Seq.empty)
        yield
            assert(html.contains("""width="50%""""))
            assert(html.contains("""stroke-width="2px""""))
        end for
    }

    // viewBox + preserveAspectRatio
    "viewBox and preserveAspectRatio render correctly" in {
        val root = Svg.svg.viewBox(Svg.ViewBox(0, 0, 640, 400))
            .preserveAspectRatio(Svg.PreserveAspectRatio(Svg.Align.XMidYMid, Svg.MeetOrSlice.Meet))
        for html <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(html.contains("""viewBox="0 0 640 400""""))
            assert(html.contains("""preserveAspectRatio="xMidYMid meet""""))
        end for
    }

    // enums render to SVG tokens
    "enums render to lowercase SVG tokens" in {
        val r = Svg.rect.fillRule(Svg.FillRule.EvenOdd).strokeLinecap(Svg.StrokeLinecap.Round)
        for html <- HtmlRenderer.render(r, Seq.empty)
        yield
            assert(html.contains("""fill-rule="evenodd""""))
            assert(html.contains("""stroke-linecap="round""""))
        end for
    }

    // title renders as child text
    "title element renders as child text node" in {
        val r = Svg.rect.x(0).y(0).width(10).height(10)(Svg.title("hover label"))
        for html <- HtmlRenderer.render(r, Seq.empty)
        yield
            assert(html.contains("<title"))
            assert(html.contains(">hover label</title>"))
            assert(html.startsWith("<rect"))
        end for
    }

    // paint Ref encodes url(#id)
    "paint Ref encodes url(#id) with deterministic id" in {
        val lg       = Svg.linearGradient.x1(0).y1(0).x2(1).y2(0)
        val paintRef = lg.paint
        val gradId   = paintRef.server.asInstanceOf[Svg.LinearGradient].svgAttrs.defId.getOrElse("")
        val r        = Svg.rect.x(0).y(0).width(100).height(100).fill(paintRef)
        val root     = Svg.svg(Svg.defs(paintRef.server.asInstanceOf[Svg.LinearGradient]), r)
        for html <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(gradId.startsWith("kyo-"))
            assert(html.contains(s"""id="$gradId""""))
            assert(html.contains(s"""fill="url(#$gradId)""""))
        end for
    }

    // clip/mask/marker refs url(#id)
    "clip-path and marker refs encode as url(#id)" in {
        val clip    = Svg.clipPath
        val clipId  = clip.id
        val g       = Svg.g.clipPath(clip.clipRef)
        val arrow   = Svg.marker
        val arrowId = arrow.id
        val line    = Svg.line.x1(0).y1(0).x2(100).y2(0).markerEnd(arrow.markerRef)
        for
            gHtml    <- HtmlRenderer.render(g, Seq.empty)
            lineHtml <- HtmlRenderer.render(line, Seq.empty)
        yield
            assert(gHtml.contains(s"""clip-path="url(#$clipId)""""))
            assert(lineHtml.contains(s"""marker-end="url(#$arrowId)""""))
        end for
    }

    // svgTagName dispatch is total. The kyo-ui build escalates the non-exhaustive-match warning to a
    // compile error for HtmlRenderer.scala and ReactiveUI.scala (see build.sbt), so removing any arm of
    // svgTagName is a compile error rather than a silent fallthrough. The checks below are runtime
    // coverage: SvgElement is sealed (so the compiler can check exhaustiveness) and svgTagName covers
    // all 47 concrete types without MatchError.
    "exhaustiveness: SvgElement is sealed and svgTagName covers all 47 types" in {
        // Compile-fail: extending sealed SvgElement outside the package is a compile error.
        typeCheckFailure("""
            import kyo.*
            class MySvg extends Svg.SvgElement
        """)("sealed")
        // Runtime: all 47 SVG types render without MatchError.
        val sym = Svg.symbol.id("s1")
        val elements: Seq[Svg.SvgElement] = Seq(
            Svg.svg,
            Svg.g,
            Svg.defs,
            sym,
            Svg.switch,
            Svg.a,
            Svg.use(sym),
            Svg.rect,
            Svg.circle,
            Svg.ellipse,
            Svg.line,
            Svg.polyline,
            Svg.polygon,
            Svg.path,
            Svg.text,
            Svg.tspan,
            Svg.textPath(Svg.path),
            Svg.linearGradient,
            Svg.radialGradient,
            Svg.stop,
            Svg.pattern,
            Svg.clipPath,
            Svg.mask,
            Svg.image(UI.ImgSrc.Path("/x.png")),
            Svg.foreignObject,
            Svg.marker,
            Svg.title("t"),
            Svg.desc("d"),
            Svg.metadata,
            // filter family
            Svg.filter,
            Svg.feGaussianBlur,
            Svg.feOffset,
            Svg.feBlend,
            Svg.feColorMatrix,
            Svg.feFlood,
            Svg.feComposite,
            Svg.feMerge,
            Svg.feMergeNode,
            Svg.feImage,
            Svg.feTile,
            Svg.feMorphology,
            Svg.feTurbulence,
            Svg.feDisplacementMap,
            // SMIL family
            Svg.animate,
            Svg.animateTransform,
            Svg.animateMotion,
            Svg.set
        )
        Kyo.foreach(elements) { e =>
            HtmlRenderer.render(e, Seq.empty).map { html =>
                assert(html.nonEmpty)
                html
            }
        }.map { results =>
            assert(results.size == 47)
        }
    }

    // rebuildSvgElement dispatch is total via the same scoped warning-to-error escalation (see above).
    // The check below is runtime coverage: rebuildSvgElement covers all 47 concrete SvgElement types
    // without MatchError.
    "exhaustiveness: rebuildSvgElement covers all 47 types without MatchError" in {
        val sym = Svg.symbol.id("s2")
        val elements: Seq[Svg.SvgElement] = Seq(
            Svg.svg,
            Svg.g,
            Svg.defs,
            sym,
            Svg.switch,
            Svg.a,
            Svg.use(sym),
            Svg.rect,
            Svg.circle,
            Svg.ellipse,
            Svg.line,
            Svg.polyline,
            Svg.polygon,
            Svg.path,
            Svg.text,
            Svg.tspan,
            Svg.textPath(Svg.path),
            Svg.linearGradient,
            Svg.radialGradient,
            Svg.stop,
            Svg.pattern,
            Svg.clipPath,
            Svg.mask,
            Svg.image(UI.ImgSrc.Path("/x.png")),
            Svg.foreignObject,
            Svg.marker,
            Svg.title("t"),
            Svg.desc("d"),
            Svg.metadata,
            // filter family
            Svg.filter,
            Svg.feGaussianBlur,
            Svg.feOffset,
            Svg.feBlend,
            Svg.feColorMatrix,
            Svg.feFlood,
            Svg.feComposite,
            Svg.feMerge,
            Svg.feMergeNode,
            Svg.feImage,
            Svg.feTile,
            Svg.feMorphology,
            Svg.feTurbulence,
            Svg.feDisplacementMap,
            // SMIL family
            Svg.animate,
            Svg.animateTransform,
            Svg.animateMotion,
            Svg.set
        )
        Kyo.foreach(elements) { e =>
            HtmlRenderer.render(e, Seq.empty).map { html =>
                assert(html.nonEmpty)
            }
        }.map(_ => assert(elements.size == 47))
    }

    // no xmlns and no createElementNS in rendered SVG
    "no xmlns and no createElementNS in rendered SVG" in {
        val root = Svg.svg(Svg.circle.cx(10).cy(10).r(5))
        for html <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(!html.contains("xmlns"))
            assert(!html.contains("createElementNS"))
        end for
    }

    // three-target byte-identical
    "three-target markup is byte-identical across renders" in {
        val root = Svg.svg.viewBox(Svg.ViewBox(0, 0, 100, 100))(
            Svg.rect.x(0).y(0).width(100).height(100).fill(Svg.Paint.Color(Style.Color.Hex("#ff0000")))
        )
        for
            html1 <- HtmlRenderer.render(root, Seq.empty)
            html2 <- HtmlRenderer.render(root, Seq.empty)
        yield assert(html1 == html2)
        end for
    }

    // id stable across two renders
    "deterministic id is stable across two renders of the same gradient" in {
        val lg       = Svg.linearGradient
        val paintRef = lg.paint
        val root1    = Svg.svg(Svg.rect.fill(paintRef))
        val root2    = Svg.svg(Svg.rect.fill(paintRef))
        for
            html1 <- HtmlRenderer.render(root1, Seq.empty)
            html2 <- HtmlRenderer.render(root2, Seq.empty)
        yield
            val id1 = paintRef.server.asInstanceOf[Svg.LinearGradient].svgAttrs.defId.getOrElse("")
            assert(id1.nonEmpty)
            assert(html1.contains(s"""fill="url(#$id1)""""))
            assert(html2.contains(s"""fill="url(#$id1)""""))
        end for
    }

    // nested Foreach inside svg resolves at runtime
    "nested Foreach inside svg resolves at runtime" in {
        val chunkSig = Signal.initConst(Chunk(1, 2, 3))
        val ui       = Svg.svg(chunkSig.foreach(i => Svg.circle.cx(i.toDouble).cy(0).r(1)))
        for html <- HtmlRenderer.render(ui, Seq.empty)
        yield assert(html.split("<circle").length - 1 == 3)
    }

    // ---- filter + SMIL rendering ----

    // filter renders its id and its feGaussianBlur child
    "filter renders id and feGaussianBlur child" in {
        val fe = Svg.feGaussianBlur.stdDeviation(2.0)
        val f  = Svg.filter(fe)
        val id = f.filterRef.id
        for html <- HtmlRenderer.render(Svg.svg(Svg.defs(f)), Seq.empty)
        yield
            assert(html.contains(s"""<filter data-kyo-path"""))
            assert(html.contains(s"""id="$id""""))
            assert(html.contains("""<feGaussianBlur"""))
            assert(html.contains("""stdDeviation="2""""))
        end for
    }

    // a filter ref is consumed by a graphics element as filter="url(#id)"
    "filter ref renders as url(#id) on consuming element" in {
        val f   = Svg.filter(Svg.feGaussianBlur.stdDeviation(3.0))
        val ref = f.filterRef
        val g   = Svg.g.filter(ref)(Svg.circle.cx(50).cy(50).r(40))
        // and on a direct shape too (HasFilter setter):
        val c = Svg.circle.cx(10).cy(10).r(5).filter(ref)
        for
            ghtml <- HtmlRenderer.render(Svg.svg(Svg.defs(f), g), Seq.empty)
            chtml <- HtmlRenderer.render(c, Seq.empty)
        yield
            assert(ghtml.contains(s"""filter="url(#${ref.id})""""))
            assert(chtml.contains(s"""filter="url(#${ref.id})""""))
        end for
    }

    // animate renders its SMIL attributes
    "animate renders attributeName/from/to/dur/repeatCount" in {
        val anim = Svg.animate
            .attributeName("r").from(20.0).to(30.0).dur("1s").repeatCount("indefinite")
        for html <- HtmlRenderer.render(Svg.circle.cx(50).cy(50).r(20)(anim), Seq.empty)
        yield
            assert(html.contains("""attributeName="r""""))
            assert(html.contains("""from="20""""))
            assert(html.contains("""to="30""""))
            assert(html.contains("""dur="1s""""))
            assert(html.contains("""repeatCount="indefinite""""))
        end for
    }

    // FeFlood is sealed under FilterPrimitive; the production dispatches are exhaustive. Because the
    // kyo-ui build escalates the non-exhaustive-match warning to an error for HtmlRenderer/ReactiveUI,
    // removing the FeFlood arm from svgTagName or rebuildSvgElement fails to compile. This test asserts
    // the sealed contract that makes that check possible, plus that FeFlood serializes its flood-color.
    "FeFlood is a sealed FilterPrimitive and renders flood-color" in {
        val fe: Svg.FilterPrimitive = Svg.feFlood.floodColor(Style.Color.Hex("#ff0000")).floodOpacity(0.5)
        assert(fe.isInstanceOf[Svg.FeFlood])
        typeCheckFailure("""
            import kyo.*
            class MyFe extends Svg.FilterPrimitive
        """)("sealed")
        for html <- HtmlRenderer.render(fe, Seq.empty)
        yield
            assert(html.contains("<feFlood"))
            assert(html.contains("""flood-color="#ff0000""""))
            assert(html.contains("""flood-opacity="0.5""""))
        end for
    }

    // Each closed-enum case serializes to its exact SVG token (hyphenated and camelCase included).
    "closed-enum filter/SMIL attributes serialize to exact SVG tokens" in {
        for
            // feBlend mode: hyphenated tokens must render with the hyphen, not the Scala case name.
            normal     <- HtmlRenderer.render(Svg.feBlend.mode(Svg.BlendMode.Normal), Seq.empty)
            colorDodge <- HtmlRenderer.render(Svg.feBlend.mode(Svg.BlendMode.ColorDodge), Seq.empty)
            softLight  <- HtmlRenderer.render(Svg.feBlend.mode(Svg.BlendMode.SoftLight), Seq.empty)
            luminosity <- HtmlRenderer.render(Svg.feBlend.mode(Svg.BlendMode.Luminosity), Seq.empty)
            // feColorMatrix type: camelCase token.
            hueRotate <- HtmlRenderer.render(Svg.feColorMatrix.`type`(Svg.ColorMatrixType.HueRotate), Seq.empty)
            lumToA    <- HtmlRenderer.render(Svg.feColorMatrix.`type`(Svg.ColorMatrixType.LuminanceToAlpha), Seq.empty)
            // feComposite operator.
            arithmetic <- HtmlRenderer.render(Svg.feComposite.operator(Svg.CompositeOperator.Arithmetic), Seq.empty)
            atop       <- HtmlRenderer.render(Svg.feComposite.operator(Svg.CompositeOperator.Atop), Seq.empty)
            // feMorphology operator.
            erode <- HtmlRenderer.render(Svg.feMorphology.operator(Svg.MorphologyOperator.Erode), Seq.empty)
            // feTurbulence type: camelCase token.
            fractal    <- HtmlRenderer.render(Svg.feTurbulence.`type`(Svg.TurbulenceType.FractalNoise), Seq.empty)
            turbulence <- HtmlRenderer.render(Svg.feTurbulence.`type`(Svg.TurbulenceType.Turbulence), Seq.empty)
            // animateTransform type: camelCase token (skewX).
            skewX  <- HtmlRenderer.render(Svg.animateTransform.`type`(Svg.TransformType.SkewX), Seq.empty)
            rotate <- HtmlRenderer.render(Svg.animateTransform.`type`(Svg.TransformType.Rotate), Seq.empty)
        yield
            assert(normal.contains("""mode="normal""""))
            assert(colorDodge.contains("""mode="color-dodge""""))
            assert(softLight.contains("""mode="soft-light""""))
            assert(luminosity.contains("""mode="luminosity""""))
            assert(hueRotate.contains("""type="hueRotate""""))
            assert(lumToA.contains("""type="luminanceToAlpha""""))
            assert(arithmetic.contains("""operator="arithmetic""""))
            assert(atop.contains("""operator="atop""""))
            assert(erode.contains("""operator="erode""""))
            assert(fractal.contains("""type="fractalNoise""""))
            assert(turbulence.contains("""type="turbulence""""))
            assert(skewX.contains("""type="skewX""""))
            assert(rotate.contains("""type="rotate""""))
        end for
    }

    // A raw (no explicit id) clipPath/mask/marker referenced via its *Ref must emit BOTH the consumer's
    // url(#id) attribute AND a matching id on the definition element itself (not a dangling ref).
    "raw clipPath/mask/marker refs emit matching id on the definition element" in {
        val clip   = Svg.clipPath
        val clipId = clip.id
        val msk    = Svg.mask
        val maskId = msk.id
        val mk     = Svg.marker
        val mkId   = mk.id
        // Each definition element is placed in defs and referenced by a consumer in the same svg.
        val clipped = Svg.g.clipPath(clip.clipRef)(Svg.rect.x(0).y(0).width(10).height(10))
        val masked  = Svg.g.mask(msk.maskRef)(Svg.circle.cx(5).cy(5).r(3))
        val lined   = Svg.line.x1(0).y1(0).x2(10).y2(0).markerEnd(mk.markerRef)
        for
            clipHtml <- HtmlRenderer.render(Svg.svg(Svg.defs(clip), clipped), Seq.empty)
            maskHtml <- HtmlRenderer.render(Svg.svg(Svg.defs(msk), masked), Seq.empty)
            mkHtml   <- HtmlRenderer.render(Svg.svg(Svg.defs(mk), lined), Seq.empty)
        yield
            // clipPath: consumer attribute present AND the clipPath element carries the matching id.
            assert(clipHtml.contains(s"""clip-path="url(#$clipId)""""))
            assert(clipHtml.contains("<clipPath "))
            assert(clipHtml.contains(s"""<clipPath data-kyo-path="${idTagOf(clipHtml, "clipPath")}" id="$clipId""""))
            // mask: consumer attribute present AND the mask element carries the matching id.
            assert(maskHtml.contains(s"""mask="url(#$maskId)""""))
            assert(maskHtml.contains(s"""<mask data-kyo-path="${idTagOf(maskHtml, "mask")}" id="$maskId""""))
            // marker: consumer attribute present AND the marker element carries the matching id.
            assert(mkHtml.contains(s"""marker-end="url(#$mkId)""""))
            assert(mkHtml.contains(s"""<marker data-kyo-path="${idTagOf(mkHtml, "marker")}" id="$mkId""""))
        end for
    }

    /** Extract the data-kyo-path value of the first `<tag ` open tag in `html` (path depth is
      * nesting-dependent, so the round-trip test reads it back rather than hardcoding it).
      */
    private def idTagOf(html: String, tag: String): String =
        val open  = html.indexOf(s"<$tag data-kyo-path=\"")
        val start = open + s"<$tag data-kyo-path=\"".length
        html.substring(start, html.indexOf("\"", start))
    end idTagOf

end SvgRendererTest
