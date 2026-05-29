package kyo.internal

import kyo.*
import kyo.Length
import kyo.Length.*
import kyo.Style
import kyo.Style.*
import kyo.Style.Color

class CssStyleRendererTest extends Test:

    // ---- color() ----

    "color Hex renders value as-is" in {
        assert(CssStyleRenderer.color(Color.Hex("#ff0000")) == "#ff0000")
    }

    "color Rgb renders rgb(...)" in {
        assert(CssStyleRenderer.color(Color.Rgb(255, 0, 0)) == "rgb(255, 0, 0)")
    }

    "color Rgba renders rgba(...)" in {
        assert(CssStyleRenderer.color(Color.Rgba(255, 0, 0, 0.5)) == "rgba(255, 0, 0, 0.5)")
    }

    "color Transparent renders transparent" in {
        assert(CssStyleRenderer.color(Color.Transparent) == "transparent")
    }

    "color Rgb with all zeros" in {
        assert(CssStyleRenderer.color(Color.Rgb(0, 0, 0)) == "rgb(0, 0, 0)")
    }

    "color Rgba with alpha 1.0 renders as integer" in {
        // fmt strips trailing .0 when value is whole number
        assert(CssStyleRenderer.color(Color.Rgba(0, 0, 0, 1.0)) == "rgba(0, 0, 0, 1)")
    }

    // ---- size() ----

    "size Px renders px suffix" in {
        assert(CssStyleRenderer.size(Px(10)) == "10px")
    }

    "size Px(0) renders bare zero" in {
        assert(CssStyleRenderer.size(Px(0)) == "0")
    }

    "size Px with fractional value" in {
        assert(CssStyleRenderer.size(Px(1.5)) == "1.5px")
    }

    "size Pct renders percent" in {
        assert(CssStyleRenderer.size(Pct(50)) == "50%")
    }

    "size Em renders em suffix" in {
        assert(CssStyleRenderer.size(Em(2)) == "2em")
    }

    "size Auto renders auto" in {
        assert(CssStyleRenderer.size(Auto) == "auto")
    }

    // ---- render() ----

    "render empty Style produces empty string" in {
        assert(CssStyleRenderer.render(Style.empty) == "")
    }

    "render background-color" in {
        val css = CssStyleRenderer.render(Style.bg(Color.red))
        assert(css.contains("background-color"))
        assert(css.contains("#ef4444"))
    }

    "render text color" in {
        val css = CssStyleRenderer.render(Style.color(Color.blue))
        assert(css.contains("color:"))
        assert(css.contains("#3b82f6"))
    }

    "render multiple props separated by space" in {
        val s   = Style.bg(Color.white).color(Color.black)
        val css = CssStyleRenderer.render(s)
        assert(css.contains("background-color"))
        assert(css.contains("color"))
    }

    "render width" in {
        val css = CssStyleRenderer.render(Style.width(100.px))
        assert(css.contains("width: 100px"))
    }

    "render height" in {
        val css = CssStyleRenderer.render(Style.height(50.pct))
        assert(css.contains("height: 50%"))
    }

    "render padding" in {
        val css = CssStyleRenderer.render(Style.padding(10.px))
        assert(css.contains("padding:"))
        assert(css.contains("10px"))
    }

    "render display none for displayNone" in {
        val css = CssStyleRenderer.render(Style.displayNone)
        assert(css.contains("display: none"))
    }

    "render filter for brightness" in {
        val css = CssStyleRenderer.render(Style.brightness(0.5))
        assert(css.contains("filter:"))
        assert(css.contains("brightness"))
    }

    "render linear gradient" in {
        val css = CssStyleRenderer.render(
            Style.bgGradient(Style.GradientDirection.toRight, (Color.red, 0.pct), (Color.blue, 100.pct))
        )
        assert(css.contains("linear-gradient"))
        assert(css.contains("to right"))
    }

    "render cursor pointer" in {
        val css = CssStyleRenderer.render(Style.cursor(Style.Cursor.pointer))
        assert(css.contains("cursor: pointer"))
    }

    "render font weight bold" in {
        val css = CssStyleRenderer.render(Style.bold)
        assert(css.contains("font-weight: bold"))
    }

    "hover prop produces no inline CSS" in {
        // HoverProp is excluded from render (goes into CSS rule separately)
        val css = CssStyleRenderer.render(Style.hover(Style.bold))
        assert(!css.contains("font-weight"))
    }

    "focus prop produces no inline CSS" in {
        val css = CssStyleRenderer.render(Style.focus(Style.color(Color.black)))
        assert(!css.contains("color:"))
    }

end CssStyleRendererTest
