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
                    case Absent           => if environments.node then Chunk(name) else Chunk.empty
                    case Present(browser) =>
                        (if environments.node then Chunk(s"$name on Node") else Chunk.empty) ++
                            (if browser then Chunk(s"$name in a browser") else Chunk.empty)
            (if jvm then Chunk("JVM") else Chunk.empty) ++ target("JS", js) ++ (if native then Chunk("Native") else Chunk.empty) ++
                target("WASM", wasm)
        end labels

        /** The compact form the generated pages carry for the bundle, which rebuilds each module from them: `jvm=1 js=1? native=1
          * wasm=10`, one digit per Boolean, and `?` for a `browser` the table does not state. [[Platforms.decode]] reads it back.
          */
        def encoded: String =
            def bit(b: Boolean): String               = if b then "1" else "0"
            def environments(e: Environments): String = bit(e.node) + e.browser.fold("?")(bit)
            s"jvm=${bit(jvm)} js=${environments(js)} native=${bit(native)} wasm=${environments(wasm)}"
        end encoded
    end Platforms

    object Platforms:
        /** Built for every platform and run in every environment. */
        val everywhere: Platforms = Platforms(true, Environments.both, true, Environments.both)

        /** Built for nothing, the value of a docs page that is not a module, such as the manifesto, and shows no platform line. */
        val none: Platforms = Platforms(false, Environments(false, Absent), false, Environments(false, Absent))

        /** [[Platforms.encoded]] read back, or `Absent` for anything it did not write. */
        def decode(text: String): Maybe[Platforms] =
            def bit(c: Char): Maybe[Boolean] = c match
                case '1' => Present(true)
                case '0' => Present(false)
                case _   => Absent
            def environments(v: String): Maybe[Environments] =
                if v.length != 2 then Absent
                else
                    val browser: Maybe[Maybe[Boolean]] = if v(1) == '?' then Present(Absent) else bit(v(1)).map(Present(_))
                    for
                        node <- bit(v(0))
                        b    <- browser
                    yield Environments(node, b)
                    end for
            val fields = text.trim.split(' ').toSeq.flatMap { field =>
                field.split('=') match
                    case Array(key, value) => Seq(key -> value)
                    case _                 => Seq.empty
            }.toMap
            if fields.size != 4 then Absent
            else
                for
                    jvmValue    <- Maybe.fromOption(fields.get("jvm")).filter(_.length == 1).flatMap(v => bit(v(0)))
                    jsValue     <- Maybe.fromOption(fields.get("js")).flatMap(environments)
                    nativeValue <- Maybe.fromOption(fields.get("native")).filter(_.length == 1).flatMap(v => bit(v(0)))
                    wasmValue   <- Maybe.fromOption(fields.get("wasm")).flatMap(environments)
                yield Platforms(jvmValue, jsValue, nativeValue, wasmValue)
            end if
        end decode
    end Platforms

    /** Where a Scala.js target's output runs: a Node process, and a browser page. `browser` is `Absent` when the table does not say. */
    final case class Environments(node: Boolean, browser: Maybe[Boolean]) derives CanEqual

    object Environments:
        /** Runs on Node and in a browser. */
        val both: Environments = Environments(true, Present(true))
    end Environments
end WebsiteModule
