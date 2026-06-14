package kyo.ffi.codegen

import java.io.File
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kyo.ffi.codegen.emitters.EmitterFixtures.*
import kyo.ffi.codegen.emitters.JvmEmitter
import kyo.ffi.codegen.model.*

/** Compile-then-load round-trip test for the JVM emitter.
  *
  * Text-shape tests (golden-file comparisons, emitter unit tests) confirm the emitter's output
  * *looks* right. This test confirms the emitted source actually *compiles*, *class-loads*, and *runs* against a real C symbol, so a
  * broken emission (e.g. missing import, mistyped identifier, wrong Panama layout) is caught here instead of surfacing only in a downstream
  * user build.
  *
  * Pipeline per scenario:
  *   1. Compile `round_trip_add.c` into a platform-native shared library via `cc`.
  *   2. Emit a Scala binding trait + companion + `JvmEmitter`-generated impl into a scratch dir.
  *   3. Invoke `dotty.tools.dotc.Main.process` in-process to compile the sources against the test-classpath system property (set by
  *      build.sbt for `kyo-ffi-codegen`).
  *   4. Class-load the emitted impl via `URLClassLoader`, instantiate, and reflectively invoke `roundTripAdd(2, 3)`, asserting the result
  *      is `5`.
  *
  * Cleanup: the scratch dir is removed by a JVM shutdown hook, the `-Dkyo.ffi.<id>.path` sys-prop is cleared, and each URLClassLoader is closed.
  */
