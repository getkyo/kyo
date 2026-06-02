package kyo.website

import kyo.*

/** JVM-only static-site generator: emits the landing page, versions manifest, artifact-root
  * files, and static assets into an output directory. Docs routes are added in Phase 7.
  *
  * `emit` is the single public entry point. It delegates to private helpers for each output
  * file, mapping kyo-core `Path` IO errors to `WebsiteEmitException` so the whole emit
  * aborts on the first write failure.
  *
  * The HTML document is produced by draining the first emission of
  * `WebsitePage.wrap(opts)(view)` (which delegates to `UI.runRenderPage` internally).
  * Taking only the first emission gives the initial static render; subsequent reactive
  * re-renders are irrelevant for SSG.
  *
  * Output directory layout (Phase 4 scope):
  * {{{
  *   <outDir>/index.html      -- landing page (INV-009)
  *   <outDir>/versions.json   -- version manifest (INV-010)
  *   <outDir>/CNAME           -- exactly "getkyo.io" (INV-011)
  *   <outDir>/.nojekyll       -- empty (INV-011)
  *   <outDir>/kyo.png         -- logo (copied from repo root)
  *   <outDir>/kyo.ico         -- favicon (copied from docs/)
  *   <outDir>/main.js         -- bundle (copied from bundleDir; Phase 4: stub)
  *   <outDir>/main.js.map     -- bundle source map (copied from bundleDir)
  * }}}
  */
object WebsiteGenerator:

    /** Filesystem paths the generator reads from, separate from the output target.
      *
      * `repoRoot` and `bundleDir` are inputs the deploy workflow supplies (the repo checkout and
      * the compiled `fullLinkJS` directory); they are not part of the `(content, outDir)` emit
      * contract. Carrying them in a typed config keeps the public `emit` signature to the two
      * arguments that vary per call while still naming the extra paths.
      *
      * @param repoRoot
      *   Absolute path to the repository root, used to locate `kyo.png` and `docs/kyo.ico`.
      * @param bundleDir
      *   Directory containing the compiled JS bundle (`main.js` and `main.js.map`). Passed in so
      *   the deploy workflow can supply the real `fullLinkJS` path; tests use a temp stub.
      */
    final case class Config(repoRoot: Path, bundleDir: Path) derives CanEqual

    /** Emits all Phase-4 artifact files into `outDir`.
      *
      * @param content
      *   The list of documentation versions to include in the version manifest and the version
      *   dropdown. May be empty; artifact-root files are always written.
      * @param outDir
      *   The root of the output directory tree. Created if it does not exist (`Path.write`
      *   default `createFolders=true`).
      * @param config
      *   The input paths (`repoRoot`, `bundleDir`) the generator copies assets from.
      */
    def emit(
        content: Chunk[WebsiteContent],
        outDir: Path,
        config: Config
    )(using Frame): Unit < (Async & Abort[WebsiteException]) =
        val versions = content.map(_.version)
        for
            _ <- emitLanding(versions, outDir)
            _ <- writeVersionsJson(versions, outDir)
            _ <- writeArtifactRootFiles(outDir)
            _ <- copyAssets(outDir, config.repoRoot, config.bundleDir)
        yield ()
        end for
    end emit

    // ---- Private helpers ----

    private def emitLanding(
        versions: Chunk[WebsiteVersion],
        outDir: Path
    )(using Frame): Unit < (Async & Abort[WebsiteException]) =
        val opts = WebsitePage.Options(
            title = "Kyo | Build with AI. Ship something that holds.",
            description =
                "Kyo is the reliability foundation for AI-built software: structured effects, typed errors, and production-grade concurrency on JVM, JS, and Native.",
            canonical = "https://getkyo.io/",
            bundleHref = "main.js",
            bootScenario = "landing"
        )
        for
            view <- LandingApp.view(versions)
            html <- wrapFirst(opts, view)
            _    <- writeRoute(outDir / "index.html", html)
        yield ()
        end for
    end emitLanding

    private def wrapFirst(opts: WebsitePage.Options, view: UI)(using Frame): String < Async =
        WebsitePage.wrap(opts)(view).take(1).run.map(_.headMaybe.getOrElse(""))

    private def writeRoute(path: Path, html: String)(using Frame): Unit < (Sync & Abort[WebsiteException]) =
        Abort.run[FileWriteException](path.write(html)).map {
            case Result.Success(_) => ()
            case Result.Failure(e) => Abort.fail(WebsiteEmitException(path.toString, e))
            case p: Result.Panic   => Abort.error(p)
        }

    private def writeString(route: String, path: Path, content: String)(using Frame): Unit < (Sync & Abort[WebsiteException]) =
        Abort.run[FileWriteException](path.write(content)).map {
            case Result.Success(_) => ()
            case Result.Failure(e) => Abort.fail(WebsiteEmitException(route, e))
            case p: Result.Panic   => Abort.error(p)
        }

    private def writeVersionsJson(
        versions: Chunk[WebsiteVersion],
        outDir: Path
    )(using Frame): Unit < (Sync & Abort[WebsiteException]) =
        val json = buildVersionsJson(versions)
        writeString("versions.json", outDir / "versions.json", json)
    end writeVersionsJson

    private def buildVersionsJson(versions: Chunk[WebsiteVersion]): String =
        if versions.isEmpty then "[]"
        else
            val entries = versions.toSeq.map { v =>
                s"""  {"tag": "${escJson(v.tag)}", "label": "${escJson(v.label)}", "latest": ${v.latest}}"""
            }
            entries.mkString("[\n", ",\n", "\n]")
        end if
    end buildVersionsJson

    private def escJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    private def writeArtifactRootFiles(outDir: Path)(using Frame): Unit < (Sync & Abort[WebsiteException]) =
        for
            _ <- writeString("CNAME", outDir / "CNAME", "getkyo.io")
            _ <- writeString(".nojekyll", outDir / ".nojekyll", "")
        yield ()

    private def copyFile(route: String, src: Path, dst: Path)(using Frame): Unit < (Sync & Abort[WebsiteException]) =
        Abort.run[FileFsException](src.copy(dst, replaceExisting = true)).map {
            case Result.Success(_) => ()
            case Result.Failure(e) => Abort.fail(WebsiteEmitException(route, e))
            case p: Result.Panic   => Abort.error(p)
        }

    private def copyAssets(
        outDir: Path,
        repoRoot: Path,
        bundleDir: Path
    )(using Frame): Unit < (Sync & Abort[WebsiteException]) =
        for
            _ <- copyFile("kyo.png", repoRoot / "kyo.png", outDir / "kyo.png")
            _ <- copyFile("kyo.ico", repoRoot / "docs" / "kyo.ico", outDir / "kyo.ico")
            _ <- copyFile("main.js", bundleDir / "main.js", outDir / "main.js")
            _ <- copyFile("main.js.map", bundleDir / "main.js.map", outDir / "main.js.map")
        yield ()

end WebsiteGenerator
