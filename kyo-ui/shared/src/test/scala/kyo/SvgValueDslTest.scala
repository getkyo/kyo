package kyo

import kyo.UI.Ast.*

class SvgValueDslTest extends kyo.test.Test[Any]:

    // PathData builder accumulates commands in order
    "PathData builder accumulates commands in order" in {
        val pd   = Svg.PathData.from(0, 0).lineTo(10, 10).close
        val cmds = Svg.PathData.commands(pd)
        assert(cmds == Chunk(
            Svg.PathCommand.MoveTo(0.0, 0.0),
            Svg.PathCommand.LineTo(10.0, 10.0),
            Svg.PathCommand.Close
        ))
    }

    // PathData arcTo flags are Boolean (not Int)
    "PathData arcTo flags are Boolean typed (compile-fail with Int)" in {
        typeCheckFailure("""
          import kyo.*
          Svg.PathData.from(0, 0).arcTo(40, 40, 0, 1, 1, 50, 90)
        """)("Required: Boolean")
    }

    // Points opaque rejects a raw string
    "polyline points setter rejects a String (compile-fail)" in {
        typeCheckFailure("""
          import kyo.*
          Svg.polyline.points("0,0 10,8")
        """)("Required: kyo.Svg.Points")
    }

    // Transform equality and Maybe optionals
    "Transform.Rotate optionals use Maybe" in {
        val r1 = Svg.Transform.Rotate(45)
        val r2 = Svg.Transform.Rotate(45, Present(10.0), Present(20.0))
        assert(r1 != r2)
        assert(r1.cx == Absent)
        assert(r2.cx == Present(10.0))
    }

    // Paint.Color via implicit conversion from Style.Color
    "Style.Color converts to Paint.Color via implicit conversion" in {
        val p: Svg.Paint = Style.Color.blue
        assert(p == Svg.Paint.Color(Style.Color.blue))
    }

    // gradient .paint yields a Ref carrying a stable id
    "linearGradient.paint yields stable deterministic id" in {
        val g                 = Svg.linearGradient.x1(0).y1(0).x2(0).y2(1)
        val p1                = g.paint
        val p2                = g.paint
        val Svg.Paint.Ref(s1) = p1: @unchecked
        val Svg.Paint.Ref(s2) = p2: @unchecked
        assert(s1.id == s2.id)
    }

    // SvgLength is a sealed four-member ADT
    "SvgLength is a four-member sealed ADT" in {
        val px   = Svg.SvgLength.px(2)
        val pct  = Svg.SvgLength.pct(50)
        val user = Svg.SvgLength.user(40)
        val em   = Svg.SvgLength.em(1.5)
        assert(px == Svg.SvgLength.Px(2.0))
        assert(pct == Svg.SvgLength.Pct(50.0))
        assert(user == Svg.SvgLength.User(40.0))
        assert(em == Svg.SvgLength.Em(1.5))
    }

    // ViewBox is typed, not a string
    "svg viewBox setter rejects a String (compile-fail)" in {
        typeCheckFailure("""
          import kyo.*
          Svg.svg.viewBox("0 0 640 400")
        """)("Required: kyo.Svg.ViewBox")
    }

    // clip/mask/marker refs are typed handles; string rejected
    "clipPath ref is typed; raw string rejected (compile-fail)" in {
        val c   = Svg.clipPath(Svg.rect.x(0).y(0).width(100).height(50))
        val ref = c.clipRef
        val grp = Svg.g.clipPath(ref)
        assert(grp.svgAttrs.clipPathRef.isDefined)
        typeCheckFailure("""
          import kyo.*
          Svg.g.clipPath("url(#x)")
        """)("Required: kyo.Svg.ClipPath.Ref")
    }

end SvgValueDslTest
