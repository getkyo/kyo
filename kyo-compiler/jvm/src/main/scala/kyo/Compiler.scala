package kyo

import kyo.internal.CompilerPool

/** A warm, 1:1-per-config presentation-compiler handle.
  *
  * `Compiler` is the per-config handle: it carries the six ops (`compile`, `completions`, `hover`,
  * `signatureHelp`, `symbol`, `didClose`) that drive the compiler-team-published presentation
  * compiler (`scala.meta.pc`, impl `dotty.tools.pc`) and adapts its results to neutral, serializable
  * shapes. kyo-compiler drives that published presentation compiler to produce every answer; it does
  * not implement a compiler of its own. Its companion `object Compiler` holds the [[Compiler.Pool]]
  * manager, the [[Compiler.Toolchain]]/[[Compiler.Config]]/[[Compiler.Pool.Settings]] configuration
  * types, the neutral offset-based result types, and the [[CompilerException]] failure hierarchy.
  *
  * Bound to exactly one `(toolchain, classpath, scalacOptions)` configuration; it resolves symbols
  * against that classpath and cannot serve a file from another config. Document text is passed per
  * call (uri + text [+ offset]); the handle stores no buffer and relies on the pc's content-keyed
  * typecheck cache. Every op is cancellable by interrupting the calling fiber; there is no cancel
  * method. Ops serialize per instance (one at a time; even queries mutate lazy denotations).
  *
  * The surface is backend-agnostic: a per-config compiler runs either in this JVM (version-matched
  * Local) or in a forked worker JVM (Spawn, any version, hard-killable), chosen internally by the
  * pool from `Config.isolate` and the version-match rule. The caller never names a backend.
  *
  * Every result type derives [[Compiler.AsMessage]] (the kyo-schema wire codec) so it is serializable
  * for the worker IPC wire and reusable for the LSP wire; the codec is the same one kyo-aeron's
  * `Topic` carries, so a result type rides the aeron transport directly.
  *
  * Offsets are UTF-16 code-unit offsets into `text`; line/column mapping is the caller's concern,
  * keeping this surface neutral.
  *
  * @see Compiler.Pool.init for the lifecycle entry point
  * @see Compiler.Pool.compiler for obtaining a handle
  */
abstract class Compiler:
    import Compiler.*

    /** Diagnostics for one uri's buffer (empty Chunk = clean). */
    def compile(uri: Uri, text: String)(using Frame): Chunk[Diagnostic] < (Async & Abort[CompilerException])

    /** Completions at an offset (empty Chunk = none). */
    def completions(uri: Uri, text: String, offset: Int)(using Frame): Chunk[Completion] < (Async & Abort[CompilerException])

    /** Hover information at an offset (Absent = none). */
    def hover(uri: Uri, text: String, offset: Int)(using Frame): Maybe[Hover] < (Async & Abort[CompilerException])

    /** Signature help at an offset (Absent = none). */
    def signatureHelp(uri: Uri, text: String, offset: Int)(using Frame): Maybe[Signature] < (Async & Abort[CompilerException])

    /** The symbol at an offset, with its live in-buffer definition span (Absent = none). */
    def symbol(uri: Uri, text: String, offset: Int)(using Frame): Maybe[SymbolInfo] < (Async & Abort[CompilerException])

    /** Drops the pc's per-uri cache. The result is `Unit`, but the op round-trips to the backend, so a
      * comms failure surfaces as `Abort[CompilerException]`.
      */
    def didClose(uri: Uri)(using Frame): Unit < (Async & Abort[CompilerException])
end Compiler

/** Drives the Scala 3 presentation compiler for IDE language intelligence.
  *
  * The companion of the [[Compiler]] handle and the module entry point: it carries the
  * [[Compiler.Pool]] manager, the [[Compiler.Toolchain]]/[[Compiler.Config]]/[[Compiler.Pool.Settings]]
  * configuration types, the [[Compiler.AsMessage]] wire-codec alias, the neutral offset-based result
  * types, the opaque [[Compiler.Uri]], and the [[CompilerException]] failure hierarchy. Everything
  * except the six handle ops nests here.
  *
  * @see Compiler.Pool.init for the lifecycle entry point
  * @see Compiler for the per-config operations (the handle)
  */
