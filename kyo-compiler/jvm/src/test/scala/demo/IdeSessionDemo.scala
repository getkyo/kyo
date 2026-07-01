package demo

import kyo.*
import kyo.Compiler
import kyo.Compiler.Completion
import kyo.Compiler.Diagnostic
import kyo.Compiler.Hover
import kyo.Compiler.Severity
import kyo.Compiler.Signature
import kyo.Compiler.SymbolInfo
import kyo.Compiler.Uri

/** A self-contained, runnable demo of a code editor's language-server backend: the
  * language-intelligence session an IDE/LSP host runs for one open Scala buffer, end to end over
  * kyo-compiler's public surface.
  *
  * It stands in for the editor that kyo-compiler exists to serve. The demo wires its own
  * [[Compiler.Pool]] and resolves the warm in-process [[Compiler]] for a version-matched toolchain (see
  * `withCompiler`); `flow` then plays one buffer's realistic lifecycle against the REAL Scala 3
  * presentation compiler: create an empty file, type code that does not compile, fix it in place, then
  * ask for completions, hover, signature help, and the symbol under the cursor, and finally close and
  * reopen the buffer. Every answer is checked in `validate` against a concrete value the pc must
  * produce, never "it returned".
  *
  * The toolchain is in-process on purpose: `scalaVersion` equals the running compiler version, so the
  * pool's version-match rule plus `isolate = false` route the handle to the same-JVM backend. That keeps
  * the demo a single JVM with no forked worker, while still driving the REAL Scala 3 presentation
  * compiler (`scala.meta.pc`, impl `dotty.tools.pc`). The pc needs scala3-library + scala-library on its
  * target classpath to typecheck Scala 3 source, so both jars (located on the test classpath) go on the
  * config classpath.
  *
  * Mechanism and why: the host never names a backend. It calls `Pool.init`, resolves a `Compiler` handle
  * for its build config, and drives the six neutral, offset-based ops; the pool keeps one warm pc
  * instance so the cold init is paid once and the whole session reuses it, exactly as an editor holds a
  * language server open across keystrokes. The internal backends (LocalBackend / SpawnBackend /
  * CompilerPool / Worker / Wire) never appear: this is the code an editor author writes on top of the
  * module, so the demo doubles as the worked example.
  *
  * Real-world concern a naive host misses, exercised here: the pc keys its typecheck cache by buffer
  * CONTENT, and offsets are UTF-16 code units into that exact content (line/column mapping is the host's
  * job). So an edited buffer must be re-reported under its new text, each cursor offset is computed
  * against the live string, and `didClose` drops the per-uri cache yet the same uri must still recompile
  * cold on the next request. A host that caches diagnostics by uri, or feeds byte offsets, silently
  * desyncs; this session edits in place and closes-then-reopens to pin that contract down.
  *
  * Dual-purpose: run it to print a narrated trace of the editor session against a live pc (it exits
  * non-zero if `validate` returns `Present`), and [[DemoValidationTest]] exercises the same `flow`
  * plus `validate` against a real pc as a CI guard.
  *
  * Run: `sbt 'kyo-compilerJVM/Test/runMain demo.IdeSessionDemo'`
  *
  * Demonstrates: Compiler.Pool.init, Compiler.Pool.Settings, Pool.compiler, Compiler.Config,
  * Compiler.Toolchain, Compiler.Uri, Compiler.compile, Compiler.completions, Compiler.hover,
  * Compiler.signatureHelp, Compiler.symbol, Compiler.didClose, CompilerException, Diagnostic,
  * Severity, Completion, Hover, Signature, SymbolInfo.
  */
