package kyo.website

import kyo.*

/** CLI entry point for the kyo website static-site generator.
  *
  * Usage:
  * {{{
  *   sbt 'kyo-websiteJVM/run --out <output-dir> --bundle-dir <js-bundle-dir> --repo-root <repo-root>'
  * }}}
  *
  * Arguments:
  *   - `--out <dir>`        Output directory (required). Created if absent.
  *   - `--bundle-dir <dir>` Directory containing the compiled `main.js` bundle (required).
  *   - `--repo-root <dir>`  Repo root for locating `kyo.png` and `docs/kyo.ico`.
  *                          Defaults to the current working directory.
  *   - `--content <dir>`    Directory with one `<tag>/` subdirectory per version, each an extracted
  *                          tag tree (root `README.md` + `<slug>/README.md`). Each subdirectory is
  *                          read via `WebsiteContent.fromRepo` into the version manifest. When absent
  *                          or empty, the generator writes the landing page and artifact-root files
  *                          with no docs versions.
  */
object WebsiteMain extends KyoApp:

    // Unsafe: Frame.internal is used here because WebsiteMain is a KyoApp entrypoint
    // inside package kyo.website. The run block uses Frame for effectful calls;
    // Frame.internal is the sanctioned escape hatch for library-level entrypoints in
    // sub-packages of kyo that cannot use user-propagated Frame.
    // flow-allow: KyoApp entrypoint in kyo sub-package; Frame auto-derivation rejected, Frame.internal is the sanctioned hatch (D4-2, convention_sweep)
    run(program(using Frame.internal))(using Frame.internal, summon[Render[Unit]])

    private def program(using Frame): Unit < (Async & Scope & Abort[Any]) =
        val theArgs   = args
        val outDir    = parseOut(theArgs)
        val bundleDir = parseBundleDir(theArgs)
        val repoRoot  = parseRepoRoot(theArgs)
        for
            _ <- Console.printLine(
                s"WebsiteMain: out=$outDir bundleDir=$bundleDir repoRoot=$repoRoot"
            )
            result <- Abort.run[WebsiteException](
                for
                    content <- parseContent(theArgs)
                    _ <- WebsiteGenerator.emit(
                        content,
                        Path(outDir),
                        WebsiteGenerator.Config(Path(repoRoot), Path(bundleDir))
                    )
                yield ()
            )
            _ <- result match
                case Result.Success(_) =>
                    Console.printLine("WebsiteMain: done")
                case Result.Failure(e) =>
                    Console.printLine(s"WebsiteMain: emit failed: ${e.getMessage}")
                case p: Result.Panic =>
                    Console.printLine(s"WebsiteMain: panic: ${p.exception.getMessage}")
        yield ()
        end for
    end program

    /** Read the content manifest from the `--content <dir>` directory: one `WebsiteContent` per
      * `<tag>/` subdirectory, built via `WebsiteContent.fromRepo`. The subdirectories are sorted by
      * name (lexicographic, oldest-first for the `v0.x` ... `v1.x` scheme) so the generator's
      * `emitLatest` (newest stable, else newest pre-release) sees them in deploy order. The version
      * served as latest is flagged here so the dropdown and banner agree with the `latest/` mirror.
      * When `--content` is absent, returns `Chunk.empty` (landing-only emit).
      */
    private[website] def parseContent(theArgs: Chunk[String])(using Frame): Chunk[WebsiteContent] < (Sync & Abort[WebsiteException]) =
        flagValue(theArgs, "--content") match
            case Absent => Chunk.empty
            case Present(dir) =>
                for
                    tagDirs <- listTagDirs(Path(dir))
                    latestTag = pickLatestTag(tagDirs.map(tagName))
                    content <- Kyo.foreach(tagDirs) { tagDir =>
                        val tag     = tagName(tagDir)
                        val version = WebsiteVersion(tag, tag.stripPrefix("v"), latestTag.contains(tag))
                        WebsiteContent.fromRepo(tagDir, version)
                    }
                yield content
        end match
    end parseContent

    private def listTagDirs(contentDir: Path)(using Frame): Chunk[Path] < (Sync & Abort[WebsiteException]) =
        Abort.run[FileFsException](contentDir.list).map {
            case Result.Success(paths) => Chunk.from(paths.toSeq.sortBy(tagName))
            case Result.Failure(_)     => Chunk.empty
            case p: Result.Panic       => Abort.error(p)
        }

    private def tagName(path: Path): String =
        val s   = path.toString
        val sep = s.lastIndexOf('/')
        if sep >= 0 then s.substring(sep + 1) else s
    end tagName

    private def pickLatestTag(tags: Chunk[String]): Maybe[String] =
        val markers = Seq("-RC", "-M", "-SNAPSHOT", "-alpha", "-beta", "-rc")
        val stable  = tags.filter(t => !markers.exists(m => t.contains(m)))
        if stable.nonEmpty then stable.lastMaybe else tags.lastMaybe
    end pickLatestTag

    private[website] def parseOut(theArgs: Chunk[String]): String =
        flagValue(theArgs, "--out").getOrElse("/tmp/kyo-site")

    private def parseBundleDir(theArgs: Chunk[String]): String =
        flagValue(theArgs, "--bundle-dir").getOrElse("/tmp/kyo-bundle")

    private def parseRepoRoot(theArgs: Chunk[String]): String =
        flagValue(theArgs, "--repo-root")
            .getOrElse(java.lang.System.getProperty("user.dir", "."))

    private[website] def flagValue(theArgs: Chunk[String], flag: String): Maybe[String] =
        val idx = theArgs.indexWhere(_ == flag)
        if idx >= 0 && idx + 1 < theArgs.size then Present(theArgs(idx + 1))
        else Absent
    end flagValue

end WebsiteMain
