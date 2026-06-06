package kyo

import kyo.Browser.*
import scala.language.implicitConversions

// Test 13: DomBackend removes the data-kyo-prop-indeterminate source attribute after
// applying the JS-side .indeterminate property on mount.
class DomBackendIndeterminateTest extends UITest:

    "indeterminate(true) sets JS property and removes data-kyo-prop-* attribute on mount" in {
        withUI(UI.checkbox.indeterminate(true).id("chk")) {
            for
                _ <- Browser.assertVisible(Selector.id("chk"))
                // After DomBackend.applyJsPropsSync runs, the source attribute is removed.
                // readAttributeExprJs returns '' (empty string) when getAttribute returns null,
                // so isEmpty is the correct absent-attribute predicate.
                _ <- Browser.assertAttributeSatisfies(
                    Selector.id("chk"),
                    "data-kyo-prop-indeterminate",
                    "attribute must be absent after applyJsPropsSync"
                )(_.isEmpty)
                // Read the JS-side .indeterminate property via evalBoolean to confirm it was set.
                indeterminate <- Browser.evalBoolean("document.getElementById('chk').indeterminate")
                _ = assert(indeterminate, "expected .indeterminate === true on the DOM element")
            yield ()
        }
    }

end DomBackendIndeterminateTest
