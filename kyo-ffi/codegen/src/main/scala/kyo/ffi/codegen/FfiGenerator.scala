package kyo.ffi.codegen

import java.nio.file.Files
import java.nio.file.Path
import kyo.ffi.codegen.emitters.*
import kyo.ffi.codegen.model.*

/** Build-tool-agnostic entry point for kyo-ffi code generation.
  *
  * Given a bindings module's compiled TASTy files and classpath, this function:
  *   1. Walks the TASTy, extracts every `Ffi`-extending trait into a [[model.TraitSpec]].
  *   2. Validates the specs (hard errors throw; soft allowlist misses surface as warnings).
  *   3. Emits one platform-specific impl file per trait to `outputDir`.
  *   4. Returns the list of files written, warnings, and the extracted specs.
  *
  * Designed to be invoked from sbt's `sourceGenerators` task, Mill, scala-cli, or a standalone tool. Safe to call repeatedly, output is
  * byte-stable for identical input.
  */
object FfiGenerator:

    /** Target platform selector for emission. */
    enum Platform derives CanEqual:
        case JVM, Native, JS

    /** Plugin configuration.
      *
      * @param libraryId
      *   optional global library identifier override (single-library mode).
      * @param extraLibraries
      *   additional library configurations (multi-library mode).
      * @param strictBlocking
      *   if true, blocking-allowlist misses throw instead of warn.
      * @param strictCallbacks
      *   if true, callback-retention-allowlist misses throw instead of warn.
      * @param includeDirs
      *   extra `-I` include directories for the Native header-availability probe. A binding that
      *   declares a vendored header (e.g. `openssl/ssl.h` from a staged BoringSSL tree, not on the
      *   system include path) is emitted as `@extern` (not a stub) only when the probe finds the
      *   header; these dirs let the probe see the staged tree (RI-006).
      */
    final case class Config(
        libraryId: Option[String],
        extraLibraries: Seq[LibraryConfig],
        strictBlocking: Boolean,
        strictCallbacks: Boolean,
        includeDirs: Seq[String] = Nil
    )

    /** Per-library configuration in multi-library mode.
      *
      * @param id
      *   library identifier matching the binding trait's resolved `library`.
      * @param cSources
      *   absolute paths to C source files for this library.
      * @param linkLibs
      *   names of system libraries to link against (without `lib` prefix or extension).
      */
    final case class LibraryConfig(id: String, cSources: Seq[Path], linkLibs: Seq[String])

    object Config:
        val default: Config = Config(
            libraryId = None,
            extraLibraries = Nil,
            strictBlocking = false,
            strictCallbacks = false
        )
    end Config

    /** Outcome of a [[generate]] invocation.
      *
      * @param files
      *   absolute paths of emitted Scala source files, one per discovered trait.
      * @param warnings
      *   build-warning messages (blocking/callback allowlist misses under non-strict config).
      * @param traits
      *   the extracted [[model.TraitSpec]]s, useful for tests and tooling.
      */
    final case class Result(
        files: Seq[Path],
        warnings: Seq[String],
        traits: Seq[TraitSpec]
    )

    /** Entry point.
      *
      * @param tastyFiles
      *   absolute paths to all `.tasty` files of the bindings module.
      * @param classpath
      *   classpath entries needed to resolve symbols (usually `Compile / dependencyClasspath` in sbt).
      * @param outputDir
      *   where generated sources go (e.g., `src_managed/main/kyo-ffi/`).
      * @param platform
      *   target platform for emission.
      * @param config
      *   plugin configuration.
      * @return
      *   paths of emitted files plus warnings plus the extracted specs.
      * @throws kyo.ffi.codegen.FfiExtractionError
      *   if TASTy extraction reports one or more trait-level errors.
      * @throws java.lang.IllegalStateException
      *   if structural validation fails, or if a soft check fires under a `strict*` config.
      */
    def generate(
        tastyFiles: Seq[String],
        classpath: Seq[String],
        outputDir: Path,
        platform: Platform,
        config: Config = Config.default
    ): Result =
        val specs = new TastyExtractor().inspect(tastyFiles.toList, classpath.toList)
        specs.foreach { s =>
            val errs = TypeValidator.validate(s)
            if errs.nonEmpty then
                throw new IllegalStateException(
                    s"[kyo-ffi] validation failed for ${s.fqcn}:\n${errs.map("  - " + _).mkString("\n")}"
                )
            end if
        }
        val warnings = collectWarnings(specs, config)
        val files    = specs.map(spec => writeSpec(spec, outputDir, platform, config.includeDirs))
        Result(files = files, warnings = warnings, traits = specs)
    end generate

    private[codegen] def writeSpec(spec: TraitSpec, outputDir: Path, platform: Platform, includeDirs: Seq[String] = Nil): Path =
        val rendered = platform match
            case Platform.JVM => JvmEmitter.emit(spec)
            case Platform.Native =>
                val available = headersAvailable(spec.headers, includeDirs)
                NativeEmitter.emit(spec, headersAvailable = available)
            case Platform.JS => JsEmitter.emit(spec)
        val packagePath =
            if spec.packageName.isEmpty then ""
            else spec.packageName.replace('.', '/')
        val dir  = if packagePath.isEmpty then outputDir else outputDir.resolve(packagePath)
        val file = dir.resolve(s"${spec.simpleName}Impl.scala")
        Files.createDirectories(dir)
        Files.writeString(file, rendered)
        file
    end writeSpec

    private[codegen] def collectWarnings(specs: Seq[TraitSpec], cfg: Config): Seq[String] =
        // Two-pass: collect entries per trait (preserving spec order, then method order), then emit one
        // `[<traitFqn>]` header per non-empty bucket with the entries indented underneath. Strict modes
        // still throw at the first miss so behaviour is otherwise unchanged.
        val out = scala.collection.mutable.Buffer.empty[String]
        for spec <- specs do
            val perTrait = scala.collection.mutable.Buffer.empty[String]
            for method <- spec.methods do
                // Blocking allowlist: symbol is in allowlist but method lacks @Ffi.blocking. Suppressed when the SAME C symbol is also bound
                // by a `@Ffi.blocking` method in this trait: that pairing is the intentional synchronous companion of a blocking binding
                // (e.g. a non-blocking `send` for use on a fd the caller has set O_NONBLOCK), not the accidental annotation omission this
                // heuristic targets. The blocking sibling is the one that carries the GC/parking contract; the sync one is a deliberate seam.
                val hasBlockingSibling =
                    spec.methods.exists(o => o.blocking && o.cSymbol == method.cSymbol)
                if !method.blocking && BlockingAllowlist.contains(method.cSymbol) && !hasBlockingSibling then
                    val msg =
                        s"${spec.fqcn}.${method.scalaName} resolves to C symbol " +
                            s"`${method.cSymbol}` which is in the blocking allowlist but the method is " +
                            s"not marked @Ffi.blocking. This is likely a bug, the call may block the " +
                            s"caller thread or deadlock the Scala Native GC."
                    if cfg.strictBlocking then throw new IllegalStateException(s"[kyo-ffi] $msg")
                    else perTrait += msg
                    end if
                end if
                // Retention allowlist: symbol retains the callback but no Guard parameter present.
                if method.callbackKind == CallbackKind.Transient && RetentionAllowlist.contains(method.cSymbol) then
                    val msg =
                        s"${spec.fqcn}.${method.scalaName} resolves to C symbol " +
                            s"`${method.cSymbol}` which retains the callback pointer but the method has " +
                            s"no Ffi.Guard parameter. Add `guard: Ffi.Guard` to the signature or the " +
                            s"callback will dangle after the call returns."
                    if cfg.strictCallbacks then throw new IllegalStateException(s"[kyo-ffi] $msg")
                    else perTrait += msg
                    end if
                end if
            end for
            if perTrait.nonEmpty then
                out += s"[kyo-ffi] [${spec.fqcn}]"
                perTrait.foreach(m => out += s"  - $m")
            end if
        end for
        out.toSeq
    end collectWarnings

    /** Check whether all declared C headers are available on the build host by running `cc -E -xc -` (the standard C preprocessor).
      *
      * Returns `true` when `headers` is empty (no requirement declared) or every `#include` resolves. Returns `false` when the preprocessor
      * rejects one or more includes or when `cc` is not found, the caller should emit runtime stubs instead of `@extern` declarations.
      *
      * `includeDirs` are added as `-I<dir>` so a vendored header (e.g. a staged BoringSSL tree not on the system path) is found and the
      * binding is emitted as `@extern` rather than a stub (RI-006).
      */
    private[codegen] def headersAvailable(headers: Seq[String], includeDirs: Seq[String] = Nil): Boolean =
        if headers.isEmpty then true
        else
            import scala.sys.process.*
            val includes = headers.map(h => s"#include <$h>").mkString("\n")
            val incFlags = includeDirs.flatMap(d => Seq("-I", d))
            val cmd      = Seq("cc", "-E", "-xc", "-") ++ incFlags
            // The `out`/`err` handlers MUST drain the process streams to completion. `cc -E` writes the full preprocessed
            // text to stdout; for a binding that declares several headers (e.g. the POSIX socket set) that output exceeds the
            // OS pipe buffer (~64 KiB). If nobody reads stdout, `cc` blocks writing and `exitValue()` deadlocks forever. We
            // discard the bytes but must read them so the pipe never fills.
            def drain(stream: java.io.InputStream): Unit =
                try
                    val buf = new Array[Byte](8192)
                    while stream.read(buf) >= 0 do ()
                finally stream.close()
            try
                val proc = cmd.run(new ProcessIO(
                    in =>
                        in.write(includes.getBytes)
                        in.close()
                    ,
                    drain,
                    drain
                ))
                proc.exitValue() == 0
            catch
                case _: Exception => false
            end try
        end if
    end headersAvailable

end FfiGenerator
