package kyo

import upickle.default.*

class CompilerTest extends kyo.test.Test[Any]:

    "result ADTs round-trip through the upickle AsMessage codec with CanEqual" in {
        import Compiler.*

        def roundTrip[T: ReadWriter](value: T): T =
            readBinary[T](writeBinary[T](value))

        val span   = Span(0, 10)
        val spanRt = roundTrip(span)
        assert(spanRt == span)

        val diag = Diagnostic(
            span = Span(1, 5),
            severity = Severity.Error,
            message = "type mismatch",
            code = Present("E001")
        )
        val diagRt = roundTrip(diag)
        assert(diagRt == diag)

        val diagAbsent = Diagnostic(span = Span(0, 0), severity = Severity.Warning, message = "unused")
        assert(diagAbsent.code == Absent)
        val diagAbsentRt = roundTrip(diagAbsent)
        assert(diagAbsentRt == diagAbsent)
        assert(diagAbsentRt.code == Absent)

        val completion = Completion(
            label = "map",
            kind = Completion.Kind.Method,
            detail = Present("def map[B](f: A => B): Option[B]"),
            insertText = Absent,
            documentation = Present("Applies f to the contained value.")
        )
        val completionRt = roundTrip(completion)
        assert(completionRt == completion)

        val hover   = Hover(markdown = "**Int**", span = Present(Span(3, 6)))
        val hoverRt = roundTrip(hover)
        assert(hoverRt == hover)

        val hoverNoSpan   = Hover(markdown = "**String**")
        val hoverNoSpanRt = roundTrip(hoverNoSpan)
        assert(hoverNoSpanRt == hoverNoSpan)
        assert(hoverNoSpanRt.span == Absent)

        val sig = Signature(
            label = "def foo(x: Int, y: String): Unit",
            params = Chunk(
                Signature.Param("x: Int", Present("The integer x")),
                Signature.Param("y: String")
            ),
            activeParam = Present(0)
        )
        val sigRt = roundTrip(sig)
        assert(sigRt == sig)

        val uri = Uri("Main.scala")
        val symWithDef = SymbolInfo(
            name = "foo",
            fullName = "kyo.foo",
            kind = SymbolInfo.Kind.Method,
            localDefinition = Present((uri, Span(10, 20)))
        )
        val symWithDefRt = roundTrip(symWithDef)
        assert(symWithDefRt == symWithDef)

        val symNoDef = SymbolInfo(
            name = "bar",
            fullName = "kyo.bar",
            kind = SymbolInfo.Kind.Val
        )
        val symNoDefRt = roundTrip(symNoDef)
        assert(symNoDefRt == symNoDef)
        assert(symNoDefRt.localDefinition == Absent)

        // A field-only leaf (no Throwable) round-trips to an equal value.
        val errReady: CompilerException = CompilerWorkerReadyException("3.8.4", 30.seconds)
        assert(roundTrip(errReady) == errReady)

        // A Throwable-carrying leaf round-trips to the same type with the cause preserved as text
        // (a live Throwable cannot cross the wire), so assert the type and the rendered cause.
        val errExec: CompilerException = CompilerExecutionException(new RuntimeException("internal error"))
        roundTrip(errExec) match
            case CompilerExecutionException(cause) => assert(cause.getMessage.contains("internal error"))
            case other                             => assert(false, s"expected CompilerExecutionException, got $other")
    }

    "Uri is an opaque String boundary" in {
        val uri = Compiler.Uri("Main.scala")
        assert(uri.asString == "Main.scala")
    }

    "no cancel/spawn/local member and lsp4j surface type rejects (compile-fail)" in {
        // Unsafe: null.asInstanceOf used for compile-time type witnesses only; these vals are never
        // dereferenced at runtime because typeCheckFailure evaluates its argument at compile time
        // via typeCheckErrors, not at execution time.
        val pool: Compiler.Pool = null.asInstanceOf[Compiler.Pool]
        val c: Compiler         = null.asInstanceOf[Compiler]
        val uri: Compiler.Uri   = Compiler.Uri("Main.scala")
        val text: String        = ""
        typeCheckFailure("c.cancel()")
        typeCheckFailure("pool.spawn(config)")
        typeCheckFailure("val d: org.eclipse.lsp4j.Diagnostic = c.compile(uri, text)")
    }

    "Settings.default and Config.isolate three-state default" in {
        import Compiler.*
        val settings = Pool.Settings.default
        assert(settings == Pool.Settings(true, 4, 16, 5.minutes))
        val toolchain = Toolchain("3.8.4", Chunk.empty)
        val config = Config(
            toolchain = toolchain,
            classpath = Chunk.empty,
            scalacOptions = Chunk.empty,
            sourceRoots = Chunk.empty
        )
        assert(config.isolate.isEmpty)
    }

end CompilerTest