object IdeSessionDemo extends KyoApp:

    /** The running compiler version. Must equal `build.sbt` `scala3Version`: the pool routes a
      * version-matched, `isolate = false` config to the in-process backend, and a stale value here would
      * force a forked worker that this demo does not provision a pc classpath for.
      */
    private val scalaVersion: String = "3.8.4"

    // Full run: open the pool, run `flow`, validate, narrate, and surface a `Present` verdict or an op
    // abort as a failure so the app exits non-zero.
    run {
        for
            _       <- banner("kyo-compiler demo: ide-session")
            _       <- Console.printLine(s"Opening Compiler.Pool, resolving the in-process compiler for Scala $scalaVersion ...\n")
            outcome <- Abort.run[CompilerException](withCompiler(c => flow(c).map(r => (r, validate(r)))))
            result <- outcome match
                case Result.Success((value, Absent)) =>
                    Console.printLine("\n[OK] validation passed").andThen(value)
                case Result.Success((_, Present(msg))) =>
                    Console.printLineErr(s"\n[FAIL] validation: $msg")
                        .andThen(Abort.fail(new RuntimeException(s"demo validation failed: $msg")))
                case Result.Failure(err) =>
                    Console.printLineErr(s"\n[FAIL] compiler op aborted: $err")
                        .andThen(Abort.fail(new RuntimeException(s"compiler op aborted: $err")))
                case Result.Panic(ex) =>
                    Abort.fail(ex)
            _ <- banner("Demo complete")
        yield result
    }

    /** The language-intelligence session. Receives the warm per-config handle and drives the public ops;
      * carries no asserts. The returned report is what `validate` inspects.
      */
    def flow(compiler: Compiler)(using Frame): SessionReport < (Async & Abort[CompilerException]) =
        // One uri for the whole session: a host edits the same buffer in place. The pc is content-keyed,
        // so each compile re-reports the live text under this uri.
        val buffer = Uri("Greeter.scala")

        // A type error: the value's declared type and its literal disagree.
        val broken = "val x: Int = \"not an int\""

        // The fixed buffer: a small object the rest of the session also queries.
        val fixed = "object Greeter:\n  def greet(name: String): String = \"hi \" + name\n  val msg = greet(\"world\")"

        // Each query carries its own text and a cursor offset computed against THAT text in UTF-16 code
        // units, the way a host maps a caret position to an offset.
        val completionText   = "object Main { val r = \"\".  }"
        val completionOffset = completionText.indexOf("\".") + 2

        val hoverText   = "object Main { val r = List(1).map(_ + 1) }"
        val hoverOffset = hoverText.indexOf("map") + 1

        val sigText   = "object Main:\n  def add(x: Int, y: Int): Int = x + y\n  val r = add(1, 2)"
        val sigOffset = sigText.indexOf("add(1") + 4

        val symText   = "object Greeter:\n  def greet(name: String): String = \"hi \" + name\n  val msg = greet(\"world\")"
        val symOffset = symText.lastIndexOf("greet")

        for
            _          <- step(1, "create: open a new, still-empty buffer")
            emptyDiags <- compiler.compile(buffer, "")

            _           <- step(2, "error: type a buffer that does not compile")
            brokenDiags <- compiler.compile(buffer, broken)

            _          <- step(3, "edit: fix the buffer in place (same uri, new content)")
            fixedDiags <- compiler.compile(buffer, fixed)

            _           <- step(4, "completions: members of a String at the cursor")
            completions <- compiler.completions(buffer, completionText, completionOffset)

            _     <- step(5, "hover: type information for List.map")
            hover <- compiler.hover(buffer, hoverText, hoverOffset)

            _         <- step(6, "signatureHelp: parameters of add(...) inside the call")
            signature <- compiler.signatureHelp(buffer, sigText, sigOffset)

            _      <- step(7, "symbol: the definition under the greet reference")
            symbol <- compiler.symbol(buffer, symText, symOffset)

            _             <- step(8, "close + restart: didClose drops the cache, then recompile cold")
            _             <- compiler.didClose(buffer)
            reopenedDiags <- compiler.compile(buffer, broken)
        yield SessionReport(
            emptyDiags = emptyDiags,
            brokenDiags = brokenDiags,
            fixedDiags = fixedDiags,
            completions = completions,
            hover = hover,
            signature = signature,
            symbol = symbol,
            reopenedDiags = reopenedDiags
        )
        end for
    end flow

    /** Design-derived validation of the session report. `Absent` = every answer matched the value the pc
      * must produce; `Present(message)` = a concrete mismatch, named for diagnosis.
      */
    def validate(result: SessionReport): Maybe[String] =
        // Each check pairs the design-derived expectation with the message that names the actual value,
        // so a failure pins down which op desynced. The first failing check is the verdict.
        val checks: Seq[(Boolean, String)] = Seq(
            !hasError(result.emptyDiags) ->
                s"a new empty buffer must have no Error diagnostics; got: ${result.emptyDiags.map(_.message)}",
            isIntVsStringError(result.brokenDiags) ->
                s"the type-error buffer must yield an Int-vs-String Error; got: ${result.brokenDiags.map(d => (d.severity, d.message))}",
            !hasError(result.fixedDiags) ->
                s"the fixed buffer must re-report clean (content-keyed cache); got: ${result.fixedDiags.map(_.message)}",
            completionsIncludeLength(result.completions) ->
                s"String member completions must include 'length'; got: ${result.completions.map(_.label).take(20)}",
            hoverDescribesMap(result.hover) ->
                s"hover over List.map must render the 'map' signature with its type; got: ${result.hover}",
            signatureDescribesAdd(result.signature) ->
                s"signatureHelp for add(...) must name 'add', its params x and y, and Int; got: ${result.signature}",
            symbolIsGreetMethod(result.symbol) ->
                s"symbol under the greet reference must resolve to the 'greet' Method; got: ${result.symbol}",
            reopenedReReportsError(result.reopenedDiags) ->
                s"the reopened buffer must recompile cold and re-report an Error; got: ${result.reopenedDiags.map(d =>
                        (d.severity, d.message)
                    )}"
        )
        Maybe.fromOption(checks.collectFirst { case (false, message) => message })
    end validate

    /** Opens a pool, resolves the warm handle for [[config]], and runs `f` against it. The pool is
      * scope-managed: on scope close the in-process pc is shut down. This is exactly the setup a host
      * writes once and reuses for every op on a buffer.
      */
    def withCompiler[A](f: Compiler => A < (Async & Abort[CompilerException]))(using
        Frame
    ): A < (Async & Scope & Abort[CompilerException]) =
        for
            pool     <- Compiler.Pool.init(Compiler.Pool.Settings(isolate = false))
            compiler <- pool.compiler(config)
            result   <- f(compiler)
        yield result

    /** The in-process, version-matched build config a host would resolve for one buildtarget: the
      * version-matched toolchain plus the stdlib on both the pc classpath and the typecheck classpath.
      */
    private def config: Compiler.Config =
        val stdlib = Chunk(findJar("scala3-library"), findJar("scala-library"))
        Compiler.Config(
            toolchain = Compiler.Toolchain(scalaVersion = scalaVersion, compilerClasspath = stdlib),
            classpath = stdlib,
            scalacOptions = Chunk.empty,
            sourceRoots = Chunk.empty,
            isolate = Present(false)
        )
    end config

    /** The typed answers from one editor session, one field per public op the session drives. */
    final case class SessionReport(
        emptyDiags: Chunk[Diagnostic],
        brokenDiags: Chunk[Diagnostic],
        fixedDiags: Chunk[Diagnostic],
        completions: Chunk[Completion],
        hover: Maybe[Hover],
        signature: Maybe[Signature],
        symbol: Maybe[SymbolInfo],
        reopenedDiags: Chunk[Diagnostic]
    ) derives CanEqual

    private def hasError(diags: Chunk[Diagnostic]): Boolean =
        diags.exists(_.severity == Severity.Error)

    /** The pc reports an Int-vs-String mismatch as an Error whose message names `Int` and the String
      * side. Requiring both rules out a generic "something is wrong" diagnostic passing as the match.
      */
    private def isIntVsStringError(diags: Chunk[Diagnostic]): Boolean =
        diags.exists { d =>
            d.severity == Severity.Error &&
            d.message.contains("Int") &&
            (d.message.contains("String") || d.message.contains("Found") || d.message.contains("Required"))
        }

    private def completionsIncludeLength(completions: Chunk[Completion]): Boolean =
        completions.map(_.label).exists(label => label == "length" || label.startsWith("length"))

    private def hoverDescribesMap(hover: Maybe[Hover]): Boolean =
        hover match
            case Present(h) => h.markdown.contains("map") && (h.markdown.contains("List") || h.markdown.contains("=>"))
            case Absent     => false

    private def signatureDescribesAdd(signature: Maybe[Signature]): Boolean =
        signature match
            case Present(sig) =>
                sig.params.nonEmpty &&
                sig.label.contains("add") &&
                sig.label.contains("Int") &&
                sig.label.contains("x") && sig.label.contains("y")
            case Absent => false

    private def symbolIsGreetMethod(symbol: Maybe[SymbolInfo]): Boolean =
        symbol match
            case Present(sym) =>
                sym.name == "greet" &&
                sym.kind == SymbolInfo.Kind.Method &&
                sym.fullName.contains("greet")
            case Absent => false

    private def reopenedReReportsError(diags: Chunk[Diagnostic]): Boolean =
        diags.nonEmpty && hasError(diags)

    // --- shared helpers ---

    /** Locates the first jar on `java.class.path` whose filename contains `fragment`. A setup-boundary
      * failure: a missing stdlib jar means the test classpath is misconfigured, not a session error.
      */
    private def findJar(fragment: String): Path =
        java.lang.System.getProperty("java.class.path", "")
            .split(Path.pathSeparator.charAt(0))
            .find(_.contains(fragment)) match
            case Some(p) => Path(p)
            case None    => throw new RuntimeException(s"no jar matching '$fragment' on java.class.path")

    private def banner(s: String)(using Frame): Unit < Sync =
        Console.printLine(s"\n=== $s ===\n")

    private def step(n: Int, desc: String)(using Frame): Unit < Sync =
        Console.printLine(s"[$n] $desc")

end IdeSessionDemo
