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
  *
  * Phase 4 note: the content manifest is empty (no docs versions) so the generator
  * writes the landing page from the empty-versions view plus the artifact-root files.
  * Phase 7 adds `--content` / `WebsiteContent.fromRepo` to populate the versions.
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
                WebsiteGenerator.emit(
                    Chunk.empty[WebsiteContent],
                    Path(outDir),
                    WebsiteGenerator.Config(Path(repoRoot), Path(bundleDir))
                )
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

    private def parseOut(theArgs: Chunk[String]): String =
        flagValue(theArgs, "--out").getOrElse("/tmp/kyo-site")

    private def parseBundleDir(theArgs: Chunk[String]): String =
        flagValue(theArgs, "--bundle-dir").getOrElse("/tmp/kyo-bundle")

    private def parseRepoRoot(theArgs: Chunk[String]): String =
        flagValue(theArgs, "--repo-root")
            .getOrElse(java.lang.System.getProperty("user.dir", "."))

    private def flagValue(theArgs: Chunk[String], flag: String): Maybe[String] =
        val idx = theArgs.indexWhere(_ == flag)
        if idx >= 0 && idx + 1 < theArgs.size then Present(theArgs(idx + 1))
        else Absent
    end flagValue

end WebsiteMain
