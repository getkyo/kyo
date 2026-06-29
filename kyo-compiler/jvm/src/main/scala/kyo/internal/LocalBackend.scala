package kyo.internal

import Compiler.*
import dotty.tools.pc.ScalaPresentationCompiler
import java.util.concurrent.CompletableFuture
import kyo.*
import scala.meta.pc.PresentationCompiler

/** Drives `dotty.tools.pc.ScalaPresentationCompiler` directly (the version-matched fast path).
  *
  * Instantiates the pc once via `newInstance(buildTargetId, classpath, options)`, drives the six pc
  * methods, and bridges each returned CompletableFuture into Async with an explicit `cf.cancel(true)`
  * finalizer so a fiber interrupt cancels the underlying future (`Async.fromCompletionStage` alone
  * does not). Results are adapted by the shared [[Wire]] adapter, so the same code drives the
  * compiler in-process and inside the worker host. No scheduled executor is supplied, so the pc
  * never schedules a forced thread stop.
  */
final private[kyo] class LocalBackend(pc: PresentationCompiler) extends Backend:

    def run(request: Request)(using Frame): Response < (Async & Abort[CompilerException]) =
        request match
            case Request.Compile(uri, text) =>
                LocalBackend.bridge(pc.didChange(Wire.compileParams(uri, text))).map(d => Response.Diagnostics(Wire.toDiagnostics(text, d)))
            case Request.Completions(uri, text, offset) =>
                LocalBackend.bridge(pc.complete(Wire.offsetParams(uri, text, offset))).map(c => Response.Completions(Wire.toCompletions(c)))
            case Request.Hover(uri, text, offset) =>
                LocalBackend.bridge(pc.hover(Wire.offsetParams(uri, text, offset))).map(h => Response.Hover(Wire.toHover(text, h)))
            case Request.SignatureHelp(uri, text, offset) =>
                LocalBackend.bridge(pc.signatureHelp(Wire.offsetParams(uri, text, offset))).map(s =>
                    Response.Signature(Wire.toSignature(s))
                )
            case Request.Symbol(uri, text, offset) =>
                LocalBackend.bridge(pc.definition(Wire.offsetParams(uri, text, offset))).map(d =>
                    Response.Symbol(Wire.toSymbol(uri, text, d))
                )
            case Request.DidClose(uri) =>
                Sync.defer(pc.didClose(Wire.toAbsoluteUri(uri.asString))).andThen(Response.Closed)

    def close(using Frame): Unit < (Async & Abort[Throwable]) =
        Sync.defer(pc.shutdown())
end LocalBackend

private[kyo] object LocalBackend:
    /** Bridges a pc CompletableFuture into Async via `Async.fromCompletableFuture`, which cancels the
      * underlying future on a fiber interrupt and surfaces an exceptional completion as a typed
      * `Abort[Throwable]` rather than a panic. Every pc failure maps to `CompilerExecutionException`: a
      * synchronous throw while issuing the call (the outer `Abort.catching`), and an
      * exceptionally-completed future (recovered from the typed abort, unwrapping the
      * `CompletionException` cause).
      */
    private[kyo] def bridge[A](cf: => CompletableFuture[A])(using Frame): A < (Async & Abort[CompilerException]) =
        Abort.catching[Throwable](t => CompilerExecutionException(t)) {
            Sync.defer(cf).map { future =>
                Abort.run[Throwable](Async.fromCompletableFuture(future)).map {
                    case Result.Success(value) => value
                    case Result.Failure(error) =>
                        val cause = if error.getCause != null then error.getCause else error
                        Abort.fail(CompilerExecutionException(cause))
                    case Result.Panic(error) => Abort.fail(CompilerExecutionException(error))
                }
            }
        }

    /** Instantiates a version-matched pc for a config and wraps it in a LocalBackend. A pc that
      * cannot start surfaces as CompilerStartException on the caller's first op.
      */
    def init(config: Compiler.Config)(using Frame): Backend < (Async & Abort[CompilerException]) =
        Abort.run[Throwable] {
            Abort.catching[Throwable] {
                Sync.defer {
                    val base = new ScalaPresentationCompiler()
                    val pc = base.newInstance(
                        config.toolchain.scalaVersion,
                        classpathList(config),
                        optionsList(config)
                    )
                    new LocalBackend(pc): Backend
                }
            }
        }.map {
            case Result.Success(backend) => backend
            // Log the failure; the typed CompilerStartException carries the Scala version and the cause.
            case Result.Failure(t) =>
                Log.error("local presentation compiler failed to initialize", t)
                    .andThen(Abort.fail(CompilerStartException(config.toolchain.scalaVersion, t)))
            case Result.Panic(t) =>
                Log.error("local presentation compiler failed to initialize", t)
                    .andThen(Abort.fail(CompilerStartException(config.toolchain.scalaVersion, t)))
        }

    private def classpathList(config: Compiler.Config): java.util.List[java.nio.file.Path] =
        import scala.jdk.CollectionConverters.*
        config.classpath.map(p => java.nio.file.Paths.get(p.toString)).toList.asJava

    /** Builds the pc options from the config's scalac options, appending `-sourcepath <roots>` when the
      * config carries source roots. The roots are joined with the platform path separator into a single
      * `-sourcepath` value (two list elements, the flag then the joined value), the form the
      * presentation compiler reads. The `-sourcepath` lets the pc resolve symbols whose definitions live
      * in a source root but are not on the compiled classpath. Empty `sourceRoots` adds nothing, so a
      * config without roots keeps the bare scalac options.
      */
    private def optionsList(config: Compiler.Config): java.util.List[String] =
        import scala.jdk.CollectionConverters.*
        val base = config.scalacOptions.toList
        val withSourcepath =
            if config.sourceRoots.isEmpty then base
            else base ++ List("-sourcepath", config.sourceRoots.map(_.toString).mkString(Path.pathSeparator))
        withSourcepath.asJava
    end optionsList
end LocalBackend
