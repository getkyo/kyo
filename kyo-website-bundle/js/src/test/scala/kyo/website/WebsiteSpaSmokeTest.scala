package kyo.website

import kyo.*
import kyo.internal.BaseKyoCoreTest
import kyo.internal.Platform
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext

/** In-Chrome chrome-parity and SPA-navigation smoke tests (leaves 9 and 22).
  *
  * Leaf 9 (INV-003): a fullLinkJS bundle loaded into Chrome over an SSG'd chrome page mounts, and the
  * post-mount `document.body.innerHTML` equals the SSG'd chrome (no content change on replace-on-load).
  * Leaf 22 (INV-013): clicking a sidebar link swaps the content area without a full page reload,
  * `UILocation.current` updates, and the `<head>` stylesheet survives the navigation.
  *
  * Both leaves require a real served-bundle Chrome context, the same `kyo-browser` SharedChrome
  * harness that `kyo.UITestSpaSmokeTest` uses. That harness is wired into `kyo-ui` (via
  * `kyo-ui-spa-harness` and a `kyo-browser % Test` dependency), not into `kyo-website-bundle`, so these
  * leaves cannot execute under the current bundle build. They are the campaign-end cross-platform gate
  * leaves: activating them needs the bundle to gain the served-bundle harness wiring, which is a build
  * surface beyond this deletion-and-workflow phase. They stay `ignore` so the bundle test run is green
  * while the gap is tracked as the gate's outstanding item rather than masked by an empty assertion.
  */
class WebsiteSpaSmokeTest extends AsyncFreeSpec with NonImplicitAssertions with BaseKyoCoreTest:

    type Assertion = org.scalatest.Assertion
    def assertionSuccess              = succeed
    def assertionFailure(msg: String) = fail(msg)

    override given executionContext: ExecutionContext = Platform.executionContext

    // Leaf 9: in-Chrome chrome parity. Cross-platform gate; needs the served-bundle Chrome harness.
    "post-mount chrome DOM equals the SSG chrome (leaf 9, INV-003, cross-platform gate)" ignore {
        // Gate: serve the fullLinkJS bundle over the SSG'd chrome page in Chrome, runMount, read
        // document.body.innerHTML after mount, normalize volatile ids, and assert it equals the SSG
        // chrome HTML. Requires the kyo-browser SharedChrome harness wired into the bundle.
        succeed
    }

    // Leaf 22: in-Chrome SPA navigation. Cross-platform gate; needs the served-bundle Chrome harness.
    "click-to-navigate swaps content without reload (leaf 22, INV-013, cross-platform gate)" ignore {
        // Gate: mount the docs app in the browser harness, click a sidebar link, assert:
        // - The content area (data-kyo-reactive span) changes to the new route's article.
        // - UILocation.current reflects the new route.
        // - The <head> stylesheet is still present (not clobbered by the content swap).
        // - window.location.href did NOT change (no full page reload).
        succeed
    }

end WebsiteSpaSmokeTest
