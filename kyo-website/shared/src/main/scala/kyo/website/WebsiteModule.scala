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
) derives CanEqual:
    /** Friendly nav-rail label derived from the slug: strips a leading `kyo-` prefix, then splits on
      * `-` and capitalizes each segment, joining with spaces so no hyphen survives. `kyo-core` becomes
      * `Core`, `kyo-stats-registry` becomes `Stats Registry`, `kyo-scheduler-cats` becomes
      * `Scheduler Cats`. Slugs without a `kyo-` prefix are transformed the same way.
      */
    def displayName: String =
        val base = if slug.startsWith("kyo-") then slug.stripPrefix("kyo-") else slug
        base.split('-').iterator.filter(_.nonEmpty).map(_.capitalize).mkString(" ")
    end displayName

end WebsiteModule

object WebsiteModule:
    /** Per-module platform support, mirroring the root README's platform table columns. */
    final case class Platforms(jvm: Boolean, js: Boolean, native: Boolean) derives CanEqual
end WebsiteModule
