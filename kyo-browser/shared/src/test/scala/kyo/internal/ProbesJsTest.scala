package kyo.internal

import kyo.*

/** Pure text-output snapshot tests for the JS-template builders in [[ProbesJs]].
  *
  * The probe expressions are interpolated into one-shot CDP `Runtime.evaluate` payloads; they are pure-string builders with no I/O. The
  * structural snapshots here catch refactors of [[JsStringUtil.escapeJsString]] and the per-probe sentinel surface (e.g. dropping
  * `'not_attached'` or accidentally swapping the visible-sentinel value).
  */
class ProbesJsTest extends kyo.Test:

    private val sampleSelector: Selector = Selector.css("#sample")

    "ProbesJs builders" - {

        "visibilityExprJs embeds the resolved selector JS, the not_attached sentinel, the hidden sentinel, and the visible sentinel" in {
            val js = ProbesJs.visibilityExprJs(sampleSelector)
            assert(js.contains("document.querySelector('#sample')"), s"missing resolved-selector JS in: $js")
            assert(js.contains("'not_attached'"), s"missing 'not_attached' sentinel in: $js")
            assert(js.contains("'hidden'"), s"missing 'hidden' sentinel in: $js")
            assert(js.contains("'visible'"), s"missing 'visible' sentinel in: $js")
            // visibilityExprJs uses the full ladder; collapseHidden=false so 'ancestor_hidden' and 'zero_size' survive.
            assert(js.contains("'ancestor_hidden'"), s"missing 'ancestor_hidden' sentinel in: $js")
            assert(js.contains("'zero_size'"), s"missing 'zero_size' sentinel in: $js")
        }

        "enabledExprJs collapses non-visible sentinels to 'hidden' and tails an aria-disabled check" in {
            val js = ProbesJs.enabledExprJs(sampleSelector)
            assert(js.contains("'enabled'"), s"missing 'enabled' sentinel in: $js")
            assert(js.contains("'disabled'"), s"missing 'disabled' sentinel in: $js")
            assert(js.contains("aria-disabled"), s"missing aria-disabled tail in: $js")
            // Collapse: 'ancestor_hidden' / 'zero_size' should NOT appear (per visibilityLadderExprJs collapseHidden=true).
            assert(!js.contains("'ancestor_hidden'"), s"unexpected 'ancestor_hidden' in collapsed enabled probe: $js")
            assert(!js.contains("'zero_size'"), s"unexpected 'zero_size' in collapsed enabled probe: $js")
        }

        "disabledExprJs returns 'disabled' / 'enabled' / 'not_attached'" in {
            val js = ProbesJs.disabledExprJs(sampleSelector)
            assert(js.contains("'disabled'"), s"missing 'disabled' sentinel in: $js")
            assert(js.contains("'enabled'"), s"missing 'enabled' sentinel in: $js")
            assert(js.contains("'not_attached'"), s"missing 'not_attached' sentinel in: $js")
            assert(js.contains("aria-disabled"), s"missing aria-disabled check in: $js")
        }

        "checkedExprJs returns 'checked' / 'not_checked' / 'not_attached'" in {
            val js = ProbesJs.checkedExprJs(sampleSelector)
            assert(js.contains("'checked'"), s"missing 'checked' sentinel in: $js")
            assert(js.contains("'not_checked'"), s"missing 'not_checked' sentinel in: $js")
            assert(js.contains("'not_attached'"), s"missing 'not_attached' sentinel in: $js")
            assert(js.contains("el.checked"), s"missing el.checked read in: $js")
        }

        "emptyTextExprJs uses textContent.trim() for the non-input case" in {
            val js = ProbesJs.emptyTextExprJs(sampleSelector)
            assert(js.contains("textContent"), s"missing textContent read in: $js")
            assert(js.contains(".trim()"), s"missing .trim() in: $js")
            assert(js.contains("'empty'"), s"missing 'empty' sentinel in: $js")
            assert(js.contains("'non_empty'"), s"missing 'non_empty' sentinel in: $js")
            assert(js.contains("'not_attached'"), s"missing 'not_attached' sentinel in: $js")
        }

        "emptyValueExprJs uses el.value (not textContent) for the input case" in {
            val js = ProbesJs.emptyValueExprJs(sampleSelector)
            assert(js.contains("el.value"), s"missing el.value read in: $js")
            assert(!js.contains("textContent"), s"emptyValueExprJs must not use textContent: $js")
            assert(js.contains("'empty'"), s"missing 'empty' sentinel in: $js")
            assert(js.contains("'non_empty'"), s"missing 'non_empty' sentinel in: $js")
        }

        "focusExprJs compares against document.activeElement" in {
            val js = ProbesJs.focusExprJs(sampleSelector)
            assert(js.contains("document.activeElement"), s"missing document.activeElement comparison in: $js")
            assert(js.contains("'focused'"), s"missing 'focused' sentinel in: $js")
            assert(js.contains("'not_focused'"), s"missing 'not_focused' sentinel in: $js")
        }

        "selectionStartExprJs reads selectionStart inside a try/catch with 'unsupported' fallback" in {
            val jsExpr = "document.querySelector('#sample')"
            val js     = ProbesJs.selectionStartExprJs(jsExpr)
            assert(js.contains("el.selectionStart"), s"missing el.selectionStart read in: $js")
            assert(js.contains("try"), s"missing try block in: $js")
            assert(js.contains("catch"), s"missing catch block in: $js")
            assert(js.contains("'unsupported'"), s"missing 'unsupported' sentinel in: $js")
            assert(js.contains("'not_attached'"), s"missing 'not_attached' sentinel in: $js")
        }

        "noAttributeExprJs escapes the attribute name via JsStringUtil.escapeJsString" in {
            val jsExpr = "document.querySelector('#sample')"
            // Attribute name containing a single quote forces the escape; the result must NOT contain
            // an unescaped single quote that would close the embedded JS string literal.
            val js = ProbesJs.noAttributeExprJs("data-'evil", jsExpr)
            assert(js.contains("hasAttribute"), s"missing hasAttribute in: $js")
            assert(js.contains("'present'"), s"missing 'present' sentinel in: $js")
            assert(js.contains("'absent'"), s"missing 'absent' sentinel in: $js")
            // Exact escaped form is `data-\'evil` surrounded by single quotes via escapeJsString.
            val escaped = JsStringUtil.escapeJsString("data-'evil")
            assert(js.contains(s"'$escaped'"), s"expected escaped attribute literal '$escaped' in: $js")
        }

        "textOrderExprJs escapes each substring via JsStringUtil.escapeJsString" in {
            val js = ProbesJs.textOrderExprJs(Seq("a", "b'with quote", "c\nwith newline"))
            assert(js.contains("document.body"), s"missing document.body read in: $js")
            assert(js.contains(".innerText"), s"missing .innerText read in: $js")
            assert(js.contains("'ok'"), s"missing 'ok' sentinel in: $js")
            assert(js.contains("'missing:'"), s"missing 'missing:' prefix in: $js")
            // Each substring is escaped and wrapped in single quotes inside the array literal.
            assert(js.contains(s"'${JsStringUtil.escapeJsString("b'with quote")}'"), s"missing escaped 'b' substring in: $js")
            assert(js.contains(s"'${JsStringUtil.escapeJsString("c\nwith newline")}'"), s"missing escaped 'c' substring in: $js")
        }

        "elementOrderExprJs concatenates raw JS expressions in the array literal" in {
            val js = ProbesJs.elementOrderExprJs(Seq("document.querySelector('#a')", "document.querySelector('#b')"))
            assert(js.contains("compareDocumentPosition"), s"missing compareDocumentPosition in: $js")
            assert(js.contains("DOCUMENT_POSITION_FOLLOWING"), s"missing DOCUMENT_POSITION_FOLLOWING in: $js")
            assert(js.contains("'ok'"), s"missing 'ok' sentinel in: $js")
            assert(js.contains("'not_attached:'"), s"missing 'not_attached:' prefix in: $js")
            assert(js.contains("'out_of_order:'"), s"missing 'out_of_order:' prefix in: $js")
            assert(js.contains("document.querySelector('#a')"), s"missing first selector JS in: $js")
            assert(js.contains("document.querySelector('#b')"), s"missing second selector JS in: $js")
        }

        "valueExprJs reads el.value with a 'V:' prefix sentinel-disambiguating the raw value" in {
            val js = ProbesJs.valueExprJs(sampleSelector)
            assert(js.contains("typeof el.value"), s"missing typeof el.value check in: $js")
            assert(js.contains("'V:'"), s"missing 'V:' prefix in: $js")
            assert(js.contains("'unsupported'"), s"missing 'unsupported' sentinel in: $js")
            assert(js.contains("'not_attached'"), s"missing 'not_attached' sentinel in: $js")
        }

        "readabilityScript clones the document and excludes the standard non-content roles" in {
            val js = ProbesJs.readabilityScript
            assert(js.contains("document.cloneNode(true)"), s"missing document clone in: $js")
            assert(js.contains("'script'"), s"missing 'script' exclusion in: $js")
            assert(js.contains("'style'"), s"missing 'style' exclusion in: $js")
            assert(js.contains("'nav'"), s"missing 'nav' exclusion in: $js")
            assert(js.contains("'footer'"), s"missing 'footer' exclusion in: $js")
            assert(js.contains("article"), s"missing article preference in: $js")
            assert(js.contains("main"), s"missing main preference in: $js")
            assert(js.contains(".innerText"), s"missing .innerText extraction in: $js")
        }

        "visibilityExprJs escapes single quotes in selector value to prevent JS-string break" in {
            // A selector value containing a single quote must round through escapeJsString so the JS
            // template stays well-formed. Drop the escape and the JS would become `... '#it's' ...`,
            // a syntax error. The escaped form must be present and unescaped raw quote forms absent.
            val raw     = "input[name='evil']"
            val tricky  = Selector.css(raw)
            val js      = ProbesJs.visibilityExprJs(tricky)
            val escaped = JsStringUtil.escapeJsString(raw)
            // The escaped selector literal must appear verbatim inside single quotes in the emitted JS.
            assert(js.contains(s"'$escaped'"), s"expected escaped selector literal '$escaped' in: $js")
            // The raw unescaped selector (containing a bare `'`) must NOT appear; if it did the JS
            // template would be `document.querySelector('input[name='evil']')` which is a SyntaxError.
            assert(!js.contains(s"'$raw'"), s"unescaped raw selector literal '$raw' leaked into: $js")
        }
    }

end ProbesJsTest
