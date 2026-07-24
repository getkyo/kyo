package kyo.doctest.internal

import dotty.tools.dotc.Compiler
import dotty.tools.dotc.Driver as DottyDriver
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.reporting.Diagnostic as DottyDiagnostic
import dotty.tools.dotc.reporting.Reporter
import dotty.tools.dotc.util.SourceFile
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import kyo.*
import kyo.doctest.*
import scala.collection.mutable

/** Wrapper around dotty.tools.dotc.Driver that provides a warm-driver pattern.
  *
  * Driver.init calls setup(args, initCtx) once to produce a configured Context. Subsequent Driver.compile calls create a fresh Run from
  * that cached Context, avoiding the overhead of re-parsing scalac options for every block.
  *
  * Concurrency: dotty's ContextBase carries a thread-ownership check: it records the thread that created the Context and asserts that all
  * subsequent operations happen on that same thread. To satisfy this constraint, all compile calls are dispatched to a dedicated
  * single-thread ExecutorService (the "compiler thread"). Kyo fibers may run on any OS thread; this indirection pins compilation to a fixed
  * thread regardless of which fiber invokes compile.
  *
  * Parallelism comes from cache hits (no Driver involvement) and from concurrent I/O, not from concurrent compilation on one Driver
  * instance.
  */
final private[kyo] class Driver private (
    ctx: Context,
    compiler: Compiler,
    outputDir: kyo.Path,
    compilerThread: ExecutorService,
    freshDriver: Boolean,
    driverArgs: Array[String]
):

    /** Compiles a single synthetic source and returns the result.
      *
      * The compile call is dispatched to the dedicated compiler thread so dotty's ContextBase thread-ownership check is satisfied
      * regardless of which kyo fiber thread invoked this method.
      *
      * @param source
      *   The synthetic source to compile.
      * @return
      *   A Driver.Outcome reflecting whether compilation succeeded or failed.
      */
    def compile(source: Driver.Source)(using Frame): Driver.Outcome < (Sync & Async) =
        Sync.defer {
            val cf = CompletableFuture.supplyAsync[Driver.Outcome](
                () => runOneCompile(source),
                compilerThread
            )
            Async.fromCompletionStage(cf)
        }

    private def runOneCompile(source: Driver.Source): Driver.Outcome =
        val reporter = new Driver.CapturingReporter
        // When freshDriver is set we rebuild Context+Compiler from scratch per compile. This drops the dotty
        // SymbolTable that caches macro-implementation denotations across Runs (notably dotty-cps-async's
        // SeqAsyncShift), which otherwise asserts "denotation invalid in run N" on the second compile.
        val (freshCtx, activeCompiler) =
            if freshDriver then
                val drv         = new Driver.AccessibleDriver
                val initContext = drv.publicInitCtx
                drv.setup(driverArgs, initContext) match
                    case None =>
                        throw Driver.DriverSetupException("dotty Driver.setup returned None for freshDriver rebuild")
                    case Some((_, configuredCtx)) =>
                        val comp = drv.publicNewCompiler(configuredCtx)
                        (configuredCtx.fresh.setReporter(reporter), comp)
                end match
            else
                (ctx.fresh.setReporter(reporter), compiler)
        // SourceFile.virtual is the canonical in-memory source creation pattern.
        val srcFile = SourceFile.virtual(source.name.toString, source.content)
        // newRun takes the Context via `using`; compileSources takes only List[SourceFile].
        val run = activeCompiler.newRun(using freshCtx)
        run.compileSources(List(srcFile))
        val diags = reporter.drain()
        toOutcome(diags)
    end runOneCompile

    private def toOutcome(diags: List[DottyDiagnostic]): Driver.Outcome =
        val converted = diags.map(convertDiag)
        val errors    = Chunk.from(converted.filter(_.severity == Driver.Diagnostic.Severity.Error))
        val warnings  = Chunk.from(converted.filter(_.severity == Driver.Diagnostic.Severity.Warning))
        if errors.isEmpty then Driver.Outcome.Ok(warnings)
        else Driver.Outcome.Failed(errors, warnings)
    end toOutcome

    private def convertDiag(d: DottyDiagnostic): Driver.Diagnostic =
        // Level constants come from dotty.tools.dotc.interfaces.Diagnostic (a Java interface).
        val severity = d.level match
            case dotty.tools.dotc.interfaces.Diagnostic.ERROR   => Driver.Diagnostic.Severity.Error
            case dotty.tools.dotc.interfaces.Diagnostic.WARNING => Driver.Diagnostic.Severity.Warning
            case _                                              => Driver.Diagnostic.Severity.Info
        val pos     = d.pos
        val line    = if pos.exists then pos.line + 1 else 0
        val col     = if pos.exists then pos.column + 1 else 0
        val srcName = if pos.exists && pos.source != null then pos.source.file.name else ""
        Driver.Diagnostic(
            severity = severity,
            file = kyo.Path(srcName),
            line = line,
            col = col,
            message = d.msg.message,
            related = Chunk.empty // v0.1: secondary positions deferred (dotty 3.8.3 API unstable)
        )
    end convertDiag

    /** Shuts down the compiler thread and deletes the temporary output directory tree created during init.
      *
      * Idempotent: safe to call multiple times.
      */
    def close(using Frame): Unit < Sync =
        // Swallow removeAll errors: if cleanup fails the OS temp cleaner will handle it.
        Abort.run[FileFsException](outputDir.removeAll).andThen(Sync.defer(compilerThread.shutdown()))

