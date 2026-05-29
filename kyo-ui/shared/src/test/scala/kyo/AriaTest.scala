package kyo

import kyo.UI.*
import kyo.UI.Ast.*
import kyo.internal.HtmlRenderer
import scala.language.implicitConversions

class AriaTest extends Test:

    private def renderHtml(ui: UI)(using Frame): String < Sync =
        HtmlRenderer.render(ui, Seq.empty)

    "aria(name, value) renders aria-label attribute on div" in run {
        val html = renderHtml(UI.div.aria("label", "Save"))
        html.map { s =>
            assert(s.contains("""aria-label="Save""""))
        }
    }

    "aria(pairs*) with three pairs renders all aria-* attributes sorted alphabetically" in run {
        val html = renderHtml(
            UI.div.aria(
                "label" -> "Main",
                "live"  -> "polite",
                "busy"  -> "false"
            )
        )
        html.map { s =>
            assert(s.contains("""aria-busy="false""""))
            assert(s.contains("""aria-label="Main""""))
            assert(s.contains("""aria-live="polite""""))
            val busyPos  = s.indexOf("aria-busy")
            val labelPos = s.indexOf("aria-label")
            val livePos  = s.indexOf("aria-live")
            assert(busyPos < labelPos && labelPos < livePos)
        }
    }

    "setting the same aria name twice keeps only the last value" in run {
        val html = renderHtml(
            UI.div.aria("label", "First").aria("label", "Second")
        )
        html.map { s =>
            assert(s.contains("""aria-label="Second""""))
            assert(!s.contains("""aria-label="First""""))
        }
    }

    "element with ariaAttrs emits aria-* before data-* before data-kyo-prop-* in deterministic order" in run {
        val element = UI.checkbox
            .aria("role", "button")
            .aria("label", "Go")
            .data("track-id", "99")
            .data("env", "prod")
            .indeterminate(true)
        for
            s  <- renderHtml(element)
            s2 <- renderHtml(element)
        yield
            assert(s == s2)
            val ariaLabelPos = s.indexOf("aria-label")
            val ariaRolePos  = s.indexOf("aria-role")
            val dataEnvPos   = s.indexOf("data-env")
            val dataTrackPos = s.indexOf("data-track-id")
            val dataPropPos  = s.indexOf("data-kyo-prop-indeterminate")
            assert(ariaLabelPos < ariaRolePos)
            assert(dataEnvPos < dataTrackPos)
            assert(ariaRolePos < dataEnvPos)
            assert(dataTrackPos < dataPropPos)
        end for
    }

    "aria and data attributes both render sorted by name (structural attribute check)" in run {
        val html = renderHtml(
            UI.div
                .aria("z-attr", "z")
                .aria("a-attr", "a")
                .data("z-data", "z")
                .data("a-data", "a")
        )
        html.map { s =>
            assert(s.indexOf("aria-a-attr") < s.indexOf("aria-z-attr"))
            assert(s.indexOf("data-a-data") < s.indexOf("data-z-data"))
            assert(s.contains("aria-a-attr"))
            assert(s.contains("aria-z-attr"))
            assert(s.contains("data-a-data"))
            assert(s.contains("data-z-data"))
        }
    }

end AriaTest
