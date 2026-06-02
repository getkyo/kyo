package kyo.website

import kyo.*
import kyo.internal.BaseKyoCoreTest
import kyo.internal.Platform
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext

/** In-Chrome SPA navigation smoke test (leaf 22).
  *
  * Verifies that clicking a sidebar link swaps the content area without a full page reload, that
  * `UILocation.current` updates, and that the `<head>` stylesheet survives the navigation (INV-013).
  *
  * This test is deferred to the Phase 8 cross-platform gate. It requires a real browser context
  * (`UITestSpaSmokeTest` harness) that is not available in the Phase 6 JVM-only verification run.
  * The test is ignored here to prevent the Phase 6 verification target from picking it up.
  */
class WebsiteSpaSmokeTest extends AsyncFreeSpec with NonImplicitAssertions with BaseKyoCoreTest:

    type Assertion = org.scalatest.Assertion
    def assertionSuccess              = succeed
    def assertionFailure(msg: String) = fail(msg)

    override given executionContext: ExecutionContext = Platform.executionContext

    // Leaf 22: in-Chrome SPA navigation. Deferred to Phase 8 cross-platform gate.
    "click-to-navigate swaps content without reload (leaf 22, Phase 8 gate)" ignore {
        // Phase 8: mount the docs app in the browser harness, click a sidebar link, assert:
        // - The content area (data-kyo-reactive span) changes to the new route's article.
        // - UILocation.current reflects the new route.
        // - The <head> stylesheet is still present (not clobbered by the content swap).
        // - window.location.href did NOT change (no full page reload).
        succeed
    }

end WebsiteSpaSmokeTest
