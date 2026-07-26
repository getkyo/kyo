package kyo.website

import kyo.*

/** The static registry of tutorial child routes attached to README-parsed modules.
  *
  * Production `WebsiteModule` values are parsed from the root README `## Modules` table by
  * `WebsiteContent.buildModule`, which knows only a module's slug, group, README, and platform
  * flags. The tutorial rail is authored knowledge the table does not carry, so it is centralized
  * here as an explicit slug-to-declarations map and attached during parsing. Tutorials are
  * declared, deterministic sources, never auto-discovered by walking the tree, matching
  * how the site already centralizes module knowledge in the README table.
  *
  * Each declaration names a route-relative in-repo source path, resolved against the repo root by
  * `WebsiteContent.loadTutorials`, and a display title. The three `kyo-eventlog` tutorials teach the
  * typed EventLog, the raw journal, and custom storage.
  */
private[website] object WebsiteTutorials:

    /** The registered tutorial child routes for `slug`, or `Chunk.empty` when the module registers
      * none. `WebsiteContent.buildModule` looks every parsed module up here and applies
      * `withTutorials` only when the result is non-empty.
      */
    def forModule(slug: String): Chunk[WebsiteTutorial.Declaration] =
        registry.getOrElse(slug, Chunk.empty)

    // Pre-validated literals: each slug is a lowercase route segment, each title is non-empty, and
    // each source is a non-empty route-relative path, so `WebsiteModule.withTutorials` checks only
    // cross-entry slug uniqueness (the three slugs here are distinct).
    private val registry: Map[String, Chunk[WebsiteTutorial.Declaration]] =
        Map(
            "kyo-eventlog" -> Chunk(
                WebsiteTutorial.Declaration("basic-eventlog", "Basic EventLog", Path("kyo-eventlog/docs/tutorials/basic-eventlog.md")),
                WebsiteTutorial.Declaration("raw-journal", "Raw Journal", Path("kyo-eventlog/docs/tutorials/raw-journal.md")),
                WebsiteTutorial.Declaration("custom-storage", "Custom storage", Path("kyo-eventlog/docs/tutorials/custom-storage.md"))
            )
        )
end WebsiteTutorials
