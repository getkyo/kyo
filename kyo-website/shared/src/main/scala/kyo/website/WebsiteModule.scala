package kyo.website

import kyo.*

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
      * `Core`, `kyo-stats-registry` becomes `Stats Registry`, `kyo-scheduler-zio` becomes
      * `Scheduler Zio`. Slugs without a `kyo-` prefix are transformed the same way.
      */
    def displayName: String =
        val base = if slug.startsWith("kyo-") then slug.stripPrefix("kyo-") else slug
        base.split('-').iterator.filter(_.nonEmpty).map(_.capitalize).mkString(" ")
    end displayName

end WebsiteModule

object WebsiteModule:
    /** Per-module platform support, read from the root README's module table: the JVM and Scala Native, and the two Scala.js targets,
      * JS and WASM (the Scala.js artifact linked with the WebAssembly backend), each with the environments its output runs in.
      *
      * The table's columns are read by their header names, so every release tag's table parses: a table with no WASM column reads as
      * not built for WASM, and one whose JS and WASM columns predate the Node and Browser split reads with each `browser` absent, since
      * such a table does not say (see `WebsiteContent.buildModule`).
      */
    final case class Platforms(jvm: Boolean, js: Environments, native: Boolean, wasm: Environments) derives CanEqual:

        /** One label per platform the module is built for, in table order, naming each Scala.js environment when the table does. */
        def labels: Chunk[String] =
            def target(name: String, environments: Environments): Chunk[String] =
                environments.browser match
                    case Absent => if environments.node then Chunk(name) else Chunk.empty
                    case Present(browser) =>
                        (if environments.node then Chunk(s"$name on Node") else Chunk.empty) ++
                            (if browser then Chunk(s"$name in a browser") else Chunk.empty)
            (if jvm then Chunk("JVM") else Chunk.empty) ++ target("JS", js) ++ (if native then Chunk("Native") else Chunk.empty) ++
                target("WASM", wasm)
        end labels
    end Platforms

    object Platforms:
        /** Built for every platform and run in every environment. */
        val everywhere: Platforms = Platforms(true, Environments.both, true, Environments.both)

        /** Built for nothing, the value of a docs page that is not a module, such as the manifesto, and shows no platform line. */
        val none: Platforms = Platforms(false, Environments(false, Absent), false, Environments(false, Absent))
    end Platforms

    /** Where a Scala.js target's output runs: a Node process, and a browser page. `browser` is `Absent` when the table does not say. */
    final case class Environments(node: Boolean, browser: Maybe[Boolean]) derives CanEqual

    object Environments:
        /** Runs on Node and in a browser. */
        val both: Environments = Environments(true, Present(true))
    end Environments
end WebsiteModule
