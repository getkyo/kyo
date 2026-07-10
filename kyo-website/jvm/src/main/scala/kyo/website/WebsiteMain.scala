package kyo.website

import kyo.*

/** CLI entry point for the kyo website static-site generator.
  *
  * Usage (the deploy workflow invocation, after `fullLinkJS` has produced the bundle):
  * {{{
  *   sbt 'kyo-websiteJVM/run --out site --repo-root .'
  * }}}
  *
  * The generator is FORWARD-ONLY: it renders the docs from the CURRENT repository state (the live
  * root `README.md` `## Modules` table plus each live `kyo-<slug>` module README), served as one
  * current documentation version and mirrored at `latest/`. It does NOT iterate or re-extract
  * historical git tags. A future release can append its own snapshot going forward via `--content`
  * (see [[parseContent]]); the normal push-to-main build renders just the current state.
  *
  * Arguments:
  *   - `--out <dir>`        Output directory (required). Created if absent.
  *   - `--bundle-dir <dir>` Directory containing the compiled `main.js` bundle. Optional: when absent,
  *                          the directory is discovered under
  *                          `<repo-root>/kyo-website-bundle/js/target/scala-<version>/` (the `fullLinkJS`
  *                          `-opt` output holding `main.js`), so the deploy workflow needs no path flag.
  *   - `--repo-root <dir>`  Repo root for reading the live root README + each module README, and for
  *                          locating `kyo.png` and `kyo-website/assets/kyo.ico`. When absent, the root
  *                          is discovered by walking up from the working directory to the nearest
  *                          ancestor holding a `build.sbt` (so a forked `sbt run` cwd of `kyo-website/jvm`
  *                          still resolves correctly).
  *   - `--content <dir>`    Forward-append snapshots: an optional directory with one `<tag>/`
  *                          subdirectory per PAST version to append alongside the current state, each an
  *                          extracted tag tree (root `README.md` + `<slug>/README.md`) read via
  *                          `WebsiteContent.fromRepo`. When absent (the normal push-to-main build), only
  *                          the current version is rendered.
  */
