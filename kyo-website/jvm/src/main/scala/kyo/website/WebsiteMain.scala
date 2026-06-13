package kyo.website

import kyo.*

/** CLI entry point for the kyo website static-site generator.
  *
  * Usage (the deploy workflow invocation, after `fullLinkJS` has produced the bundle):
  * {{{
  *   sbt 'kyo-websiteJVM/run --out site --content content'
  * }}}
  *
  * Arguments:
  *   - `--out <dir>`        Output directory (required). Created if absent.
  *   - `--bundle-dir <dir>` Directory containing the compiled `main.js` bundle. Optional: when absent,
  *                          the directory is discovered under
  *                          `<repo-root>/kyo-website-bundle/js/target/scala-<version>/` (the `fullLinkJS`
  *                          `-opt` output holding `main.js`), so the deploy workflow needs no path flag.
  *   - `--repo-root <dir>`  Repo root for locating `kyo.png` and `kyo-website/assets/kyo.ico`.
  *                          When absent, the root is discovered by walking up from the working
  *                          directory to the nearest ancestor holding a `build.sbt` (so a forked
  *                          `sbt run` cwd of `kyo-website/jvm` still resolves correctly).
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
    // KyoApp entrypoint in kyo sub-package; Frame auto-derivation rejected, Frame.internal is the sanctioned hatch (D4-2, convention_sweep)
    run(program(using Frame.internal))(using Frame.internal, summon[Render[Unit]])

    private def program(using Frame): Unit < (Async & Scope & Abort[Any]) =
        val theArgs = args
        val outDir  = parseOut(theArgs)
        for
            repoRoot  <- parseRepoRoot(theArgs)
            bundleDir <- parseBundleDir(theArgs, repoRoot)
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
      * SEMANTIC version (`WebsiteVersion.tagOrdering`, oldest-first), not lexicographically and not
      * by the filesystem listing order, so the generator's `emitLatest` (newest stable, else newest
      * pre-release) and the `versions.json` dropdown see them in true version order independent of
      * the deploy workflow's `sort -V`. The version served as latest is flagged here via
      * `pickLatestTag` so the dropdown and banner agree with the `latest/` mirror. When `--content`
      * is absent, returns `Chunk.empty` (landing-only emit).
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
            case Result.Success(paths) => Chunk.from(paths.toSeq.sortBy(tagName)(using WebsiteVersion.tagOrdering))
            case Result.Failure(_)     => Chunk.empty
            case p: Result.Panic       => Abort.error(p)
        }

    private def tagName(path: Path): String =
        val s   = path.toString
        val sep = s.lastIndexOf('/')
        if sep >= 0 then s.substring(sep + 1) else s
    end tagName

    /** The tag served as `latest`: the newest STABLE version by semantic ordering, falling back to
      * the newest pre-release only when no stable tag exists. Order-independent: the result is
      * computed via `WebsiteVersion.tagOrdering` (the max), not by taking the last element of the
      * input, so a lexicographic or shuffled `tags` argument yields the same answer. A tag is stable
      * when it parses to a `WebsiteVersion.Parsed` with no pre-release suffix; unparseable tags are
      * treated as non-stable (and rank oldest under the ordering), so they win only when nothing
      * else is present.
      */
    private def pickLatestTag(tags: Chunk[String]): Maybe[String] =
        val stable = tags.filter(t => WebsiteVersion.parse(t).exists(_.preRelease.isEmpty))
        val pool   = if stable.nonEmpty then stable else tags
        Maybe.fromOption(pool.maxOption(using WebsiteVersion.tagOrdering))
    end pickLatestTag

    private[website] def parseOut(theArgs: Chunk[String]): String =
        flagValue(theArgs, "--out").getOrElse("/tmp/kyo-site")

    /** Resolve the directory holding the linked `main.js` bundle.
      *
      * When `--bundle-dir <dir>` is supplied (tests, manual runs), that path wins. When it is absent,
      * the deploy workflow has already produced the bundle via
      * `sbt 'kyo-website-bundleJS/Compile/fullLinkJS'` and the directory is discovered under
      * `<repoRoot>/kyo-website-bundle/js/target/scala-<version>/`: the Scala.js linker writes the
      * full-optimized output to a sibling whose name ends in `-opt` (the `fullLinkJS` convention,
      * distinct from the `-fastopt` and `-test-fastopt` siblings). The first such directory that
      * actually contains a `main.js` is returned, so a stale `-fastopt` directory never wins.
      *
      * When neither the flag nor a discovered directory is present, falls back to
      * `<repoRoot>/kyo-website-bundle/js/target/scala-3.8.3/kyo-website-bundle-opt`, the path the
      * current build (`build.sbt` `scala3Version`, ESModule bundle) writes to. `copyAssets` then
      * reports a `WebsiteEmitException` if that path holds no `main.js`, so a missing bundle fails
      * loud rather than emitting a site with a broken script reference.
      */
    private[website] def parseBundleDir(theArgs: Chunk[String], repoRoot: String)(using Frame): String < (Sync & Abort[WebsiteException]) =
        flagValue(theArgs, "--bundle-dir") match
            case Present(dir) => dir
            case Absent =>
                Abort.run[FileFsException](discoverBundleDir(repoRoot)).map {
                    case Result.Success(dir) => dir
                    case Result.Failure(e)   => Abort.fail(WebsiteEmitException("bundle-dir discovery", e))
                    case p: Result.Panic     => Abort.error(p)
                }
        end match
    end parseBundleDir

    private def discoverBundleDir(repoRoot: String)(using Frame): String < (Sync & Abort[FileFsException]) =
        val fallback  = Path(repoRoot, "kyo-website-bundle", "js", "target", "scala-3.8.3", "kyo-website-bundle-opt")
        val targetDir = Path(repoRoot, "kyo-website-bundle", "js", "target")
        targetDir.isDirectory.map {
            case false => fallback.toString
            case true  =>
                // Both levels are sorted by path string so the `-opt`-with-main.js selection is
                // deterministic: when two `scala-*` dirs both hold an `-opt/main.js`, the listing order
                // is filesystem-dependent, so sorting picks the same directory on every run regardless
                // of the platform's listing order.
                targetDir.list.map { entries =>
                    val scalaDirs = entries.filter(_.name.exists(_.startsWith("scala-"))).sortBy(_.toString)
                    Kyo.foreach(scalaDirs)(_.list).map { listed =>
                        val optDirs = listed.flattenChunk
                            .filter(_.name.exists(_.endsWith("-opt")))
                            .sortBy(_.toString)
                        Kyo.foreach(optDirs)(d => (d / "main.js").isRegularFile.map(_ -> d)).map { flagged =>
                            flagged.collect { case (true, d) => d.toString }.headMaybe.getOrElse(fallback.toString)
                        }
                    }
                }
        }
    end discoverBundleDir

    /** The repo root for locating `kyo.png`, `kyo-website/assets/kyo.ico`, and the discovered bundle
      * directory. `--repo-root <dir>` overrides when supplied. Otherwise the root is discovered by
      * walking UP from `user.dir`: under `sbt 'kyo-websiteJVM/run …'` the forked cwd is
      * `kyo-website/jvm`, not the repo root, so the bare `user.dir` resolves those paths wrong. The
      * nearest ancestor directory holding a `build.sbt` (the repository's marker file) is the root;
      * falls back to `user.dir` if none is found. The method is effectful (`Sync & Abort[WebsiteException]`)
      * because it probes the filesystem during the walk; an explicit `--repo-root` flag short-circuits the walk.
      */
    private[website] def parseRepoRoot(theArgs: Chunk[String])(using Frame): String < (Sync & Abort[WebsiteException]) =
        flagValue(theArgs, "--repo-root") match
            case Present(dir) => dir
            case Absent =>
                Abort.run[FileFsException](discoverRepoRoot()).map {
                    case Result.Success(dir) => dir
                    case Result.Failure(e)   => Abort.fail(WebsiteEmitException("repo-root discovery", e))
                    case p: Result.Panic     => Abort.error(p)
                }
        end match
    end parseRepoRoot

    private def discoverRepoRoot()(using Frame): String < (Sync & Abort[FileFsException]) =
        System.property[String]("user.dir", ".").map { userDir =>
            val start                             = Path(userDir)
            def isRoot(dir: Path): Boolean < Sync = (dir / "build.sbt").isRegularFile
            def walkUp(dir: Path): Maybe[Path] < Sync =
                isRoot(dir).map {
                    case true => Present(dir)
                    case false =>
                        dir.parent match
                            case Present(p) => walkUp(p)
                            case Absent     => Absent
                }
            walkUp(start).map(_.map(_.toString).getOrElse(userDir))
        }
    end discoverRepoRoot

    private[website] def flagValue(theArgs: Chunk[String], flag: String): Maybe[String] =
        val idx = theArgs.indexWhere(_ == flag)
        if idx >= 0 && idx + 1 < theArgs.size then Present(theArgs(idx + 1))
        else Absent
    end flagValue

end WebsiteMain
