package kyo.website

import kyo.*

/** The complete render input for one documentation version: the root-README intro text, the
  * grouped modules, and the version record. A value (not files) so the renderer on `main` can
  * render any tag's content (render-from-tags, INV-006).
  */
final case class WebsiteContent(
    intro: String,
    groups: Chunk[WebsiteContent.Group],
    version: WebsiteVersion
) derives CanEqual

object WebsiteContent:
    /** One sidebar group: the group name (a root-README `### <Group>` heading) and its modules in
      * README order.
      */
    final case class Group(name: String, modules: Chunk[WebsiteModule]) derives CanEqual

    // plan: fromRepo(root: Path) is added in Phase 7 (the render-from-tags extractor). Phase 1
    // ships only the value contract and its derivations so apps/wrapper can import it.
end WebsiteContent