object WebsiteMain extends KyoApp:

    // Unsafe: Frame.internal is used here because WebsiteMain is a KyoApp entrypoint
    // inside package kyo.website. The run block uses Frame for effectful calls;
    // Frame.internal is the sanctioned escape hatch for library-level entrypoints in
    // sub-packages of kyo that cannot use user-propagated Frame.
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
            version <- currentVersion(repoRoot)
            result <- Abort.run[WebsiteException](
                for
                    content <- parseContent(theArgs, repoRoot, version)
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

    /** Build the documentation content, FORWARD-ONLY.
      *
      * The current `version` is supplied by the caller (`program` derives it from the live repo via
      * [[currentVersion]]; tests inject a fixed value). Its content is read from the LIVE repo at
      * `repoRoot` via `WebsiteContent.fromRepo`: the live root `README.md` `## Modules` table drives the
      * sidebar and each live module README becomes a module page. The current version is flagged
      * `latest = true`, so the generator serves it both at its own version path and mirrored under
      * `latest/`. No git tags are iterated and no per-tag extraction runs; the docs reflect exactly what
      * is checked out.
      *
      * Forward-append: when `--content <dir>` is supplied, each `<tag>/` subdirectory is appended as an
      * additional historical-snapshot version (read via `WebsiteContent.fromRepo`, ordered oldest-first
      * by semantic version, latest = false). This is the clean append path a future release-tag deploy
      * uses to add that version's snapshot alongside the current state; a snapshot whose tag matches the
      * current version, or a non-version directory, is dropped. The normal push-to-main build passes no
      * `--content`, so only the current version renders.
      *
      * The result is ordered oldest-first (appended snapshots first by semantic version, then the current
      * version last), matching the order `WebsiteGenerator` and the version dropdown expect.
      */
    private[website] def parseContent(theArgs: Chunk[String], repoRoot: String, version: WebsiteVersion)(using
        Frame
    ): Chunk[WebsiteContent] < (Sync & Abort[WebsiteException]) =
        for
            current  <- WebsiteContent.fromRepo(Path(repoRoot), version)
            appended <- appendedSnapshots(theArgs, version.tag)
        yield appended.append(current)

    /** The current documentation version, read from the live repo at `repoRoot`: the impure git read
      * ([[tagTimestamps]]) composed with the pure selection in [[currentVersionFrom]]. Kept thin so the
      * selection logic stays testable against a fixed tag set; see [[currentVersionFrom]].
      */
    private[website] def currentVersion(repoRoot: String)(using Frame): WebsiteVersion < Sync =
        tagTimestamps(repoRoot).map(currentVersionFrom)

    /** Pick the current documentation version from a `tag -> git-creation-timestamp` map. The newest tag
      * by timestamp (`WebsiteVersion.pickLatestByTimestamp`) is the version, flagged `latest = true`,
      * labelled with the leading `v` dropped. A newer pre-release wins over an older stable release,
      * matching what the repo's most recent `git tag` actually is.
      *
      * Total fallback: when the map is empty (no release tag: git missing, not a repo, or no `v[0-9]*`
      * tag) the version is `WebsiteVersion("current", "current", latest = true)`, so the build still
      * renders the live docs under a clearly-current label rather than failing.
      *
      * Pure and split from the impure git read so the selection can be tested with a fixed tag set,
      * independent of the live repo's tags (which change with every release, so a test asserting a frozen
      * tag against the live repo would break on the next release).
      */
    private[website] def currentVersionFrom(timestamps: Map[String, Long]): WebsiteVersion =
        WebsiteVersion.pickLatestByTimestamp(Chunk.from(timestamps.keys), timestamps) match
            case Present(tag) => WebsiteVersion(tag, tag.stripPrefix("v"), latest = true)
            case Absent       => WebsiteVersion("current", "current", latest = true)
    end currentVersionFrom

    /** Read the optional `--content <dir>` forward-append snapshots: one `WebsiteContent` per `<tag>/`
      * subdirectory, built via `WebsiteContent.fromRepo`, ordered oldest-first by semantic version. Only
      * release-version directories survive (a name is kept iff `WebsiteVersion.parse` accepts it); a
      * directory whose tag equals `currentTag` is dropped so the current version is not duplicated. When
      * `--content` is absent, returns `Chunk.empty` (the normal push-to-main build renders only the
      * current version).
      */
    private def appendedSnapshots(theArgs: Chunk[String], currentTag: String)(using
        Frame
    ): Chunk[WebsiteContent] < (Sync & Abort[WebsiteException]) =
        flagValue(theArgs, "--content") match
            case Absent => Chunk.empty
            case Present(dir) =>
                for
                    tagDirs <- listSnapshotDirs(Path(dir), currentTag)
                    content <- Kyo.foreach(tagDirs) { tagDir =>
                        val tag     = tagName(tagDir)
                        val version = WebsiteVersion(tag, tag.stripPrefix("v"), latest = false)
                        WebsiteContent.fromRepo(tagDir, version)
                    }
                yield content
        end match
    end appendedSnapshots

    /** Read each release tag's git creation timestamp (unix seconds) from the repo at `repoRoot` in a
      * single `git for-each-ref` call, returning a `tag -> seconds` map. The ref glob is
      * `refs/tags/v[0-9]*` (a release tag is `v` + a version number), not loose `v*`, so non-release
      * tags (`backup-*`, `bench-*`, `test`, ...) are excluded at the git layer; the map is then
      * re-filtered through `WebsiteVersion.parse` as the authoritative guard, so only tags that parse
      * to a `vMAJOR[.MINOR[.PATCH]][-PRE]` version survive even if the glob ever loosened.
      * `%(creatordate:unix)` covers both annotated tags (the tag object's date) and lightweight tags
      * (the commit's date), so every tag carries a date. Total: any failure (git missing, not a repo,
      * no tags, a malformed line) yields an EMPTY map, which makes `currentVersion` fall back to the
      * `"current"` label, so the build never breaks on the timestamp source.
      */
    private[website] def tagTimestamps(repoRoot: String)(using Frame): Map[String, Long] < Sync =
        Sync.defer {
            try
                import scala.sys.process.*
                val out = Seq(
                    "git",
                    "-C",
                    repoRoot,
                    "for-each-ref",
                    "--format=%(refname:short)=%(creatordate:unix)",
                    "refs/tags/v[0-9]*"
                ).!!
                out.linesIterator.foldLeft(Map.empty[String, Long]) { (acc, line) =>
                    val eq = line.lastIndexOf('=')
                    if eq <= 0 then acc
                    else
                        val tag = line.substring(0, eq)
                        if WebsiteVersion.parse(tag).isEmpty then acc
                        else
                            line.substring(eq + 1).trim.toLongOption match
                                case Some(ts) => acc.updated(tag, ts)
                                case None     => acc
                        end if
                    end if
                }
            catch case _: Throwable => Map.empty[String, Long]
        }
    end tagTimestamps

    /** List the `--content` snapshot directories, keeping ONLY release-version directories whose tag is
      * not the current version: a name is kept iff `WebsiteVersion.parse` accepts it (a
      * `vMAJOR[.MINOR[.PATCH]][-PRE]` tag) and it differs from `currentTag`, so a stray non-version dir
      * or a duplicate of the current version is dropped here as the authoritative guard. The survivors
      * are ordered oldest-first by semantic version (`WebsiteVersion.tagOrdering`), independent of the
      * filesystem listing order.
      */
    private def listSnapshotDirs(contentDir: Path, currentTag: String)(using
        Frame
    ): Chunk[Path] < (Sync & Abort[WebsiteException]) =
        Abort.run[FileException](Path.runReadOnly(contentDir.list)).map {
            case Result.Success(paths) =>
                val byName =
                    paths.toSeq
                        .map(p => tagName(p) -> p)
                        .filter((name, _) => WebsiteVersion.parse(name).isDefined && name != currentTag)
                        .toMap
                val ordered = byName.keys.toSeq.sorted(using WebsiteVersion.tagOrdering)
                Chunk.from(ordered.flatMap(byName.get))
            case Result.Failure(_) => Chunk.empty
            case p: Result.Panic   => Abort.error(p)
        }

    private def tagName(path: Path): String =
        val s   = path.toString
        val sep = s.lastIndexOf('/')
        if sep >= 0 then s.substring(sep + 1) else s
    end tagName

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
                Abort.run[FileException](discoverBundleDir(repoRoot)).map {
                    case Result.Success(dir) => dir
                    case Result.Failure(e)   => Abort.fail(WebsiteEmitException("bundle-dir discovery", e))
                    case p: Result.Panic     => Abort.error(p)
                }
        end match
    end parseBundleDir

    // List `dir`, flag each entry as a directory, collect those where the name matches `p`, sort by
    // path string. Both levels of the bundle discovery use this pipeline; the sort makes selection
    // deterministic regardless of the platform's filesystem listing order. The `isDirectory` guard
    // runs before any `.list` call so a regular file whose name matches the predicate is silently
    // skipped rather than causing a `FileNotADirectoryException`.
    private def childDirsMatching(dir: Path, p: String => Boolean)(using Frame): Chunk[Path] < (Sync & Abort[FileException]) =
        Path.runReadOnly {
            dir.list.map(entries =>
                Kyo.foreach(entries)(d => d.isDirectory.map(_ -> d)).map(
                    _.collect { case (true, d) if d.name.exists(p) => d }.sortBy(_.toString)
                )
            )
        }

    private def discoverBundleDir(repoRoot: String)(using Frame): String < (Sync & Abort[FileException]) =
        val fallback  = Path(repoRoot, "kyo-website-bundle", "js", "target", "scala-3.8.3", "kyo-website-bundle-opt")
        val targetDir = Path(repoRoot, "kyo-website-bundle", "js", "target")
        Path.runReadOnly {
            targetDir.isDirectory.map {
                case false => fallback.toString
                case true =>
                    for
                        scalaDirs   <- childDirsMatching(targetDir, _.startsWith("scala-"))
                        optDirs     <- Kyo.foreach(scalaDirs)(childDirsMatching(_, _.endsWith("-opt"))).map(_.flattenChunk)
                        flaggedMain <- Kyo.foreach(optDirs)(d => (d / "main.js").isRegularFile.map(_ -> d))
                    yield flaggedMain.collect { case (true, d) => d.toString }.headMaybe.getOrElse(fallback.toString)
            }
        }
    end discoverBundleDir

    /** The repo root for reading the live root README + each module README, locating `kyo.png` and
      * `kyo-website/assets/kyo.ico`, and the discovered bundle directory. `--repo-root <dir>` overrides
      * when supplied. Otherwise the root is discovered by walking UP from `user.dir`: under
      * `sbt 'kyo-websiteJVM/run …'` the forked cwd is `kyo-website/jvm`, not the repo root, so the bare
      * `user.dir` resolves those paths wrong. The nearest ancestor directory holding a `build.sbt` (the
      * repository's marker file) is the root; falls back to `user.dir` if none is found. The method is
      * effectful (`Sync & Abort[WebsiteException]`) because it probes the filesystem during the walk; an
      * explicit `--repo-root` flag short-circuits the walk.
      */
    private[website] def parseRepoRoot(theArgs: Chunk[String])(using Frame): String < (Sync & Abort[WebsiteException]) =
        flagValue(theArgs, "--repo-root") match
            case Present(dir) => dir
            case Absent =>
                Abort.run[FileException](discoverRepoRoot()).map {
                    case Result.Success(dir) => dir
                    case Result.Failure(e)   => Abort.fail(WebsiteEmitException("repo-root discovery", e))
                    case p: Result.Panic     => Abort.error(p)
                }
        end match
    end parseRepoRoot

    private def discoverRepoRoot()(using Frame): String < (Sync & Abort[FileException]) =
        System.property[String]("user.dir", ".").map { userDir =>
            val start                                 = Path(userDir)
            def isRoot(dir: Path): Boolean < PathRead = (dir / "build.sbt").isRegularFile
            def walkUp(dir: Path): Maybe[Path] < PathRead =
                isRoot(dir).map {
                    case true => Present(dir)
                    case false =>
                        dir.parent match
                            case Present(p) => walkUp(p)
                            case Absent     => Absent
                }
            Path.runReadOnly(walkUp(start).map(_.map(_.toString).getOrElse(userDir)))
        }
    end discoverRepoRoot

    private[website] def flagValue(theArgs: Chunk[String], flag: String): Maybe[String] =
        val idx = theArgs.indexWhere(_ == flag)
        if idx >= 0 && idx + 1 < theArgs.size then Present(theArgs(idx + 1))
        else Absent
    end flagValue

end WebsiteMain
