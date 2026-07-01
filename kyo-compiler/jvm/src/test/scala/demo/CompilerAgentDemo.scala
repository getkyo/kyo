package demo

import kyo.*
import kyo.Compiler
import kyo.Compiler.Diagnostic
import kyo.Compiler.Uri

/** A self-contained, runnable demo of an LLM coding assistant that can actually compile its own Scala.
  *
  * It wires kyo-ai (the LLM) to kyo-compiler (the real Scala 3 presentation compiler) through two tools
  * the model can call:
  *
  *   - `compile_scala`: hands a Scala 3 snippet to a warm in-process [[Compiler]] and returns the
  *     compiler's own [[Compiler.Diagnostic]] values directly, so the model reads the real typechecker's
  *     verdict (severity, span, message) rather than a re-encoded summary. An empty result means clean.
  *   - `ask_user`: prints a question to the console and reads the human's reply, returned as a plain
  *     `String`, so the model can gather requirements and confirm direction.
  *
  * The tool result types are kyo-compiler's own (`Chunk[Compiler.Diagnostic]`) and a bare `String`;
  * `Schema` derives for them on demand, so no wrapper result types are introduced. The two tool INPUTS
  * are one-field case classes because LLM tool-calling requires each tool's parameter schema to be a JSON
  * object; they are the tool's typed parameters, not indirection over the compiler types.
  *
  * The agent loop is kyo-ai's: `AI.gen[String]` posts the system prompt, the model decides to call a tool,
  * the eval loop runs it and feeds the typed result back, and it repeats until the model returns a final
  * answer. The intended arc is: ask the user what to build, write code, compile it, read the diagnostics,
  * fix them, recompile until clean, then return the working code.
  *
  * The compiler is in-process on purpose: `scalaVersion` equals the running compiler version, so the
  * pool's version-match rule plus `isolate = false` route the handle to the same-JVM backend, driving the
  * REAL `scala.meta.pc` (impl `dotty.tools.pc`) with the stdlib jars located on the test classpath.
  *
  * Run (needs a provider key; the present key selects the provider):
  *   `ANTHROPIC_API_KEY=... sbt 'kyo-compilerJVM/Test/runMain demo.CompilerAgentDemo'`
  *
  * Demonstrates: Tool.init, AI.enable, AI.gen, LLM.run (kyo-ai) driving Compiler.Pool.init,
  * Pool.compiler, Compiler.compile returning Compiler.Diagnostic (kyo-compiler), with Console for
  * human interaction.
  */
object CompilerAgentDemo extends KyoApp:

    /** The running compiler version. Must equal `build.sbt` `scala3Version` so the version-matched,
      * `isolate = false` config routes to the in-process backend (no forked worker provisioned here).
      */
    private val scalaVersion: String = "3.8.4"

    /** The model's compile request. A one-field object because LLM tool-calling requires the parameter
      * schema to be a JSON object; the result reuses [[Compiler.Diagnostic]] directly.
      */
    case class CompileRequest(code: String) derives Schema

    /** The model's question to the human. Same one-field-object reason as [[CompileRequest]]; the reply
      * is a plain `String`.
      */
    case class UserQuestion(question: String) derives Schema

    private val systemPrompt =
        """You are a Scala 3 coding assistant with two tools.
          |
          |- ask_user: ask the human a question; returns their reply.
          |- compile_scala: compile a self-contained Scala 3 snippet; returns the compiler's diagnostics,
          |  each with a severity (Error/Warning/Info/Hint), a span, and a message. An EMPTY list means it
          |  compiled cleanly.
          |
          |Do this:
          |1. Call ask_user to find out what small Scala 3 program they want.
          |2. Write the code and call compile_scala.
          |3. If any diagnostic has severity Error, fix it and call compile_scala again. Repeat until no
          |   Error diagnostics remain. Never claim success without a clean compile_scala result.
          |4. When it compiles cleanly, return the final working code with a one-line summary.""".stripMargin

    run {
        val program =
            for
                pool     <- Compiler.Pool.init(Compiler.Pool.Settings(isolate = false))
                compiler <- pool.compiler(config)
                answer   <- agent(compiler)
            yield answer
        Abort.run(program).map {
            case Result.Success(answer) => Console.printLine(s"\n[agent] $answer")
            case Result.Failure(err)    => Console.printLineErr(s"\n[FAIL] $err")
            case Result.Panic(ex)       => Console.printLineErr(s"\n[PANIC] $ex")
        }
    }

    /** The two tools over the warm compiler plus the agent loop. `compile_scala` exposes
      * `Chunk[Compiler.Diagnostic]` and `ask_user` a plain reply `String`; a compiler failure (distinct
      * from a diagnostic) surfaces to the model as a tool error via kyo-ai's tool-dispatch boundary.
      */
    private def agent(compiler: Compiler)(using Frame) =
        val compileScala =
            Tool.init[CompileRequest](
                "compile_scala",
                "Compile a self-contained Scala 3 snippet; returns the compiler diagnostics (empty list = compiled cleanly)"
            )(req => compiler.compile(Uri("Main.scala"), req.code))

        val askUser =
            Tool.init[UserQuestion]("ask_user", "Ask the human user a question and return their typed reply") { q =>
                Console.printLine(s"\n[agent] ${q.question}")
                    .andThen(Console.printLine("[you]"))
                    .andThen(Console.readLine)
            }

        LLM.run(AI.enable(compileScala, askUser)(AI.gen[String](systemPrompt)))
    end agent

    /** The in-process, version-matched build config: the version-matched toolchain plus the stdlib on
      * both the pc classpath and the typecheck classpath (located on the test classpath).
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

    /** Locates the first jar on `java.class.path` whose filename contains `fragment`. */
    private def findJar(fragment: String): Path =
        java.lang.System.getProperty("java.class.path", "")
            .split(Path.pathSeparator.charAt(0))
            .find(_.contains(fragment)) match
            case Some(p) => Path(p)
            case None    => throw new RuntimeException(s"no jar matching '$fragment' on java.class.path")

end CompilerAgentDemo
