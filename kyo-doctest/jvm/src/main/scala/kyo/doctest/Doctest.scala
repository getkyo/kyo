package kyo.doctest

import kyo.*
import kyo.Schema

/** Entry point and configuration for kyo-doctest, the Markdown code block validator.
  *
  * Typical usage: supply a Config describing which files to validate, then call check to receive a Report summarising the outcome across
  * all blocks.
  */
object Doctest:

    private given Schema[kyo.Path] =
        Schema.stringSchema.transform[kyo.Path](kyo.Path(_))(_.toString)

    /** Configuration for a single validation run.
      *
      * @param sources
      *   Markdown files to scan for scala code blocks.
      * @param classpath
      *   Classpath entries made available to the compiler for each block.
      * @param scalaOpts
      *   Additional scalac options forwarded to the compiler.
      * @param cache
      *   Directory used for the content-hash cache. Created if absent.
      * @param parallel
      *   Maximum number of blocks compiled concurrently.
      * @param predef
      *   Lines auto-injected at the top of every block's wrapped source, before the user body. Visible to ALL block scopes including
      *   `scope=env:NAME` groups. Empty by default; doctest is a general-purpose Scala 3 code block validator and applies no
      *   library-specific defaults.
      * @param freshDriver
      *   When true, every compile uses a freshly constructed dotty Compiler instead of the warm cached one. Required for modules using
      *   macros that register denotations into the compiler's symbol table (notably dotty-cps-async), where reusing the warm Compiler
      *   across runs trips a "denotation invalid in run N" assertion. Defaults to false (warm-driver pattern; significantly faster).
      */
    case class Config(
        sources: Chunk[Path],
        classpath: Chunk[Path],
        scalaOpts: Chunk[String],
        cache: Path,
        parallel: Int,
        predef: Chunk[String] = Chunk.empty,
        freshDriver: Boolean = false
    ) derives Schema, CanEqual

    /** Outcome of a single validation run.
      *
      * @param totalBlocks
      *   Total number of scala code blocks found across all source files.
      * @param cacheHits
      *   Number of blocks whose result was served from cache (not recompiled).
      * @param compiled
      *   Number of blocks that were actually sent to the compiler.
      * @param warnings
      *   Number of blocks that compiled but emitted at least one compiler warning. Blocks marked `doctest:expect=warns`
      *   are not counted (their warning is the expected outcome). A non-zero count means some README examples teach a
      *   warning-producing pattern and should be fixed.
      * @param failures
      *   Per-block failure details. Empty on full success.
      */
    case class Report(
        totalBlocks: Int,
        cacheHits: Int,
        compiled: Int,
        warnings: Int,
        failures: Chunk[Failure]
    ) derives CanEqual

    /** A single block that did not meet its expectation.
      *
      * @param file
      *   The source Markdown file containing the block.
      * @param line
      *   The 1-indexed line number of the opening backtick code block marker in the source file.
      * @param message
      *   Human-readable description of the failure, with line numbers mapped back to the Markdown source.
      */
    case class Failure(file: Path, line: Int, message: String) derives Schema, CanEqual

    // reportSchema is defined after Failure so that Schema[Failure] is in scope for derivation.
    private given reportSchema: Schema[Report] = Schema.derived[Report]

    /** Typed errors that abort a validation run before any per-block result can be produced.
      *
      * Per-block compile failures are represented as Failure entries in Report, not as Error aborts.
      */
    enum Error derives CanEqual:
        /** The Dotty Driver could not be initialised (classpath missing, corrupt, or incompatible). */
        case DriverInitFailed(cause: Throwable)

        /** A source path in Config.sources does not exist. */
        case SourceNotFound(path: Path)

        /** The on-disk cache entry exists but cannot be deserialised. */
        case CacheCorrupt(path: Path, cause: Throwable)

        /** A Markdown file could not be parsed (structural problem, not a type error in a block). */
        case ParseError(file: Path, line: Int, message: String)

        /** Config.sources is empty. Enabling KyoDoctestPlugin without any sources to validate is a build error. Set doctestSources
          * explicitly, or ensure the default README.md exists.
          */
        case NoSourcesConfigured

        /** A file-system operation failed (read, write, mkDir, list, remove, etc.).
          *
          * @param path
          *   The path on which the operation was attempted.
          * @param operation
          *   A short label identifying the operation (e.g. "read", "write", "mkDir", "list", "remove").
          * @param cause
          *   The underlying exception from the file-system layer.
          */
        case IoError(path: Path, operation: String, cause: Throwable)
    end Error

    /** Validates all scala code blocks in the supplied sources and returns a Report.
      *
      * The computation requires Sync for IO, Async for parallel block compilation, Scope for the Driver lifecycle, and Abort[Error] for
      * fatal setup failures.
      *
      * Delegates to internal.Orchestrator.run.
      */
    def check(config: Config)(using Frame): Report < (Sync & Async & Scope & Abort[Error]) =
        kyo.doctest.internal.Orchestrator.run(config)

end Doctest
