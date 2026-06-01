package kyo.website

/** One module's documentation-page input: its URL slug, the root-README group it belongs to, its
  * display title, the raw README Markdown, and which platforms it supports.
  */
final case class WebsiteModule(
    slug: String,
    group: String,
    title: String,
    readme: String,
    platforms: WebsiteModule.Platforms
) derives CanEqual

object WebsiteModule:
    /** Per-module platform support, mirroring the root README's platform table columns. */
    final case class Platforms(jvm: Boolean, js: Boolean, native: Boolean) derives CanEqual
end WebsiteModule
