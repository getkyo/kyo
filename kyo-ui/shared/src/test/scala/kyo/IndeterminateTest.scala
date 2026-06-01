package kyo

import kyo.UI.*
import kyo.UI.Ast.*
import kyo.internal.HtmlRenderer
import scala.language.implicitConversions

class IndeterminateTest extends Test:

    private def renderHtml(ui: UI)(using Frame): String < Sync =
        HtmlRenderer.render(ui, Seq.empty)

    "checkbox.indeterminate(true) adds jsProps and renders data-kyo-prop-indeterminate" in run {
        val cb   = UI.checkbox.indeterminate(true)
        val html = renderHtml(cb)
        html.map { s =>
            assert(cb.attrs.jsProps == Map("indeterminate" -> "true"))
            assert(s.contains("""data-kyo-prop-indeterminate="true""""))
        }
    }

    "checkbox.indeterminate(true).checked(true) renders both checked and data-kyo-prop-indeterminate" in run {
        val html = renderHtml(UI.checkbox.indeterminate(true).checked(true))
        html.map { s =>
            assert(s.contains("checked"))
            assert(s.contains("""data-kyo-prop-indeterminate="true""""))
        }
    }

    "checkbox.indeterminate(false) removes the jsProp key from attrs" in run {
        val cb   = UI.checkbox.indeterminate(true).indeterminate(false)
        val html = renderHtml(cb)
        html.map { s =>
            assert(!cb.attrs.jsProps.contains("indeterminate"))
            assert(!s.contains("data-kyo-prop-indeterminate"))
        }
    }

    "checkbox.indeterminate(signal) at true produces Checkbox with correct jsProps" in run {
        for
            ref <- Signal.initRef(true)
            reactive = UI.checkbox.indeterminate(ref: Signal[Boolean])
            result <- reactive match
                case r: Reactive =>
                    r.signal.current.map { inner =>
                        inner match
                            case cb: Checkbox =>
                                assert(cb.attrs.jsProps == Map("indeterminate" -> "true"))
                            case other =>
                                fail(s"Expected Checkbox, got $other")
                    }
                case other =>
                    fail(s"Expected Reactive, got $other")
        yield result
    }

    "toggling indeterminate from true to false drops the jsProp in rendered HTML" in run {
        for
            ref <- Signal.initRef(true)
            reactive = UI.checkbox.indeterminate(ref: Signal[Boolean])
            result <- reactive match
                case r: Reactive =>
                    for
                        htmlTrue  <- r.signal.current.flatMap(ui => renderHtml(ui))
                        _         <- ref.set(false)
                        htmlFalse <- r.signal.current.flatMap(ui => renderHtml(ui))
                    yield
                        assert(htmlTrue.contains("""data-kyo-prop-indeterminate="true""""))
                        assert(!htmlFalse.contains("data-kyo-prop-indeterminate"))
                case other =>
                    fail(s"Expected Reactive, got $other")
        yield result
    }

end IndeterminateTest
