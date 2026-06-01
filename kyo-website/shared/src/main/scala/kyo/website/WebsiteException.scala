package kyo.website

import kyo.*

/** Base for every kyo-website build-time failure. Mirrors the substrate exception rules: extends
  * KyoException, leaves are top-level + prefixed, each leaf is one failure mode with raw structured
  * fields, Frame captured.
  */
abstract class WebsiteException(message: => String = "", cause: String | Throwable = "")(using Frame)
    extends KyoException(message, cause)

/** Raised when a README cannot be read or fails to parse into the content model. */
final case class WebsiteReadmeException(path: Path, detail: ReadmeFailure)(using Frame)
    extends WebsiteException(s"README $detail at $path")

/** The build-time flexmark render of a module README failed (rare; flexmark degrades gracefully). */
final case class WebsiteMarkdownException(slug: String, detail: String)(using Frame)
    extends WebsiteException(s"markdown render failed for $slug: $detail")

/** Writing a route's file failed. */
final case class WebsiteEmitException(route: String, cause: Throwable)(using Frame)
    extends WebsiteException(s"emit failed for route $route", cause)

/** Typed README-parse failure modes (not a free-text detail string). */
enum ReadmeFailure derives CanEqual:
    case Missing, MalformedGroups, MalformedTable
