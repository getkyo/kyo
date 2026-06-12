package kyo

import kyo.UI.Ast.*

class SvgTest extends kyo.test.Test[Any]:

    // circle factory builds a Circle
    "circle factory stores cx/cy/r in svgAttrs" in {
        val c = Svg.circle.cx(50).cy(50).r(40)
        assert(c.svgAttrs.cx == Present(50.0))
        assert(c.svgAttrs.cy == Present(50.0))
        assert(c.svgAttrs.r == Present(40.0))
    }

    // Int overload delegates to Double body
    "Int overload delegates to Double body for Rect x/y" in {
        val intForm    = Svg.rect.x(120).y(48)
        val doubleForm = Svg.rect.x(120.0).y(48.0)
        assert(intForm.svgAttrs.x == Present(Svg.Coord.Num(120.0)))
        assert(intForm.svgAttrs.y == Present(Svg.Coord.Num(48.0)))
        assert(intForm.svgAttrs.x == doubleForm.svgAttrs.x)
        assert(intForm.svgAttrs.y == doubleForm.svgAttrs.y)
    }

    // SvgLength overload distinct from Double
    "SvgLength overload produces Coord.Len for width" in {
        val r = Svg.rect.width(Svg.SvgLength.pct(50))
        assert(r.svgAttrs.width == Present(Svg.Coord.Len(Svg.SvgLength.Pct(50.0))))
    }

    // capability gating compile-fail (circle has no width)
    "circle has no width setter (compile-fail)" in {
        typeCheckFailure("""
          import kyo.*
          Svg.circle.width(40)
        """)("value width is not a member")
    }

    // capability gating compile-fail (line has no fill)
    "line has no fill setter (compile-fail)" in {
        typeCheckFailure("""
          import kyo.*
          Svg.line.fill(Svg.Paint.None)
        """)("value fill is not a member")
    }

    // Root is Inline (and thus a UI.Ast.Inline)
    "Root extends UI.Ast.Inline" in {
        val root: UI.Ast.Inline = Svg.svg
        assert(root.isInstanceOf[Svg.Root])
    }

    // Circle is NOT Inline (a bare SVG primitive cannot embed in an HTML container)
    "Circle is not UI.Ast.Inline (compile-fail)" in {
        typeCheckFailure("""
          import kyo.*
          val _: UI.Ast.Inline = Svg.circle
        """)("Found")
    }

    // title is a typed child of a shape
    "title is a typed child of rect" in {
        val r = Svg.rect.x(0).y(0).width(64).height(18)(Svg.title("parseRequest"))
        assert(r.children.size == 1)
        r.children.head match
            case t: Svg.Title => assert(t.text == "parseRequest")
            case other        => fail(s"Expected Svg.Title but got $other")
    }

    // shape rejects an HTML child (Div is not ShapeChild)
    "shape apply rejects HTML Div child (compile-fail)" in {
        typeCheckFailure("""
          import kyo.*
          Svg.rect(UI.Ast.Div())
        """)("Required: kyo.Svg.ShapeChild")
    }

    // g mixes transform + paint capabilities
    "g mixes transform and fill capabilities" in {
        val grp = Svg.g.transform(Svg.Transform.Translate(10, 20)).fill(Svg.Paint.None)
        assert(grp.svgAttrs.transform == Chunk(Svg.Transform.Translate(10.0, 20.0)))
        assert(grp.svgAttrs.fill == Present(Svg.Paint.None))
    }

    // foreignObject accepts HtmlContent (UI.Ast.Block), rejects SvgElement
    "foreignObject accepts Block child; rejects SvgElement child (compile-fail)" in {
        val fo = Svg.foreignObject(UI.Ast.Div())
        assert(fo.children.size == 1)
        typeCheckFailure("""
          import kyo.*
          Svg.foreignObject(Svg.circle)
        """)("kyo.UI.Ast.HtmlChildVal")
    }

    // use takes a typed Symbol reference, not a string
    "use(symbol) resolves href from svgAttrs.defId" in {
        val icon = Svg.symbol.id("star")
        val u    = Svg.use(icon)
        assert(u.svgAttrs.href == Present("#star"))
        typeCheckFailure("""
          import kyo.*
          Svg.use("#star")
        """)("None of the overloaded alternatives")
    }

    // SvgAttrs holds presentation, Attrs is untouched
    "fill goes into svgAttrs, not attrs" in {
        val r = Svg.rect.fill(Svg.Paint.None)
        assert(r.svgAttrs.fill == Present(Svg.Paint.None))
        // presentation never touches the shared Attrs bag:
        // identifier/onClick/style are all Absent in the plain Attrs after fill
        assert(r.attrs.identifier == Absent)
        assert(r.attrs.onClick == Absent)
        assert(r.attrs.uiStyle == Style.empty)
    }

    // construction is total (no throw)
    "construction is total for extreme / empty values" in {
        val r = Svg.rect.width(-5).height(0)
        assert(r.svgAttrs.width == Present(Svg.Coord.Num(-5.0)))
        assert(r.svgAttrs.height == Present(Svg.Coord.Num(0.0)))
        val p = Svg.path.d(Svg.PathData.empty)
        assert(p.svgAttrs.d == Present(Svg.PathData.empty))
    }

    // ---- filter family ----

    // feGaussianBlur is a FilterPrimitive and stores stdDeviation
    "feGaussianBlur is FilterPrimitive and stores stdDeviation" in {
        val fe = Svg.feGaussianBlur.stdDeviation(2.0).in("SourceGraphic").result("blur")
        assert(fe.svgAttrs.stdDeviation == Present(2.0))
        assert(fe.svgAttrs.feIn == Present("SourceGraphic"))
        assert(fe.svgAttrs.feResult == Present("blur"))
        val _: Svg.FilterPrimitive = fe
        succeed
    }

    // filter accepts FilterPrimitive children, rejects a shape (compile-fail)
    "filter accepts FilterPrimitive, rejects Rect (compile-fail)" in {
        val f = Svg.filter(Svg.feGaussianBlur.stdDeviation(2.0), Svg.feOffset.dx(3).dy(4))
        assert(f.children.size == 2)
        assert(f.children.head.isInstanceOf[Svg.FeGaussianBlur])
        typeCheckFailure("""
          import kyo.*
          Svg.filter(Svg.rect)
        """)("Found:")
    }

    // feMerge accepts only feMergeNode, rejects another FilterPrimitive
    "feMerge accepts FeMergeNode, rejects FeGaussianBlur" in {
        val m = Svg.feMerge(Svg.feMergeNode.in("blur"), Svg.feMergeNode.in("SourceGraphic"))
        assert(m.children.size == 2)
        assert(m.children.head.isInstanceOf[Svg.FeMergeNode])
        // FeMergeNode is NOT a FilterPrimitive, so it cannot be placed directly in a filter:
        typeCheckFailure("""
          import kyo.*
          Svg.filter(Svg.feMergeNode.in("x"))
        """)("Found:")
        // and feMerge rejects a non-feMergeNode filter primitive:
        typeCheckFailure("""
          import kyo.*
          Svg.feMerge(Svg.feGaussianBlur.stdDeviation(2.0))
        """)("Found:")
    }

    // SMIL animate is a valid shape child
    "animate is a valid shape child of circle" in {
        val c = Svg.circle.cx(50).cy(50).r(20)(
            Svg.animate.attributeName("r").from(20.0).to(30.0).dur("1s").repeatCount("indefinite")
        )
        assert(c.children.size == 1)
        assert(c.children.head.isInstanceOf[Svg.Animate])
        // numeric from/to are formatted via the canonical NumberFormat encoder (no trailing zeros):
        val anim = c.children.head.asInstanceOf[Svg.Animate]
        assert(anim.svgAttrs.animFrom == Present("20"))
        assert(anim.svgAttrs.animTo == Present("30"))
    }

    // set is named SetAnim; the factory is Svg.set
    "set factory produces SetAnim type" in {
        val s: Svg.SetAnim = Svg.set.attributeName("visibility").to("hidden").begin("0s")
        assert(s.svgAttrs.animAttributeName == Present("visibility"))
        assert(s.svgAttrs.animTo == Present("hidden"))
        assert(s.svgAttrs.animBegin == Present("0s"))
    }

    // Filter ref produces a typed Filter.Ref carrying the element's id
    "filterRef carries the filter element id" in {
        val f   = Svg.filter(Svg.feGaussianBlur.stdDeviation(1.0))
        val ref = f.filterRef
        assert(ref.id == f.filterRef.id)
        assert(ref.id.nonEmpty)
    }

    // Closed-enum attributes are typed; a raw string token does not compile.
    "filter/SMIL closed-enum setters reject raw strings (compile-fail)" in {
        // feComposite.operator takes a typed CompositeOperator, not a free String.
        typeCheckFailure("""
          import kyo.*
          Svg.feComposite.operator("ovrr")
        """)("Found:")
        // feBlend.mode takes a typed BlendMode.
        typeCheckFailure("""
          import kyo.*
          Svg.feBlend.mode("multiplyy")
        """)("Found:")
        // animateTransform.`type` takes a typed TransformType.
        typeCheckFailure("""
          import kyo.*
          Svg.animateTransform.`type`("translatte")
        """)("Found:")
    }

    // The typed enum setters store the typed value in svgAttrs.
    "filter/SMIL closed-enum setters store typed values" in {
        assert(Svg.feBlend.mode(Svg.BlendMode.ColorDodge).svgAttrs.feMode == Present(Svg.BlendMode.ColorDodge))
        assert(Svg.feColorMatrix.`type`(Svg.ColorMatrixType.HueRotate).svgAttrs.feColorMatrixType == Present(Svg.ColorMatrixType.HueRotate))
        assert(Svg.feComposite.operator(Svg.CompositeOperator.Arithmetic).svgAttrs.feCompositeOperator == Present(
            Svg.CompositeOperator.Arithmetic
        ))
        assert(
            Svg.feMorphology.operator(Svg.MorphologyOperator.Dilate).svgAttrs.feMorphologyOperator == Present(Svg.MorphologyOperator.Dilate)
        )
        assert(
            Svg.feTurbulence.`type`(Svg.TurbulenceType.FractalNoise).svgAttrs.feTurbulenceType == Present(Svg.TurbulenceType.FractalNoise)
        )
        assert(Svg.animateTransform.`type`(Svg.TransformType.SkewX).svgAttrs.animType == Present(Svg.TransformType.SkewX))
    }

end SvgTest