class CompileLoadRoundTripTest extends kyo.test.Test[Any]:

    // Touches process-global state (global stderr/system property, or the shared CallbackRegistry pool/hooks) and so
    // must run alone: under the default parallel leaf execution a sibling leaf observes or mutates the same global.
    override def config = super.config.sequential

    // -------------------------------------------------------------------------
    // Test configuration (driven by build.sbt sys-props)
    // -------------------------------------------------------------------------

    private val testClasspathProp = "kyo.ffi.codegen.test.classpath"

    private def testClasspath: String =
        sys.props.get(testClasspathProp) match
            case Some(v) => v
            case None    => sys.props("java.class.path")

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    private val libraryId       = "kyo_round_trip"
    private val pkgName         = "kyo.ffi.codegen.roundtrip"
    private val traitSimpleName = "RoundTripBindings"
    private val traitFqcn       = s"$pkgName.$traitSimpleName"
    private val implSimpleName  = s"${traitSimpleName}Impl"
    private val implFqcn        = s"$pkgName.$implSimpleName"
    private val pathSysProp     = s"kyo.ffi.$libraryId.path"

    // -------------------------------------------------------------------------
    // Scratch state: set up once at suite instantiation (before any leaf runs), with cleanup registered as a JVM
    // shutdown hook. The C library compile is one-time shared setup all three leaves depend on, so it runs eagerly here
    // rather than per-leaf (the kyo-test equivalent of the old beforeAll/afterAll pair).
    // -------------------------------------------------------------------------

    private val scratchDir: Path = Files.createTempDirectory("kyo-ffi-round-trip-").toAbsolutePath
    private val libPath: Path    = compileCLibrary(scratchDir)

    java.lang.System.setProperty(pathSysProp, libPath.toString): Unit
    Runtime.getRuntime.nn.addShutdownHook(new Thread(() =>
        java.lang.System.clearProperty(pathSysProp)
        deleteRecursively(scratchDir)
    ))

    private def deleteRecursively(p: Path): Unit =
        if Files.isDirectory(p) then
            val stream = Files.list(p)
            try stream.forEach(child => deleteRecursively(child))
            finally stream.close()
        end if
        try Files.deleteIfExists(p): Unit
        catch case _: Throwable => ()
    end deleteRecursively

    // -------------------------------------------------------------------------
    // C compile helper
    // -------------------------------------------------------------------------

    /** Compile `round_trip_add.c` into a shared library under `baseDir`. Returns the absolute path of the produced library. Uses `cc` via
      * ProcessBuilder so we don't drag in the sbt-scoped `CCompiler` utility from `kyo-ffi-plugin`.
      */
    private def compileCLibrary(baseDir: Path): Path =
        val cSource = locateCSource("c/round_trip_add.c")
        val osName  = java.lang.System.getProperty("os.name").toLowerCase
        val (ext, sharedFlag) =
            if osName.contains("mac") then ("dylib", "-dynamiclib")
            else ("so", "-shared")
        val outLib = baseDir.resolve(s"lib$libraryId.$ext")
        val cmd    = List("cc", "-O2", "-fPIC", sharedFlag, "-o", outLib.toString, cSource.toString)
        val pb     = new ProcessBuilder(cmd*).redirectErrorStream(true)
        val proc   = pb.start()
        val output = new String(proc.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
        val exit   = proc.waitFor()
        if exit != 0 then
            // Setup failure (runs at suite instantiation, before any leaf scope exists), so surface it as a thrown
            // exception rather than the leaf-scoped `fail`.
            throw new RuntimeException(s"C compile failed (exit=$exit). Command: ${cmd.mkString(" ")}\nOutput:\n$output")
        end if
        outLib
    end compileCLibrary

    /** Resolve a file under `src/test/resources` by probing upward from cwd. Mirrors the pattern used in `EmitterGoldenTest`.
      */
    private def locateCSource(relativePath: String): Path =
        val cwd = Paths.get("").toAbsolutePath
        // The codegen module lives at kyo-ffi/codegen (see EmitterGoldenTest for the same anchor).
        val marker = Paths.get("kyo-ffi", "codegen")
        val base =
            if cwd.endsWith(marker) then cwd
            else cwd.resolve(marker)
        val resolved = base.resolve("src/test/resources").resolve(relativePath)
        if !Files.exists(resolved) then
            // Setup failure (runs at suite instantiation, before any leaf scope exists), so surface it as a thrown
            // exception rather than the leaf-scoped `fail`.
            throw new RuntimeException(s"C source not found at $resolved (cwd=$cwd)")
        end if
        resolved
    end locateCSource

    // -------------------------------------------------------------------------
    // Scala source emission (trait + companion + JvmEmitter impl)
    // -------------------------------------------------------------------------

    private def emitBindingTrait(): String =
        // Every binding method takes a trailing `(using AllowUnsafe)`: the FFI binding layer is the unsafe tier, and
        // the emitted impl's override must match. The trait declares the same `(using AllowUnsafe)` the generated impl
        // emits so the round-trip compile validates the override.
        s"""package $pkgName
           |
           |import kyo.AllowUnsafe
           |import kyo.ffi.Ffi
           |
           |trait $traitSimpleName extends Ffi:
           |    def roundTripAdd(a: Int, b: Int)(using AllowUnsafe): Int
           |end $traitSimpleName
           |
           |object $traitSimpleName extends Ffi.Config(library = "$libraryId")
           |""".stripMargin

    private def emitImplSource(): String =
        val spec = mkTrait(
            simpleName = traitSimpleName,
            library = libraryId,
            packageName = pkgName,
            methods = List(
                mkMethod(
                    "roundTripAdd",
                    "round_trip_add",
                    List(ParamSpec("a", TypeRef.IntT), ParamSpec("b", TypeRef.IntT)),
                    ReturnShape.Primitive(TypeRef.IntT)
                )
            )
        )
        JvmEmitter.emit(spec)
    end emitImplSource

    // -------------------------------------------------------------------------
    // In-process Scala 3 compile
    // -------------------------------------------------------------------------

    /** Invoke `dotty.tools.dotc.Main.process` on the given source files with the test classpath. Returns `true` on success (no reported
      * errors), `false` otherwise.
      */
    private def compileInProcess(sources: Seq[Path], outDir: Path): Boolean =
        Files.createDirectories(outDir)
        val args = Array(
            "-classpath",
            testClasspath,
            "-d",
            outDir.toAbsolutePath.toString,
            "-nowarn"
        ) ++ sources.map(_.toAbsolutePath.toString)
        val reporter = dotty.tools.dotc.Main.process(args)
        !reporter.hasErrors
    end compileInProcess

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    "emit + compile + load + call: round_trip_add" in {
        val workDir  = Files.createTempDirectory(scratchDir, "ok-")
        val srcDir   = Files.createDirectories(workDir.resolve("src"))
        val outDir   = Files.createDirectories(workDir.resolve("classes"))
        val traitSrc = srcDir.resolve(s"$traitSimpleName.scala")
        val implSrc  = srcDir.resolve(s"$implSimpleName.scala")
        Files.writeString(traitSrc, emitBindingTrait())
        Files.writeString(implSrc, emitImplSource())

        val ok = compileInProcess(Seq(traitSrc, implSrc), outDir)
        assert(ok == true, s"compilation failed; sources at $srcDir")

        val parentCl = getClass.getClassLoader
        val cl       = new URLClassLoader(Array(outDir.toUri.toURL), parentCl)
        try
            val implClass = cl.loadClass(implFqcn)
            val instance  = implClass.getDeclaredConstructor().newInstance()
            // The binding method takes a trailing `(using AllowUnsafe)`, so the JVM signature carries a third
            // `kyo.AllowUnsafe` parameter. Pass the process-wide `AllowUnsafe.embrace.danger` evidence for the call.
            val allowUnsafeClass = cl.loadClass("kyo.AllowUnsafe")
            val method           = implClass.getMethod("roundTripAdd", classOf[Int], classOf[Int], allowUnsafeClass)
            val result =
                method.invoke(instance, Integer.valueOf(2), Integer.valueOf(3), kyo.AllowUnsafe.embrace.danger)
                    .asInstanceOf[java.lang.Integer]
            assert(result.intValue() == 5)
        finally cl.close()
        end try
    }

    "emits, compiles, loads, and calls a binding that sets all four Config fields" in {
        val allFieldsTraitName = "AllFieldsRoundTrip"
        val allFieldsImplName  = s"${allFieldsTraitName}Impl"
        val allFieldsFqcn      = s"$pkgName.$allFieldsImplName"
        val workDir            = Files.createTempDirectory(scratchDir, "all-fields-")
        val srcDir             = Files.createDirectories(workDir.resolve("src"))
        val outDir             = Files.createDirectories(workDir.resolve("classes"))
        val traitSrc           = srcDir.resolve(s"$allFieldsTraitName.scala")
        val implSrc            = srcDir.resolve(s"${allFieldsTraitName}Impl.scala")

        val traitSource =
            s"""package $pkgName
               |
               |import kyo.AllowUnsafe
               |import kyo.Chunk
               |import kyo.Maybe.Present
               |import kyo.ffi.Ffi
               |
               |trait $allFieldsTraitName extends Ffi:
               |    def roundTripAdd(a: Int, b: Int)(using AllowUnsafe): Int
               |end $allFieldsTraitName
               |
               |object $allFieldsTraitName extends Ffi.Config(
               |    library = "$libraryId",
               |    scratchSize = Present(96 * 1024),
               |    headers = Chunk("stdint.h"),
               |    symbols = Map("roundTripAdd" -> "round_trip_add"),
               |    packedStructs = Set.empty
               |)
               |""".stripMargin

        val spec = mkTrait(
            simpleName = allFieldsTraitName,
            library = libraryId,
            packageName = pkgName,
            methods = List(
                mkMethod(
                    "roundTripAdd",
                    "round_trip_add",
                    List(ParamSpec("a", TypeRef.IntT), ParamSpec("b", TypeRef.IntT)),
                    ReturnShape.Primitive(TypeRef.IntT)
                )
            )
        )
        val implSource = JvmEmitter.emit(spec)

        Files.writeString(traitSrc, traitSource)
        Files.writeString(implSrc, implSource)

        val ok = compileInProcess(Seq(traitSrc, implSrc), outDir)
        assert(ok == true, s"compilation of all-four-fields binding failed; sources at $srcDir")

        val parentCl = getClass.getClassLoader
        val cl       = new URLClassLoader(Array(outDir.toUri.toURL), parentCl)
        try
            val implClass        = cl.loadClass(allFieldsFqcn)
            val instance         = implClass.getDeclaredConstructor().newInstance()
            val allowUnsafeClass = cl.loadClass("kyo.AllowUnsafe")
            val method           = implClass.getMethod("roundTripAdd", classOf[Int], classOf[Int], allowUnsafeClass)
            val result =
                method.invoke(instance, Integer.valueOf(2), Integer.valueOf(3), kyo.AllowUnsafe.embrace.danger)
                    .asInstanceOf[java.lang.Integer]
            assert(result.intValue() == 5)
        finally cl.close()
        end try
    }

    "negative: a deliberately broken emission fails compilation" in {
        val workDir  = Files.createTempDirectory(scratchDir, "bad-")
        val srcDir   = Files.createDirectories(workDir.resolve("src"))
        val outDir   = Files.createDirectories(workDir.resolve("classes"))
        val traitSrc = srcDir.resolve(s"$traitSimpleName.scala")
        val implSrc  = srcDir.resolve(s"$implSimpleName.scala")
        Files.writeString(traitSrc, emitBindingTrait())
        // Inject a typo into the emitter output, `MethodHandle` is a real import from
        // java.lang.invoke that appears in the emitted body as the method-handle val's
        // type. Renaming every occurrence to `MethodHandleX` must make compilation fail;
        // that's the whole point of this test.
        val emitted = emitImplSource()
        val broken  = emitted.replace("MethodHandle", "MethodHandleX")
        assert(broken != emitted, "broken source did not differ from original, the mutation missed its target")
        Files.writeString(implSrc, broken)

        val ok = compileInProcess(Seq(traitSrc, implSrc), outDir)
        assert(ok == false)
    }

end CompileLoadRoundTripTest
