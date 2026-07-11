package kyo

import java.util.concurrent.CompletableFuture
import kyo.internal.*
import scala.jdk.CollectionConverters.*

class LocalBackendTest extends kyo.test.Test[Any]:

    /** Finds the first jar on java.class.path whose filename contains the given fragment. */
    private def findJar(fragment: String): String =
        java.lang.System.getProperty("java.class.path", "")
            .split(Path.pathSeparator.charAt(0))
            .find(_.contains(fragment))
            .getOrElse(throw new RuntimeException(s"Jar containing '$fragment' not found on java.class.path"))

    /** Builds a Compiler.Config that uses the jars named by the given fragment list as the target
      * classpath and the scala3-library as the compiler classpath. The pc needs at minimum the
      * scala3-library on its classpath to typecheck Scala 3 source.
      */
    private def realConfig(classpathFragments: String*): Compiler.Config =
        val pcClasspath = Seq(findJar("scala3-library"), findJar("scala-library"))
        Compiler.Config(
            toolchain = Compiler.Toolchain(
                scalaVersion = "3.8.4",
                compilerClasspath = Chunk.from(pcClasspath.map(Path(_)))
            ),
            classpath = Chunk.from(
                (pcClasspath ++ classpathFragments.map(findJar)).map(Path(_))
            ),
            scalacOptions = Chunk.empty,
            sourceRoots = Chunk.empty
        )
    end realConfig

    /** Re-checks `cond` every `step` until it holds or `attempts` are exhausted, suspending between
      * checks so the fiber under test makes progress. Returns the final value of `cond`.
      */
    private def pollUntil(attempts: Int, step: Duration)(cond: => Boolean)(using Frame): Boolean < Async =
        if cond || attempts <= 0 then cond
        else Async.sleep(step).andThen(pollUntil(attempts - 1, step)(cond))

    "a type-error buffer yields diagnostics with Error severity; a syntax-error buffer yields diagnostics; a clean buffer yields none" in {
        for
            backend <- LocalBackend.init(realConfig())
            uri = Compiler.Uri("Main.scala")

            typeErrorText = "val x: Int = \"not an int\""
            typeErrorResp <- backend.run(Request.Compile(uri, typeErrorText))
            _ = typeErrorResp match
                case Response.Diagnostics(diags) =>
                    assert(diags.nonEmpty, s"expected diagnostics for type error, got empty chunk")
                    assert(
                        diags.exists(_.severity == Compiler.Severity.Error),
                        s"expected at least one Error severity diagnostic; got: $diags"
                    )
                    assert(
                        diags.exists(d =>
                            d.severity == Compiler.Severity.Error &&
                                d.message.contains("Int") &&
                                (d.message.contains("String") || d.message.contains("Found") || d.message.contains("Required"))
                        ),
                        s"expected an Int-vs-String type-mismatch diagnostic; got: ${diags.map(_.message)}"
                    )
                case other => assert(false, s"expected Response.Diagnostics, got $other")

            syntaxErrorText = "object :"
            syntaxErrorResp <- backend.run(Request.Compile(uri, syntaxErrorText))
            _ = syntaxErrorResp match
                case Response.Diagnostics(diags) =>
                    assert(diags.nonEmpty, s"expected diagnostics for syntax error, got empty chunk")
                    assert(
                        diags.exists(_.severity == Compiler.Severity.Error),
                        s"expected at least one Error severity diagnostic for syntax error; got: $diags"
                    )
                case other => assert(false, s"expected Response.Diagnostics, got $other")

            cleanText = "object Main { }"
            cleanResp <- backend.run(Request.Compile(uri, cleanText))
            _ = cleanResp match
                case Response.Diagnostics(diags) =>
                    assert(diags.isEmpty, s"expected no diagnostics for clean buffer, got: $diags")
                case other => assert(false, s"expected Response.Diagnostics, got $other")

            _ <- backend.close
        yield ()
    }

    "completions at a member-access offset return real type-checked items including stable String members" in {
        for
            backend <- LocalBackend.init(realConfig())
            uri = Compiler.Uri("Completions.scala")

            text   = "object Main { val r = \"\".  }"
            offset = text.indexOf("\".") + 2

            resp <- backend.run(Request.Completions(uri, text, offset))
            _ = resp match
                case Response.Completions(items) =>
                    assert(items.nonEmpty, s"expected non-empty completions for String member access, got empty chunk")
                    val labels = items.map(_.label).toSeq
                    assert(
                        labels.exists(l => l == "length" || l.startsWith("length")),
                        s"expected 'length' (or 'length...') in completions for String; got labels: ${labels.take(20)}"
                    )
                case other => assert(false, s"expected Response.Completions, got $other")

            _ <- backend.close
        yield ()
    }

    "hover over a member access yields a non-empty Hover whose markdown carries the hovered symbol's type/signature" in {
        for
            backend <- LocalBackend.init(realConfig())
            uri = Compiler.Uri("Hover.scala")

            // Hover over `map` in `List(1).map`: the pc renders the method's signature, so the markdown
            // names `map` and carries its List/function types.
            text   = "object Main { val r = List(1).map(_ + 1) }"
            offset = text.indexOf("map") + 1

            resp <- backend.run(Request.Hover(uri, text, offset))
            _ = resp match
                case Response.Hover(Present(h)) =>
                    assert(h.markdown.nonEmpty, "expected non-empty hover markdown for List.map, got empty")
                    assert(
                        h.markdown.contains("map"),
                        s"expected the hover markdown to render the 'map' signature; got: ${h.markdown}"
                    )
                    assert(
                        h.markdown.contains("List") || h.markdown.contains("=>"),
                        s"expected the hover markdown to carry List.map's type/signature; got: ${h.markdown}"
                    )
                case other => assert(false, s"expected a present Response.Hover for List.map, got $other")

            _ <- backend.close
        yield ()
    }

    "signatureHelp inside a call site yields a Signature naming the called method's parameters" in {
        for
            backend <- LocalBackend.init(realConfig())
            uri = Compiler.Uri("Sig.scala")

            // Cursor right after the open paren of the call to `add(Int, Int)`: the pc reports the
            // signature of `add`, naming its two parameters and their types.
            text   = "object Main:\n  def add(x: Int, y: Int): Int = x + y\n  val r = add(1, 2)"
            offset = text.indexOf("add(1") + 4

            resp <- backend.run(Request.SignatureHelp(uri, text, offset))
            _ = resp match
                case Response.Signature(Present(sig)) =>
                    assert(sig.params.nonEmpty, s"expected non-empty params for add(Int, Int); got: $sig")
                    assert(sig.label.contains("add"), s"expected the signature label to name 'add'; got: ${sig.label}")
                    assert(sig.label.contains("Int"), s"expected the signature label to carry the parameter types; got: ${sig.label}")
                    assert(
                        sig.label.contains("x") && sig.label.contains("y"),
                        s"expected the signature label to name parameters x and y; got: ${sig.label}"
                    )
                case other => assert(false, s"expected a present Response.Signature for the add call, got $other")

            _ <- backend.close
        yield ()
    }

    "a symbol defined in a non-classpath source root resolves through Config.sourceRoots (-sourcepath wiring)" in {
        // A source root holding myroot/Defs.scala defining a symbol that is NOT on the classpath. The
        // pc can only resolve it if Config.sourceRoots is wired into the pc's -sourcepath.
        val srcRoot = java.nio.file.Files.createTempDirectory("kyo-compiler-sourceroot")
        val pkgDir  = srcRoot.resolve("myroot")
        java.nio.file.Files.createDirectories(pkgDir)
        val defsFile = pkgDir.resolve("Defs.scala")
        java.nio.file.Files.writeString(
            defsFile,
            """package myroot
              |
              |object Defs:
              |  def magicNumber: Int = 4242
              |""".stripMargin
        )
        srcRoot.toFile.deleteOnExit()
        pkgDir.toFile.deleteOnExit()
        defsFile.toFile.deleteOnExit()

        val uri = Compiler.Uri("Probe.scala")
        val text =
            """object Probe:
              |  val n = myroot.Defs.magicNumber""".stripMargin
        val offset = text.indexOf("magicNumber")

        val withoutRoots = realConfig() // sourceRoots empty
        val withRoots    = realConfig().copy(sourceRoots = Chunk(Path(srcRoot.toString)))

        for
            // Without the source root the symbol is off the classpath, so the pc does not resolve it to
            // its real definition (control case: proves the resolution below is sourceRoots, not luck).
            baseline     <- LocalBackend.init(withoutRoots)
            baselineResp <- baseline.run(Request.Symbol(uri, text, offset))
            _ = baselineResp match
                case Response.Symbol(Present(sym)) =>
                    assert(
                        sym.fullName != "myroot/Defs.magicNumber().",
                        s"without sourceRoots the pc must not resolve the source-root definition; got: ${sym.fullName}"
                    )
                case Response.Symbol(Absent) => ()
                case other                   => assert(false, s"expected Response.Symbol, got $other")
            _ <- baseline.close

            // With the source root wired into -sourcepath, the pc resolves the definition.
            backend <- LocalBackend.init(withRoots)
            symResp <- backend.run(Request.Symbol(uri, text, offset))
            _ = symResp match
                case Response.Symbol(Present(sym)) =>
                    assert(
                        sym.fullName == "myroot/Defs.magicNumber().",
                        s"expected fullName 'myroot/Defs.magicNumber().', got '${sym.fullName}'"
                    )
                    assert(sym.name == "magicNumber", s"expected simple name 'magicNumber', got '${sym.name}'")
                    assert(
                        sym.kind == Compiler.SymbolInfo.Kind.Method,
                        s"expected Method kind, got ${sym.kind}"
                    )
                case other => assert(false, s"expected a resolved Response.Symbol, got $other")

            // The buffer that references the source-root symbol now type-checks clean.
            compileResp <- backend.run(Request.Compile(uri, text))
            _ = compileResp match
                case Response.Diagnostics(diags) =>
                    assert(
                        !diags.exists(_.severity == Compiler.Severity.Error),
                        s"expected no Error diagnostics once the source root resolves, got: ${diags.map(_.message)}"
                    )
                case other => assert(false, s"expected Response.Diagnostics, got $other")

            _ <- backend.close
        yield ()
        end for
    }

    "the real bridge cancels its underlying pc future on a fiber interrupt (cf.cancel(true) wired end to end)" in {
        for
            // A future that never completes on its own, so only the interrupt's `cf.cancel(true)` can
            // finish the bridged op.
            blocked = new CompletableFuture[String]()
            fiber <- Fiber.initUnscoped(LocalBackend.bridge(blocked))
            // Gate on the fiber actually reaching the cancel-protected await before interrupting: the
            // bridge registers a `handle` dependent on `blocked`, so a positive dependent count proves
            // the `Sync.ensure` finalizer is in scope and the await is entered. Without this gate the
            // interrupt could land before the finalizer registers, so no cancel would ever fire.
            reached <- pollUntil(500, 10.millis)(blocked.getNumberOfDependents() > 0)
            _ = assert(reached, "the bridge must register on the pc future (reach its await) before the interrupt")
            _ <- fiber.interrupt
            _ <- Abort.run[Throwable](fiber.get)
            // The `Sync.ensure` finalizer can run a hair after `fiber.get` observes the interrupt
            // result, so poll (bounded) for the cancel rather than asserting once and racing it.
            cancelled <- pollUntil(500, 10.millis)(blocked.isCancelled)
            _ = assert(cancelled, "a fiber interrupt must cancel the bridged pc future via cf.cancel(true)")
        yield ()
    }

    "the real bridge maps an exceptionally-completed pc future to a typed Fatal, never an escaped panic" in {
        for
            failed = new CompletableFuture[String]()
            _      = failed.completeExceptionally(new RuntimeException("pc boom"))
            result <- Abort.run[CompilerException](LocalBackend.bridge(failed))
            _ = result match
                case Result.Failure(CompilerExecutionException(cause)) =>
                    assert(cause.getMessage.contains("boom"), s"expected the pc error message in the cause, got: ${cause.getMessage}")
                case other =>
                    assert(false, s"expected a typed CompilerExecutionException from an exceptionally-completed future, got: $other")
        yield ()
    }

    "a pc throw surfaces as a typed CompilerException, never an escaped throw (sub-case A and B)" in {
        for
            throwingBackend = new Backend:
                def run(request: Request)(using Frame): Response < (Async & Abort[CompilerException]) =
                    Abort.catching[Throwable](t => CompilerExecutionException(t)) {
                        Sync.defer[Response, Async](throw new RuntimeException("boom"))
                    }
                def close(using Frame): Unit < (Async & Abort[Throwable]) = ()

            runA = throwingBackend.run(Request.Compile(Compiler.Uri("x.scala"), ""))
            resultA <- Abort.run[CompilerException](runA)
            _ = resultA match
                case Result.Failure(e: CompilerExecutionException) =>
                    assert(e.getMessage.nonEmpty, "expected a non-empty message on the typed CompilerExecutionException")
                case Result.Panic(_) =>
                    assert(false, "sub-case A: exception escaped as Panic; expected typed Failure")
                case Result.Success(r) =>
                    assert(false, s"sub-case A: expected Failure, got Success($r)")

            badConfig = Compiler.Config(
                toolchain = Compiler.Toolchain(
                    scalaVersion = "3.8.4",
                    compilerClasspath = Chunk.empty
                ),
                classpath = Chunk(Path("/nonexistent/does/not/exist.jar")),
                scalacOptions = Chunk.empty,
                sourceRoots = Chunk.empty
            )
            initB = LocalBackend.init(badConfig).map { b =>
                b.run(Request.Compile(Compiler.Uri("x.scala"), "val x: Int = 1"))
            }
            resultB <- Abort.run[CompilerException](initB)
            _ = resultB match
                case Result.Failure(_) =>
                    ()
                case Result.Panic(_) =>
                    assert(false, "sub-case B: exception escaped as Panic; expected typed Failure")
                case Result.Success(Response.Diagnostics(diags)) =>
                    ()
                case Result.Success(other) =>
                    assert(false, s"sub-case B: unexpected Success($other)")
        yield ()
    }

    "didClose completes with Response.Closed and the uri can be recompiled after close" in {
        for
            backend <- LocalBackend.init(realConfig())
            uri  = Compiler.Uri("CloseMe.scala")
            text = "val x: Int = \"not an int\""

            compileResp <- backend.run(Request.Compile(uri, text))
            _ = compileResp match
                case Response.Diagnostics(diags) =>
                    assert(diags.nonEmpty, s"expected diagnostics before didClose, got empty chunk")
                    assert(diags.exists(_.severity == Compiler.Severity.Error), s"expected Error severity before close; got: $diags")
                case other => assert(false, s"expected Response.Diagnostics before close, got $other")

            closeResp <- backend.run(Request.DidClose(uri))
            _ = closeResp match
                case Response.Closed => succeed("didClose returned Response.Closed as expected")
                case other           => assert(false, s"expected Response.Closed, got $other")

            reopenResp <- backend.run(Request.Compile(uri, text))
            _ = reopenResp match
                case Response.Diagnostics(diags) =>
                    assert(diags.nonEmpty, s"expected diagnostics after reopen, got empty chunk")
                    assert(diags.exists(_.severity == Compiler.Severity.Error), s"expected Error severity after reopen; got: $diags")
                case other => assert(false, s"expected Response.Diagnostics after reopen, got $other")

            _ <- backend.close
        yield ()
    }

end LocalBackendTest
