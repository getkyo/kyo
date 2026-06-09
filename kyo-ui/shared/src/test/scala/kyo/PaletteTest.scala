package kyo

import kyo.Chart.*
import kyo.UI.*
import kyo.UI.Ast.*

/** Tests for the named color [[Palette]] surface.
  *
  * Asserts the resolved color lists and that selecting a palette via `_.theme(_.palette(p))`
  * actually changes the per-mark fill color emitted in the lowered SVG.
  */
class PaletteTest extends kyo.test.Test[Any]:

    private def circlesIn(root: Svg.Root): Chunk[Svg.Circle] =
        root.children.flatMap:
            case g: Svg.G =>
                g.children.flatMap:
                    case c: Svg.Circle => Chunk(c)
                    case _             => Chunk.empty
            case c: Svg.Circle => Chunk(c)
            case _             => Chunk.empty

    private def firstFill(root: Svg.Root)(using Frame, kyo.test.AssertScope): Style.Color =
        val cs = circlesIn(root)
        assert(cs.nonEmpty, "Expected at least one circle")
        cs(0).svgAttrs.fill match
            case Present(Svg.Paint.Color(col)) => col
            case other                         => fail(s"Expected a color fill but got $other")
    end firstFill

    case class Row(x: String, y: Int)

    // ---- Palette.Okabe resolves to exactly 8 colors ----

    "Palette.Okabe resolves to exactly 8 colors" in {
        val cs = Palette.colors(Palette.Okabe)
        assert(cs.size == 8, s"Expected 8 Okabe colors but got ${cs.size}")
        // Okabe-Ito starts with black and orange.
        assert(cs(0) == Style.Color.rgb(0, 0, 0), s"Okabe(0) should be black but got ${cs(0)}")
        assert(cs(1) == Style.Color.rgb(230, 159, 0), s"Okabe(1) should be the Okabe orange but got ${cs(1)}")
    }

    // ---- selecting a palette changes the mark fill color ----

    "theme(_.palette(Palette.Okabe)) makes the first mark fill the first Okabe color, not the Default" in {
        val rows        = Chunk(Row("a", 1), Row("b", 2))
        val defaultSpec = Chart(rows)(point(x = _.x, y = _.y))
        val okabeSpec   = Chart(rows)(point(x = _.x, y = _.y)).theme(_.palette(Palette.Okabe))
        for
            defaultRoot <- (defaultSpec).lower
            okabeRoot   <- (okabeSpec).lower
        yield
            val defaultFill = firstFill(defaultRoot)
            val okabeFill   = firstFill(okabeRoot)
            // Default palette mark 0 is blue; Okabe mark 0 is black. They must differ, and Okabe must win.
            assert(defaultFill == Palette.colors(Palette.Default)(0), s"Default fill should be Default(0) but got $defaultFill")
            assert(okabeFill == Palette.colors(Palette.Okabe)(0), s"Okabe fill should be Okabe(0) but got $okabeFill")
            assert(okabeFill != defaultFill, s"Okabe fill ($okabeFill) must differ from Default fill ($defaultFill)")
        end for
    }

    // ---- Palette.Default equals the built-in DefaultPalette ----

    "Palette.Default returns the same chunk as the built-in DefaultPalette" in {
        assert(
            Palette.colors(Palette.Default) == kyo.internal.ChartLower.DefaultPalette,
            "Palette.Default must equal internal.ChartLower.DefaultPalette"
        )
    }

end PaletteTest
