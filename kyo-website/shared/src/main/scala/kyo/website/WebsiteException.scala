package kyo.website

import kyo.*

/** Base for every kyo-website build-time failure. Mirrors the substrate exception rules: extends
  * KyoException, leaves are top-level + prefixed, each leaf is one failure mode with raw structured
  * fields, Frame captured. The whole family lives in this one `WebsiteException.scala` file, matching
  * how the kyo substrate organizes its exception hierarchies ([[kyo.FileException]],
  * [[kyo.HttpException]], [[kyo.BrowserException]]).
  */
sealed abstract class WebsiteException(message: => String = "", cause: String | Throwable = "")(using Frame)
    extends KyoException(message, cause)

/** Raised when a README cannot be read or fails to parse into the content model. The `detail` field
  * is the typed failure mode (see [[WebsiteReadmeException.ReadmeFailure]]), not a free-text string.
  */
final case class WebsiteReadmeException(path: Path, detail: WebsiteReadmeException.ReadmeFailure)(using Frame)
    extends WebsiteException(s"README $detail at $path")

object WebsiteReadmeException:
    /** Typed README-parse failure modes. Nested in this exception's companion (its owner), mirroring
      * how [[kyo.BrowserIFrameInvalidException.Reason]] scopes its detail enum to its owning exception
      * rather than sitting as a free-standing top-level type.
      */
    enum ReadmeFailure derives CanEqual:
        case Missing, MalformedGroups, MalformedTable
end WebsiteReadmeException

/** The build-time transpile of a module README to the UI article subtree failed. Rare: the kyo-parse
  * transpiler ([[DocsMarkdownRender.transpile]]) degrades unknown constructs to plain text rather than
  * raising, so this signals a genuinely unexpected failure in the render pipeline.
  */
final case class WebsiteMarkdownException(slug: String, detail: String)(using Frame)
    extends WebsiteException(s"markdown render failed for $slug: $detail")

/** Writing a route's file failed. */
final case class WebsiteEmitException(route: String, cause: Throwable)(using Frame)
    extends WebsiteException(s"emit failed for route $route", cause)