object Compiler:

    /** Manages all presentation-compiler instances across configs and Scala versions.
      *
      * One pool owns the live pc instances: it resolves a warm per-config [[Compiler]] lazily,
      * serializes ops per instance, caps global concurrency, and evicts-and-closes idle instances.
      * Backend selection (same-JVM Local vs forked Spawn worker) is internal and driven by
      * `isolate` plus the version-match rule; the caller never names a backend.
      *
      * @see Compiler.Pool.init for the lifecycle entry point
      * @see Compiler for the per-config operations (the handle)
      */
    abstract class Pool:
        /** Lazily resolves the warm compiler for a config.
          *
          * Created single-flight on first op, reused while live, evicted and closed when idle (LRU,
          * `Settings.idleEviction`). One instance per distinct config. A returned [[Compiler]] whose
          * instance was concurrently evicted re-resolves on its next op (treat-closed-as-recreate);
          * the caller holds no stale-handle obligation.
          */
        def compiler(config: Config)(using Frame): Compiler < Sync
    end Pool

    object Pool:
        /** Opens a pool. Scope-managed: on scope close every live instance is closed and every
          * spawned worker JVM is force-killed.
          *
          * @param settings
          *   the pool-wide policy (concurrency cap, live-instance bound, idle eviction, isolate)
          */
        def init(settings: Settings = Settings.default)(using Frame): Pool < (Sync & Scope) =
            CompilerPool.init(settings)

        /** Pool-wide policy.
          *
          * `isolate` defaults true (the stability default): compilers run in forked worker JVMs
          * unless a per-config override opts into Local. A Scala-version mismatch forces a worker
          * regardless.
          *
          * @param isolate
          *   default backend choice; true runs forked workers, false the same-JVM Local path
          * @param maxConcurrentCompiles
          *   the global cap on concurrent pc requests across all instances
          * @param maxLiveCompilers
          *   the live-instance bound before LRU eviction
          * @param idleEviction
          *   how long an unused instance stays warm before idle eviction
          * @param readyTimeout
          *   how long to wait for a forked worker to answer its readiness probe before treating it as
          *   failed to start
          * @param stuckTimeout
          *   how long a single op may run before the pool reclaims the instance as genuinely stuck
          */
        final case class Settings(
            isolate: Boolean = true,
            maxConcurrentCompiles: Int = 4,
            maxLiveCompilers: Int = 16,
            idleEviction: Duration = 5.minutes,
            readyTimeout: Duration = 30.seconds,
            stuckTimeout: Duration = 1.minute
        ) derives CanEqual

        object Settings:
            val default: Settings = Settings()
    end Pool

    /** A Scala toolchain: the version plus the resolved presentation-compiler classpath.
      *
      * `compilerClasspath` is the caller-provided `scala3-presentation-compiler_3:vN` (+ transitive)
      * JAR paths. kyo-compiler does not resolve them; a caller that wants coursier resolution
      * composes it above and passes the paths.
      */
    final case class Toolchain(scalaVersion: String, compilerClasspath: Chunk[Path]) derives CanEqual

    /** A build configuration: one toolchain, one classpath, the scalac options and source roots,
      * and an optional per-config `isolate` override of the pool default (`Absent` = use the pool
      * default).
      */
    final case class Config(
        toolchain: Toolchain,
        classpath: Chunk[Path],
        scalacOptions: Chunk[String],
        sourceRoots: Chunk[Path],
        isolate: Maybe[Boolean] = Absent
    ) derives CanEqual

    /** The wire-codec alias (mirrors kyo-aeron's message requirement). A type deriving `AsMessage`
      * carries a kyo-schema `Schema`, so it rides the kyo-aeron `Topic` transport directly.
      */
    type AsMessage[A] = Schema[A]

    /** A neutral, offset-based file identity. Opaque over String so the surface stays free of
      * java.net.URI / lsp4j coupling; it IS a String on the wire. The `given Schema[Uri]` (over the
      * String form, via `transform`) keeps `Uri` opaque-over-String through serialization.
      */
    opaque type Uri = String
    object Uri:
        def apply(value: String): Uri             = value
        extension (uri: Uri) def asString: String = uri
        given Schema[Uri]                         = summon[Schema[String]].transform[Uri](Uri.apply)(_.asString)
    end Uri

    /** An offset span [start, end) in UTF-16 code units. */
    final case class Span(start: Int, end: Int) derives CanEqual, Compiler.AsMessage

    /** A diagnostic severity level. */
    enum Severity derives CanEqual, Compiler.AsMessage:
        case Error, Warning, Info, Hint

    /** One diagnostic for a buffer: its span, severity, message, and optional code. */
    final case class Diagnostic(
        span: Span,
        severity: Severity,
        message: String,
        code: Maybe[String] = Absent
    ) derives CanEqual, Compiler.AsMessage

    /** One completion candidate. */
    final case class Completion(
        label: String,
        kind: Completion.Kind,
        detail: Maybe[String] = Absent,
        insertText: Maybe[String] = Absent,
        documentation: Maybe[String] = Absent
    ) derives CanEqual, Compiler.AsMessage
    object Completion:
        /** The kind of a completion candidate. */
        enum Kind derives CanEqual, Compiler.AsMessage:
            case Value, Method, Field, Class, Trait, Object, Type, Package, Keyword, Param
    end Completion

    /** Hover information: rendered markdown and an optional span. */
    final case class Hover(markdown: String, span: Maybe[Span] = Absent) derives CanEqual, Compiler.AsMessage

    /** Signature help: the rendered label, its parameters, and the active parameter index. */
    final case class Signature(
        label: String,
        params: Chunk[Signature.Param],
        activeParam: Maybe[Int] = Absent
    ) derives CanEqual, Compiler.AsMessage
    object Signature:
        /** One signature parameter. */
        final case class Param(label: String, documentation: Maybe[String] = Absent) derives CanEqual, Compiler.AsMessage

    /** The symbol at a position: its name, fully-qualified name, kind, and the live in-buffer
      * definition span (the cross-file definition is the caller's to resolve via `fullName`).
      */
    final case class SymbolInfo(
        name: String,
        fullName: String,
        kind: SymbolInfo.Kind,
        localDefinition: Maybe[(Uri, Span)] = Absent
    ) derives CanEqual, Compiler.AsMessage
    object SymbolInfo:
        /** The kind of a symbol. */
        enum Kind derives CanEqual, Compiler.AsMessage:
            case Class, Trait, Object, Method, Val, Var, Type, Package, Param
    end SymbolInfo

end Compiler