end Driver

private[kyo] object Driver:

    /** A synthetic Scala source file created from a block body.
      *
      * @param name
      *   Display name for the virtual file, used in error messages. Stored as a kyo.Path for consistency with the rest of the codebase.
      * @param content
      *   Full Scala source text to compile.
      */
    case class Source(name: kyo.Path, content: String) derives CanEqual

    /** Outcome of a single compilation attempt.
      *
      * Ok is returned when the compiler reports no errors. Failed is returned when the compiler reports one or more errors. Warnings are
      * captured in both cases.
      */
    sealed trait Outcome derives CanEqual
    object Outcome:
        case class Ok(warnings: Chunk[Driver.Diagnostic])                                       extends Outcome
        case class Failed(errors: Chunk[Driver.Diagnostic], warnings: Chunk[Driver.Diagnostic]) extends Outcome
    end Outcome

    /** A single compiler diagnostic mapped to a kyo-doctest-friendly shape.
      *
      * @param severity
      *   Error, Warning, or Info.
      * @param file
      *   The synthetic source file name (VirtualFile display name).
      * @param line
      *   1-indexed line number in the synthetic source.
      * @param col
      *   1-indexed column number.
      * @param message
      *   The diagnostic message text.
      * @param related
      *   Secondary-position diagnostics. Defaults to Chunk.empty in v0.1 (dotty 3.8.3 secondary-position API is unstable).
      */
    case class Diagnostic(
        severity: Driver.Diagnostic.Severity,
        file: kyo.Path,
        line: Int,
        col: Int,
        message: String,
        related: Chunk[Driver.Diagnostic]
    ) derives CanEqual

    object Diagnostic:
        enum Severity derives CanEqual:
            case Error, Warning, Info
        end Severity
    end Diagnostic

    /** A Reporter subclass that captures diagnostics into a buffer we own.
      *
      * dotty.tools.dotc.reporting.StoreReporter.infos is protected, so we cannot access it from outside the dotty package. This
      * implementation overrides doReport to push each Diagnostic into our own ArrayBuffer, which we can read and clear at will.
      *
      * Thread safety: this object is not thread-safe. Each Driver.compile call creates a fresh CapturingReporter and uses it on one thread
      * at a time behind the Driver's single-threaded executor.
      */
    private[Driver] class CapturingReporter extends Reporter:
        private val buf: mutable.ArrayBuffer[DottyDiagnostic] = mutable.ArrayBuffer.empty

        override def doReport(d: DottyDiagnostic)(using Context): Unit =
            val _ = buf.append(d)
        end doReport

        /** Returns all captured diagnostics and clears the buffer. */
        def drain(): List[DottyDiagnostic] =
            val result = buf.toList
            buf.clear()
            result
        end drain
    end CapturingReporter

    /** A thin subclass of DottyDriver that makes the protected members accessible.
      *
      * dotty's Driver.initCtx and Driver.newCompiler are declared `protected` in Scala source but are `public` in JVM bytecode. We expose
      * them as public methods here to avoid reflection hacks.
      */
    private[Driver] class AccessibleDriver extends DottyDriver:
        override def sourcesRequired: Boolean         = false
        def publicInitCtx: Context                    = initCtx
        def publicNewCompiler(ctx: Context): Compiler = newCompiler(using ctx)
    end AccessibleDriver

    private[Driver] class DriverSetupException(msg: String) extends RuntimeException(msg)

    /** Creates a warm Driver instance.
      *
      * Calls dotty Driver.setup once to configure classpath and scalac options, caching the resulting Context for reuse across all compile
      * calls.
      *
      * @param classpath
      *   Classpath entries to make available to the compiler.
      * @param scalacOpts
      *   Additional scalac options to pass to the compiler.
      * @return
      *   A configured Driver, or Abort[Doctest.Error.DriverInitFailed] if setup fails.
      */
    def init(
        classpath: Chunk[kyo.Path],
        scalacOpts: Chunk[String],
        freshDriver: Boolean
    )(using Frame): Driver < (Sync & Abort[Doctest.Error.DriverInitFailed]) =
        for
            id <- UUID.v4.map(_.show)
            dir = Path.basePaths.tmp / s"doctest-out-$id"
            _ <- Abort.run[FileFsException](dir.mkDir).flatMap {
                (r: Result[FileFsException, Unit]) =>
                    r match
                        case Result.Success(_) => Sync.defer(())
                        case Result.Failure(e) =>
                            Abort.fail[Doctest.Error.DriverInitFailed](Doctest.Error.DriverInitFailed(e))
                        case Result.Panic(t) =>
                            Abort.fail[Doctest.Error.DriverInitFailed](Doctest.Error.DriverInitFailed(t))
            }
            driver <- Sync.defer {
                try Right(buildDriver(classpath, scalacOpts, freshDriver, dir))
                catch case t: Throwable => Left(t)
            }.flatMap { (either: Either[Throwable, Driver]) =>
                either match
                    case Right(d) => Sync.defer(d)
                    case Left(t)  => Abort.fail[Doctest.Error.DriverInitFailed](Doctest.Error.DriverInitFailed(t))
            }
        yield driver

    private def buildDriver(
        classpath: Chunk[kyo.Path],
        scalacOpts: Chunk[String],
        freshDriver: Boolean,
        outputDir: kyo.Path
    ): Driver =
        val cpArg = classpath.map(_.toString).iterator.mkString(java.io.File.pathSeparator)
        val baseArgs = Array(
            "-classpath",
            cpArg,
            "-d",
            outputDir.toString
        )
        val allArgs  = baseArgs ++ scalacOpts.toSeq.toArray
        val dottyDrv = new AccessibleDriver

        // Single-threaded executor pins all compilation to one OS thread because dotty's
        // ContextBase carries a thread-ownership assertion: the Context created on thread X
        // must be reused only from thread X. Replacing this with a Kyo Mutex would only
        // serialize calls without pinning them to a thread, which would still trip the assertion.
        // The blocking .get() has been replaced by CompletableFuture.supplyAsync +
        // Async.fromCompletionStage so the Kyo carrier yields instead of blocking.
        val compilerThread = Executors.newSingleThreadExecutor()
        val setupResult = compilerThread.submit(new Callable[(Context, Compiler)]:
            def call(): (Context, Compiler) =
                val initContext = dottyDrv.publicInitCtx
                dottyDrv.setup(allArgs, initContext) match
                    case None =>
                        throw DriverSetupException(
                            "dotty Driver.setup returned None; invalid scalac arguments or classpath"
                        )
                    case Some((_, configuredCtx)) =>
                        val comp = dottyDrv.publicNewCompiler(configuredCtx)
                        (configuredCtx, comp)).get()

        val (configuredCtx, comp) = setupResult
        new Driver(configuredCtx, comp, outputDir, compilerThread, freshDriver, allArgs)
    end buildDriver

end Driver
