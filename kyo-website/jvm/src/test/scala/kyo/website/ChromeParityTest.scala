package kyo.website

import kyo.*

/** Verifies INV-003: the landing page renders byte-identically via `UI.runRender` (SSG path) and
  * `RecordingBackend.render` (the same renderer `DomBackend.mountInto` calls), at the initial
  * signal state (dropdown closed, no interaction). The two paths must produce identical HTML after
  * normalizing `data-kyo-path` attribute values (which are positional and deterministic for an
  * identical UI tree).
  *
  * JVM-only: `RecordingBackend` uses `HtmlRenderer` directly, which is `private[kyo]` and
  * accessible here because `kyo.website` is a subpackage of `kyo`. `UI.runMount` is JS-only;
  * this backend replaces it for parity assertions on the JVM.
  */
class ChromeParityTest extends Test:

    private val v1        = WebsiteVersion("v1.0.0-RC2", "1.0.0-RC2", true)
    private val v0        = WebsiteVersion("v0.9.3", "0.9.3", false)
    private val versions2 = Chunk(v1, v0)

    /** Strip `data-kyo-path="..."` values (they are positional indices and are deterministic for
      * an identical UI tree; this normalization is a safeguard for any future ordering sensitivity).
      */
    private def normalize(html: String): String =
        html.replaceAll("""data-kyo-path="[^"]*"""", "data-kyo-path=\"\"")
    end normalize

    "landing SSG vs RecordingBackend parity (INV-003)" in run {
        for
            view  <- LandingApp.view(versions2)
            ssg   <- UI.runRender(view).take(1).run.map(_.headMaybe.getOrElse(""))
            mount <- RecordingBackend.render(view)
        yield assert(
            normalize(ssg) == normalize(mount),
            "SSG (runRender) and mount (RecordingBackend) must produce identical HTML at initial state (INV-003)"
        )
        end for
    }

    "parity holds for the dropdown subtree (INV-003)" in run {
        for
            view  <- LandingApp.view(versions2)
            ssg   <- UI.runRender(view).take(1).run.map(_.headMaybe.getOrElse(""))
            mount <- RecordingBackend.render(view)
        yield
            val ssgNorm   = normalize(ssg)
            val mountNorm = normalize(mount)
            // Both outputs must contain the dropdown markup for both version labels
            assert(ssgNorm.contains("1.0.0-RC2"), "SSG must contain version label")
            assert(mountNorm.contains("1.0.0-RC2"), "mount must contain version label")
            assert(ssgNorm.contains("0.9.3"), "SSG must contain second version label")
            assert(mountNorm.contains("0.9.3"), "mount must contain second version label")
            // kyo-ui dropdown renders as a div with data-kyo-dropdown attribute (not a <select>).
            // The dropdown subtree must be identical between the two paths.
            val ssgDropStart   = ssgNorm.indexOf("data-kyo-dropdown")
            val mountDropStart = mountNorm.indexOf("data-kyo-dropdown")
            assert(ssgDropStart >= 0, "SSG must have dropdown element (data-kyo-dropdown)")
            assert(mountDropStart >= 0, "mount must have dropdown element (data-kyo-dropdown)")
            // Find the closing tag of the outer dropdown div: search for the hidden div that ends it
            val ssgDropEnd   = ssgNorm.indexOf("</div></div>", ssgDropStart)
            val mountDropEnd = mountNorm.indexOf("</div></div>", mountDropStart)
            val ssgDrop = if ssgDropEnd > ssgDropStart then ssgNorm.substring(ssgDropStart, ssgDropEnd) else ssgNorm.substring(ssgDropStart)
            val mountDrop = if mountDropEnd > mountDropStart then mountNorm.substring(mountDropStart, mountDropEnd)
            else mountNorm.substring(mountDropStart)
            assert(ssgDrop == mountDrop, "dropdown subtree must be identical between SSG and mount")
        end for
    }

end ChromeParityTest
