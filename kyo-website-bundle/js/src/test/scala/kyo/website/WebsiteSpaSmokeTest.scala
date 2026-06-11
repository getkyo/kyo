package kyo.website

import kyo.*

/** In-Chrome chrome-parity and SPA-navigation smoke tests.
  *
  * Both leaves require a real served-bundle Chrome context wired into `kyo-website-bundle`, which is
  * not yet set up. They stay ignored so the bundle test run is green without masking the gap.
  */
class WebsiteSpaSmokeTest extends kyo.test.Test[Any]:

    override def config = super.config.sequential.failOnNoAssertion(false)

    // Leaf 9: in-Chrome chrome parity. Cross-platform gate; needs the served-bundle Chrome harness.
    "post-mount chrome DOM equals the SSG chrome (leaf 9, cross-platform gate)".ignore in {
        // Gate: serve the fullLinkJS bundle over the SSG'd chrome page in Chrome, runMount, read
        // document.body.innerHTML after mount, normalize volatile ids, and assert it equals the SSG
        // chrome HTML. Requires the kyo-browser SharedChrome harness wired into the bundle.
        succeed
    }

    // Leaf 22: in-Chrome SPA navigation. Cross-platform gate; needs the served-bundle Chrome harness.
    "click-to-navigate swaps content without reload (leaf 22, cross-platform gate)".ignore in {
        // Gate: mount the docs app in the browser harness, click a sidebar link, assert:
        // - The content area (data-kyo-reactive span) changes to the new route's article.
        // - UILocation.current reflects the new route.
        // - The <head> stylesheet is still present (not clobbered by the content swap).
        // - window.location.href did NOT change (no full page reload).
        succeed
    }

end WebsiteSpaSmokeTest
