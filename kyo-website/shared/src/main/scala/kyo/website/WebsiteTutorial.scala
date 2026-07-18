package kyo.website

import kyo.*

/** First-class per-module tutorial child-route model. A tutorial is a public content value: a
  * validated slug/title/source declaration that participates in SSG emission, SPA route
  * classification, hydration parity, manifests, search, sitemap, and SEO head data.
  */
object WebsiteTutorial:

    /** A validated tutorial child-route declaration under a module's docs rail: a lowercase route
      * `slug`, a display `title`, and the in-repo `source` path whose Markdown the generator renders
      * as the tutorial page. Built through [[Declaration.init]], which validates the fields and
      * resolves `source` from a route-relative string.
      */
    final case class Declaration(slug: String, title: String, source: Path) derives CanEqual

    object Declaration:
        /** Validate a tutorial child-route declaration and resolve its source path. Fails loud with a
          * [[WebsiteTutorialException]] on an empty slug/title/source or a slug that is not a valid
          * lowercase route segment. `source` is a route-relative in-repo path string (e.g.
          * `"kyo-eventlog/docs/basic-eventlog.md"`), converted to a [[kyo.Path]] once validated.
          */
        def init(slug: String, title: String, source: String)(using Frame): Declaration < Abort[WebsiteException] =
            Abort.get(validate(slug, title, source))
    end Declaration

    /** The single validation authority for a tutorial declaration, shared with the tests and reused as
      * the fail path of [[Declaration.init]]. The slug mirrors the logical route-segment grammar used
      * across kyo (`kyo.JournalId.validate`): non-empty, only lowercase letters, digits, and `-`, with
      * no path separator, `..`, or `:`. Title and source must be non-empty. The empty source string is
      * rejected here before `Path.apply` (which silently drops empty parts, so `Path("")` would not
      * itself error).
      */
    private[website] def validate(slug: String, title: String, source: String)(using
        Frame
    ): Result[WebsiteException, Declaration] =
        if slug.isEmpty then Result.fail(WebsiteTutorialException("slug", WebsiteTutorialException.TutorialFailure.Empty))
        else if slug.contains("/") || slug.contains("..") || slug.contains(":") ||
            !slug.forall(c => (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-')
        then Result.fail(WebsiteTutorialException("slug", WebsiteTutorialException.TutorialFailure.InvalidSlug))
        else if title.isEmpty then Result.fail(WebsiteTutorialException("title", WebsiteTutorialException.TutorialFailure.Empty))
        else if source.isEmpty then Result.fail(WebsiteTutorialException("source", WebsiteTutorialException.TutorialFailure.Empty))
        else Result.succeed(Declaration(slug, title, Path(source)))
    end validate
end WebsiteTutorial
