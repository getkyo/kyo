package kyo.ffi.codegen.emitters

/** Minimal parse-only probe: runs the Scala 3 parser on an in-memory source and reports whether any fatal errors were raised.
  *
  * This is a syntax check only, it does not resolve symbols. The test-only dependency `scala3-compiler` is declared in build.sbt for
  * exactly this use.
  */
private object ScalaParseProbe:

    def parses(source: String): (Boolean, String) =
        // Use a throwaway Context with a no-op reporter and run the parser.
        import dotty.tools.dotc.*
        import dotty.tools.dotc.core.Contexts.*
        import dotty.tools.dotc.parsing.Parsers
        import dotty.tools.dotc.reporting.StoreReporter
        import dotty.tools.dotc.util.*

        val reporter = new StoreReporter(null, fromTyperState = false)
        val base     = new ContextBase
        given Context = base.initialCtx.fresh
            .setSetting(base.settings.color, "never")
            .setReporter(reporter)
        val nameLit = "<jvm-emitter-test>.scala"
        val src     = SourceFile.virtual(nameLit, source)
        val parser  = new Parsers.Parser(src)
        try
            parser.parse()
        catch
            case _: Throwable => ()
        end try

        val errorMsgs = reporter.allErrors.map(_.message).mkString("\n")
        (!reporter.hasErrors, errorMsgs)
    end parses

end ScalaParseProbe
