package kyo.ffi.codegen

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/** End-to-end integration test for [[FfiGenerator]].
  *
  * Relies on two system properties set by the sbt build (`kyo-ffi-codegen` project):
  *   - `kyo.ffi.codegen.test.classes`, absolute path to `Test / classDirectory`.
  *   - `kyo.ffi.codegen.test.classpath`, `File.pathSeparator`-joined `Test / fullClasspath`.
  *
  * The fixture traits under `kyo.ffi.codegen.fixtures` compile as part of `kyo-ffi-codegen/Test`; this spec feeds the resulting `.tasty`
  * files back into the generator and asserts on extraction, emission, and warning behavior.
  */
class FfiGeneratorTest extends kyo.test.Test[Any]:

    private val testClassesProp   = "kyo.ffi.codegen.test.classes"
    private val testClasspathProp = "kyo.ffi.codegen.test.classpath"

    private def testClassesDir(using kyo.test.AssertScope): Path =
        val v = sys.props.get(testClassesProp).getOrElse(
            fail(s"system property `$testClassesProp` is not set, check build.sbt wiring for kyo-ffi-codegen")
        )
        Path.of(v)
    end testClassesDir

    private def testClasspath: List[String] =
        sys.props.get(testClasspathProp) match
            case Some(v) => v.split(File.pathSeparator).toList
            case None    => sys.props("java.class.path").split(File.pathSeparator).toList

    private def findTastyFiles(root: Path, nameFilter: String => Boolean): List[String] =
        if !Files.exists(root) then Nil
        else
            val stream = Files.walk(root)
            try
                stream
                    .iterator()
                    .asScala
                    .filter(p => Files.isRegularFile(p))
                    .map(_.toString)
                    .filter(_.endsWith(".tasty"))
                    .filter(p => nameFilter(Path.of(p).getFileName.toString))
                    .toList
            finally stream.close()
            end try
    end findTastyFiles

    "FfiGenerator.generate" - {

        "extracts FixtureBindings and emits FixtureBindingsImpl.scala" in {
            val tasty = findTastyFiles(testClassesDir, _ == "FixtureBindings.tasty")
            assert(tasty.nonEmpty)
            val out = Files.createTempDirectory("kyo-ffi-gen-test")
            val result = FfiGenerator.generate(
                tastyFiles = tasty,
                classpath = testClasspath,
                outputDir = out,
                platform = FfiGenerator.Platform.JVM
            )
            assert(result.files.nonEmpty)
            assert(result.traits.map(_.simpleName).contains("FixtureBindings"))

            val generated = result.files.find(_.getFileName.toString == "FixtureBindingsImpl.scala")
            assert(generated.isDefined)
            val content = Files.readString(generated.get)
            assert(content.contains("final class FixtureBindingsImpl extends FixtureBindings"))
            assert(content.contains("""AbiCheck.verify(1, "kyo.ffi.codegen.fixtures.FixtureBindings")"""))
            assert(content.contains("kyo_fixture"))
        }

        "extracts SizedScratchBindings and emits Scratch.currentFor with the Config.scratchSize literal" in {
            val tasty = findTastyFiles(testClassesDir, _ == "SizedScratchBindings.tasty")
            assert(tasty.nonEmpty)
            val out    = Files.createTempDirectory("kyo-ffi-gen-sized")
            val result = FfiGenerator.generate(tasty, testClasspath, out, FfiGenerator.Platform.JVM)
            val spec   = result.traits.find(_.simpleName == "SizedScratchBindings").getOrElse(fail("SizedScratchBindings not extracted"))
            assert(spec.companion.flatMap(_.scratchSize) == Some(131072))
            val generated = result.files.find(_.getFileName.toString == "SizedScratchBindingsImpl.scala").getOrElse(
                fail("SizedScratchBindingsImpl.scala not generated")
            )
            val content = Files.readString(generated)
            assert(content.contains("""Scratch.currentFor("kyo.ffi.codegen.fixtures.SizedScratchBindings", 131072L)"""))
        }

        "extracts a symbols Map that mixes arrow and tuple entries" in {
            val tasty = findTastyFiles(testClassesDir, _ == "MixedSymbolFormsBindings.tasty")
            assert(tasty.nonEmpty)
            val out    = Files.createTempDirectory("kyo-ffi-gen-mixed-syms")
            val result = FfiGenerator.generate(tasty, testClasspath, out, FfiGenerator.Platform.JVM)
            val spec =
                result.traits.find(_.simpleName == "MixedSymbolFormsBindings").getOrElse(
                    fail("MixedSymbolFormsBindings not extracted")
                )
            assert(spec.companion.map(_.symbols) == Some(Map("a" -> "b", "c" -> "d")))
        }

        "extracts a Config that sets all four fields with Maybe, Chunk, Map, and Set literals" in {
            val tasty = findTastyFiles(testClassesDir, _ == "AllConfigFieldsBindings.tasty")
            assert(tasty.nonEmpty)
            val out    = Files.createTempDirectory("kyo-ffi-gen-all-fields")
            val result = FfiGenerator.generate(tasty, testClasspath, out, FfiGenerator.Platform.JVM)
            val spec =
                result.traits.find(_.simpleName == "AllConfigFieldsBindings").getOrElse(fail("AllConfigFieldsBindings not extracted"))
            assert(spec.companion.flatMap(_.scratchSize) == Some(98304))
            assert(spec.companion.map(_.headers) == Some(Seq("stdint.h")))
            assert(spec.companion.map(_.symbols) == Some(Map("echo" -> "kyo_echo")))
            assert(spec.companion.map(_.packedStructs) == Some(Set("Packed")))
        }

        "is byte-stable for identical input" in {
            val tasty = findTastyFiles(testClassesDir, _ == "FixtureBindings.tasty")
            assert(tasty.nonEmpty)
            val out1 = Files.createTempDirectory("kyo-ffi-gen-stable-a")
            val out2 = Files.createTempDirectory("kyo-ffi-gen-stable-b")
            val r1   = FfiGenerator.generate(tasty, testClasspath, out1, FfiGenerator.Platform.JVM)
            val r2   = FfiGenerator.generate(tasty, testClasspath, out2, FfiGenerator.Platform.JVM)
            assert(r1.files.size == r2.files.size)
            val f1 = r1.files.find(_.getFileName.toString == "FixtureBindingsImpl.scala").get
            val f2 = r2.files.find(_.getFileName.toString == "FixtureBindingsImpl.scala").get
            assert(Files.readAllBytes(f1).toSeq == Files.readAllBytes(f2).toSeq)
        }

        "places the emitted file under the trait's package directory" in {
            val tasty   = findTastyFiles(testClassesDir, _ == "FixtureBindings.tasty")
            val out     = Files.createTempDirectory("kyo-ffi-gen-pkg")
            val result  = FfiGenerator.generate(tasty, testClasspath, out, FfiGenerator.Platform.JVM)
            val emitted = result.files.find(_.getFileName.toString == "FixtureBindingsImpl.scala").get
            // Expect .../kyo/ffi/codegen/fixtures/FixtureBindingsImpl.scala
            assert(emitted.toString.contains("kyo/ffi/codegen/fixtures/FixtureBindingsImpl.scala".replace('/', File.separatorChar)))
        }

        "emits a warning for blocking-allowlist symbols missing @Ffi.blocking" in {
            val tasty = findTastyFiles(testClassesDir, _ == "BlockingWarningFixture.tasty")
            assert(tasty.nonEmpty)
            val out    = Files.createTempDirectory("kyo-ffi-gen-warn")
            val result = FfiGenerator.generate(tasty, testClasspath, out, FfiGenerator.Platform.JVM)
            assert(result.warnings.nonEmpty)
            val w = result.warnings.mkString("\n")
            assert(w.contains("BlockingWarningFixture.read"))
            assert(w.contains("blocking allowlist"))
        }

        "throws under strictBlocking when a blocking-allowlist symbol is missing @Ffi.blocking" in {
            val tasty = findTastyFiles(testClassesDir, _ == "BlockingWarningFixture.tasty")
            assert(tasty.nonEmpty)
            val out = Files.createTempDirectory("kyo-ffi-gen-strict")
            val cfg = FfiGenerator.Config.default.copy(strictBlocking = true)
            interceptThrown[IllegalStateException] {
                FfiGenerator.generate(tasty, testClasspath, out, FfiGenerator.Platform.JVM, cfg)
            }
        }

        "extracts BorrowedReturnBindings and produces BorrowedString / BorrowedBuffer return shapes" in {
            import kyo.ffi.codegen.model.*
            val tasty = findTastyFiles(testClassesDir, _ == "BorrowedReturnBindings.tasty")
            assert(tasty.nonEmpty)
            val out    = Files.createTempDirectory("kyo-ffi-gen-borrowed")
            val result = FfiGenerator.generate(tasty, testClasspath, out, FfiGenerator.Platform.JVM)
            val spec = result.traits.find(_.simpleName == "BorrowedReturnBindings").getOrElse(
                fail("BorrowedReturnBindings not extracted")
            )
            val getenvMethod = spec.methods.find(_.scalaName == "getenvLike").getOrElse(fail("getenvLike method not found"))
            assert(getenvMethod.returnShape.isInstanceOf[ReturnShape.BorrowedString])

            val mallocMethod = spec.methods.find(_.scalaName == "mallocChunk").getOrElse(fail("mallocChunk method not found"))
            assert(mallocMethod.returnShape == ReturnShape.BorrowedBuffer(TypeRef.ByteT, "n"))
        }

        "extracts UnionBindings with `Int | Float` union parameter" in {
            import kyo.ffi.codegen.model.*
            val tasty = findTastyFiles(testClassesDir, _ == "UnionBindings.tasty")
            assert(tasty.nonEmpty)
            val out    = Files.createTempDirectory("kyo-ffi-gen-union")
            val result = FfiGenerator.generate(tasty, testClasspath, out, FfiGenerator.Platform.JVM)
            val spec = result.traits.find(_.simpleName == "UnionBindings").getOrElse(
                fail("UnionBindings not extracted")
            )
            val method = spec.methods.find(_.scalaName == "roundtrip").getOrElse(
                fail("roundtrip method not found")
            )
            assert(method.params.head.tpe == TypeRef.UnionT(List(TypeRef.IntT, TypeRef.FloatT)))
        }

        "emits runtime type match for union parameter on JVM" in {
            val tasty = findTastyFiles(testClassesDir, _ == "UnionBindings.tasty")
            assert(tasty.nonEmpty)
            val out    = Files.createTempDirectory("kyo-ffi-gen-union-jvm")
            val result = FfiGenerator.generate(tasty, testClasspath, out, FfiGenerator.Platform.JVM)
            val generated = result.files.find(_.getFileName.toString == "UnionBindingsImpl.scala").getOrElse(
                fail("UnionBindingsImpl.scala not emitted")
            )
            val content = Files.readString(generated)
            assert(content.contains("java.lang.Integer"))
            assert(content.contains("java.lang.Float"))
            assert(content.contains("match"))
        }

        "emits runtime type match for union parameter on JS" in {
            val tasty = findTastyFiles(testClassesDir, _ == "UnionBindings.tasty")
            assert(tasty.nonEmpty)
            val out    = Files.createTempDirectory("kyo-ffi-gen-union-js")
            val result = FfiGenerator.generate(tasty, testClasspath, out, FfiGenerator.Platform.JS)
            val generated = result.files.find(_.getFileName.toString == "UnionBindingsImpl.scala").getOrElse(
                fail("UnionBindingsImpl.scala not emitted")
            )
            val content = Files.readString(generated)
            assert(content.contains("java.lang.Integer"))
            assert(content.contains("java.lang.Float"))
        }

        "emits runtime type match for union parameter on Native" in {
            val tasty = findTastyFiles(testClassesDir, _ == "UnionBindings.tasty")
            assert(tasty.nonEmpty)
            val out    = Files.createTempDirectory("kyo-ffi-gen-union-native")
            val result = FfiGenerator.generate(tasty, testClasspath, out, FfiGenerator.Platform.Native)
            val generated = result.files.find(_.getFileName.toString == "UnionBindingsImpl.scala").getOrElse(
                fail("UnionBindingsImpl.scala not emitted")
            )
            val content = Files.readString(generated)
            assert(content.contains("java.lang.Integer"))
            assert(content.contains("java.lang.Float"))
        }

        "rejects union type with a `String` variant" in {
            val tasty = findTastyFiles(testClassesDir, _ == "BadUnionStringBindings.tasty")
            assert(tasty.nonEmpty)
            val out = Files.createTempDirectory("kyo-ffi-gen-bad-union-string")
            val ex = intercept[FfiExtractionError] {
                FfiGenerator.generate(tasty, testClasspath, out, FfiGenerator.Platform.JVM)
            }
            val msg = ex.errors.map(_.message).mkString("\n")
            assert(msg.contains("not permitted in a union"))
        }

        "rejects union type as return" in {
            val tasty = findTastyFiles(testClassesDir, _ == "BadUnionReturnBindings.tasty")
            assert(tasty.nonEmpty)
            val out = Files.createTempDirectory("kyo-ffi-gen-bad-union-return")
            val ex = intercept[FfiExtractionError] {
                FfiGenerator.generate(tasty, testClasspath, out, FfiGenerator.Platform.JVM)
            }
            val msg = ex.errors.map(_.message).mkString("\n")
            assert(msg.contains("union type by value"))
            assert(msg.contains("not supported"))
        }

        "extracts VariadicBindings and sets MethodSpec.hasVarargs = true, excluding the varargs param" in {
            import kyo.ffi.codegen.model.*
            val tasty = findTastyFiles(testClassesDir, _ == "VariadicBindings.tasty")
            assert(tasty.nonEmpty)
            val out    = Files.createTempDirectory("kyo-ffi-gen-varargs")
            val result = FfiGenerator.generate(tasty, testClasspath, out, FfiGenerator.Platform.JVM)
            val spec = result.traits.find(_.simpleName == "VariadicBindings").getOrElse(
                fail("VariadicBindings not extracted")
            )
            val m = spec.methods.find(_.scalaName == "variadicSum").getOrElse(fail("variadicSum method not found"))
            assert(m.hasVarargs == true)
            // The varargs `args: Any*` parameter is synthesized by emitters; `params` lists only fixed args.
            assert(m.params.map(_.name) == List("count"))
            assert(m.params.map(_.tpe) == List(TypeRef.IntT))
        }

        "extracts headers from Ffi.Config companion" in {
            val tasty = findTastyFiles(testClassesDir, _ == "HeaderGatedBindings.tasty")
            assert(tasty.nonEmpty)
            val out    = Files.createTempDirectory("kyo-ffi-gen-headers")
            val result = FfiGenerator.generate(tasty, testClasspath, out, FfiGenerator.Platform.JVM)
            val spec = result.traits.find(_.simpleName == "HeaderGatedBindings").getOrElse(
                fail("HeaderGatedBindings not extracted")
            )
            assert(spec.headers == Seq("sys/test_header.h"))
            assert(spec.companion.map(_.headers) == Some(Seq("sys/test_header.h")))
        }

        "returns an empty Result when given no TASTy files" in {
            val out    = Files.createTempDirectory("kyo-ffi-gen-empty")
            val result = FfiGenerator.generate(Nil, testClasspath, out, FfiGenerator.Platform.JVM)
            assert(result.files.isEmpty)
            assert(result.traits.isEmpty)
            assert(result.warnings.isEmpty)
        }

        "headersAvailable does not deadlock when the preprocessed output exceeds the OS pipe buffer" in {
            // Regression for the `cc -E -xc -` probe: the `out`/`err` ProcessIO handlers must drain the process streams. The
            // combined preprocessed text of the POSIX socket header set is far larger than the ~64 KiB pipe buffer, so before
            // the drain fix `cc` blocked writing stdout and `exitValue()` hung forever. We run the probe on a separate thread
            // with a generous timeout: a deadlock fails the test; a normal completion (true on a POSIX host) passes fast.
            val socketHeaders  = Seq("sys/socket.h", "netinet/in.h", "sys/un.h", "fcntl.h", "unistd.h")
            @volatile var done = false
            val worker = new Thread(() =>
                FfiGenerator.headersAvailable(socketHeaders): Unit
                done = true
            )
            worker.setDaemon(true)
            worker.start()
            worker.join(30000)
            assert(done == true)
        }

        "headersAvailable: a vendored header off the system path resolves only when its -I dir is supplied" in {
            // A staged BoringSSL header lives under build/boringssl/staged/<osArch>/include, not on the
            // system include path. The probe must miss it with no -I (so the binding would stub) and
            // find it once the staged include dir is passed (so the binding is emitted as @extern).
            val dir = java.nio.file.Files.createTempDirectory("kyo-ffi-incprobe").toFile
            try
                val pkgDir = new java.io.File(dir, "vendored")
                pkgDir.mkdirs()
                val header = new java.io.File(pkgDir, "only_here.h")
                java.nio.file.Files.writeString(header.toPath, "#define KYO_FFI_PROBE_OK 1\n"): Unit
                val rel = Seq("vendored/only_here.h")
                assert(FfiGenerator.headersAvailable(rel, Nil) == false)
                assert(FfiGenerator.headersAvailable(rel, Seq(dir.getAbsolutePath)) == true)
            finally
                def del(f: java.io.File): Unit =
                    if f.isDirectory then f.listFiles().foreach(del)
                    f.delete(): Unit
                del(dir)
            end try
        }

        // -------------------------------------------------------------------------
        // File-collision handling: two traits that share a simple name
        // but live in different packages must not overwrite each other. Uses
        // `writeSpec` directly with synthetic TraitSpecs because the fixture set
        // only contains one trait per simple name.
        // -------------------------------------------------------------------------

        "writeSpec places traits with the same simple name in different packages into distinct files" in {
            import kyo.ffi.codegen.model.*
            val spec1 = TraitSpec(
                fqcn = "kyo.example.a.Bindings",
                simpleName = "Bindings",
                packageName = "kyo.example.a",
                library = "alib",
                methods = Nil,
                structs = Nil,
                companion = None
            )
            val spec2 = TraitSpec(
                fqcn = "kyo.example.b.Bindings",
                simpleName = "Bindings",
                packageName = "kyo.example.b",
                library = "blib",
                methods = Nil,
                structs = Nil,
                companion = None
            )
            val out = Files.createTempDirectory("kyo-ffi-gen-collision")
            val p1  = FfiGenerator.writeSpec(spec1, out, FfiGenerator.Platform.JVM)
            val p2  = FfiGenerator.writeSpec(spec2, out, FfiGenerator.Platform.JVM)
            assert(!p1.equals(p2))
            assert(p1.toString.contains("kyo/example/a/BindingsImpl.scala".replace('/', File.separatorChar)))
            assert(p2.toString.contains("kyo/example/b/BindingsImpl.scala".replace('/', File.separatorChar)))
            assert(Files.exists(p1) == true)
            assert(Files.exists(p2) == true)
            val c1 = Files.readString(p1)
            val c2 = Files.readString(p2)
            assert(c1.contains("package kyo.example.a"))
            assert(c2.contains("package kyo.example.b"))
            assert(c1.contains("alib"))
            assert(c2.contains("blib"))
        }
        "extracts HandleT from Handle[A] and emits correct impl on JVM" in {
            import kyo.ffi.codegen.model.*
            val tasty = findTastyFiles(
                testClassesDir,
                n => n == "OpaqueBindings.tasty" || n == "FixtureHandle.tasty" || n == "FixtureOtherHandle.tasty"
            )
            assert(tasty.nonEmpty)
            val out    = Files.createTempDirectory("kyo-ffi-gen-handle-jvm")
            val result = FfiGenerator.generate(tasty, testClasspath, out, FfiGenerator.Platform.JVM)
            val spec = result.traits.find(_.simpleName == "OpaqueBindings").getOrElse(
                fail("OpaqueBindings not extracted")
            )

            // Verify method signatures use HandleT.
            val createMethod = spec.methods.find(_.scalaName == "createHandle").getOrElse(fail("createHandle not found"))
            assert(createMethod.returnShape == ReturnShape.HandleReturn("kyo.ffi.codegen.fixtures.FixtureHandle"))

            val nullMethod = spec.methods.find(_.scalaName == "nullHandle").getOrElse(fail("nullHandle not found"))
            assert(nullMethod.returnShape.isInstanceOf[ReturnShape.HandleReturn])
            assert(nullMethod.returnShape.asInstanceOf[ReturnShape.HandleReturn].nullable == true)

            val readMethod = spec.methods.find(_.scalaName == "readHandle").getOrElse(fail("readHandle not found"))
            assert(readMethod.params.head.tpe.isInstanceOf[TypeRef.HandleT])

            // Verify JVM emitted code contains ADDRESS pattern.
            val generated = result.files.find(_.getFileName.toString == "OpaqueBindingsImpl.scala").getOrElse(
                fail("OpaqueBindingsImpl.scala not generated")
            )
            val content = Files.readString(generated)
            assert(content.contains("ADDRESS"))
            assert(content.contains("Ffi.Handle.wrap"))
            assert(content.contains("Ffi.Handle.unwrap"))
            // Bare Handle returns throw FfiNullPointer, nullable returns use Absent/Present
            assert(content.contains("FfiNullPointer"))
            assert(content.contains("kyo.Absent"))
            assert(content.contains("kyo.Present"))
        }

        "extracts HandleT and emits correct impl on Native" in {
            val tasty = findTastyFiles(
                testClassesDir,
                n => n == "OpaqueBindings.tasty" || n == "FixtureHandle.tasty" || n == "FixtureOtherHandle.tasty"
            )
            assert(tasty.nonEmpty)
            val out    = Files.createTempDirectory("kyo-ffi-gen-handle-native")
            val result = FfiGenerator.generate(tasty, testClasspath, out, FfiGenerator.Platform.Native)
            val generated = result.files.find(_.getFileName.toString == "OpaqueBindingsImpl.scala").getOrElse(
                fail("OpaqueBindingsImpl.scala not generated")
            )
            val content = Files.readString(generated)
            assert(content.contains("Ptr[Byte]"))
            assert(content.contains("Ffi.Handle.wrap"))
            assert(content.contains("NativePtr"))
        }

        "extracts EnumT from structurally-detected Scala 3 enum and emits correct impl on JVM" in {
            import kyo.ffi.codegen.model.*
            val tasty = findTastyFiles(
                testClassesDir,
                n => n == "EnumBindings.tasty" || n == "FixtureColor.tasty"
            )
            assert(tasty.nonEmpty)
            val out    = Files.createTempDirectory("kyo-ffi-gen-enum-jvm")
            val result = FfiGenerator.generate(tasty, testClasspath, out, FfiGenerator.Platform.JVM)
            val spec = result.traits.find(_.simpleName == "EnumBindings").getOrElse(
                fail("EnumBindings not extracted")
            )

            // Verify enums were accumulated.
            assert(spec.enums.nonEmpty)
            assert(spec.enums.map(_.simpleName).contains("FixtureColor"))

            // Verify method signatures use EnumT.
            val getColorMethod = spec.methods.find(_.scalaName == "getColor").getOrElse(fail("getColor not found"))
            assert(getColorMethod.returnShape.isInstanceOf[ReturnShape.EnumReturn])

            val colorValueMethod = spec.methods.find(_.scalaName == "colorValue").getOrElse(fail("colorValue not found"))
            assert(colorValueMethod.params.head.tpe.isInstanceOf[TypeRef.EnumT])

            // Verify JVM emitted code contains .value and fromInt patterns.
            val generated = result.files.find(_.getFileName.toString == "EnumBindingsImpl.scala").getOrElse(
                fail("EnumBindingsImpl.scala not generated")
            )
            val content = Files.readString(generated)
            assert(content.contains(".value"))
            assert(content.contains(".fromInt("))
            assert(content.contains("JAVA_INT"))
        }

        "extracts EnumT and emits correct impl on Native" in {
            val tasty = findTastyFiles(
                testClassesDir,
                n => n == "EnumBindings.tasty" || n == "FixtureColor.tasty"
            )
            assert(tasty.nonEmpty)
            val out    = Files.createTempDirectory("kyo-ffi-gen-enum-native")
            val result = FfiGenerator.generate(tasty, testClasspath, out, FfiGenerator.Platform.Native)
            val generated = result.files.find(_.getFileName.toString == "EnumBindingsImpl.scala").getOrElse(
                fail("EnumBindingsImpl.scala not generated")
            )
            val content = Files.readString(generated)
            assert(content.contains(".value"))
            assert(content.contains(".fromInt("))
            assert(content.contains("CInt"))
        }

        "extracts EnumT and emits correct impl on JS" in {
            val tasty = findTastyFiles(
                testClassesDir,
                n => n == "EnumBindings.tasty" || n == "FixtureColor.tasty"
            )
            assert(tasty.nonEmpty)
            val out    = Files.createTempDirectory("kyo-ffi-gen-enum-js")
            val result = FfiGenerator.generate(tasty, testClasspath, out, FfiGenerator.Platform.JS)
            val generated = result.files.find(_.getFileName.toString == "EnumBindingsImpl.scala").getOrElse(
                fail("EnumBindingsImpl.scala not generated")
            )
            val content = Files.readString(generated)
            assert(content.contains(".value"))
            assert(content.contains(".fromInt("))
            assert(content.contains("\"int\""))
        }

        "extracts HandleT and emits correct impl on JS" in {
            val tasty = findTastyFiles(
                testClassesDir,
                n => n == "OpaqueBindings.tasty" || n == "FixtureHandle.tasty" || n == "FixtureOtherHandle.tasty"
            )
            assert(tasty.nonEmpty)
            val out    = Files.createTempDirectory("kyo-ffi-gen-handle-js")
            val result = FfiGenerator.generate(tasty, testClasspath, out, FfiGenerator.Platform.JS)
            val generated = result.files.find(_.getFileName.toString == "OpaqueBindingsImpl.scala").getOrElse(
                fail("OpaqueBindingsImpl.scala not generated")
            )
            val content = Files.readString(generated)
            assert(content.contains("\"void*\""))
            assert(content.contains("Ffi.Handle.wrap"))
            // Bare Handle returns throw FfiNullPointer, nullable returns use Absent/Present
            assert(content.contains("FfiNullPointer"))
            assert(content.contains("kyo.Absent"))
            assert(content.contains("kyo.Present"))
        }

        "emits struct with pointer fields on JVM (handle + string + fnptr)" in {
            val tasty = findTastyFiles(
                testClassesDir,
                n =>
                    n == "StructPtrFieldBindings.tasty" || n == "StructWithPtrFields.tasty" ||
                        n == "FixtureHandle.tasty" || n == "FixtureOtherHandle.tasty"
            )
            assert(tasty.nonEmpty)
            val out    = Files.createTempDirectory("kyo-ffi-gen-struct-ptr-jvm")
            val result = FfiGenerator.generate(tasty, testClasspath, out, FfiGenerator.Platform.JVM)
            val generated = result.files.find(_.getFileName.toString == "StructPtrFieldBindingsImpl.scala").getOrElse(
                fail("StructPtrFieldBindingsImpl.scala not generated")
            )
            val content = Files.readString(generated)
            // Handle field: ADDRESS layout and Ffi.Handle.unwrap
            assert(content.contains("ADDRESS"))
            assert(content.contains("Ffi.Handle.unwrap"))
            // String field: allocUtf8
            assert(content.contains("allocUtf8"))
            // FnPtr field: UpcallBridge.stub
            assert(content.contains("UpcallBridge.stub"))
        }

        "emits struct with pointer fields on Native (handle + string + fnptr)" in {
            val tasty = findTastyFiles(
                testClassesDir,
                n =>
                    n == "StructPtrFieldBindings.tasty" || n == "StructWithPtrFields.tasty" ||
                        n == "FixtureHandle.tasty" || n == "FixtureOtherHandle.tasty"
            )
            assert(tasty.nonEmpty)
            val out    = Files.createTempDirectory("kyo-ffi-gen-struct-ptr-native")
            val result = FfiGenerator.generate(tasty, testClasspath, out, FfiGenerator.Platform.Native)
            val generated = result.files.find(_.getFileName.toString == "StructPtrFieldBindingsImpl.scala").getOrElse(
                fail("StructPtrFieldBindingsImpl.scala not generated")
            )
            val content = Files.readString(generated)
            // Handle field: NativePtr
            assert(content.contains("NativePtr"))
            // String field: toCString
            assert(content.contains("toCString"))
            // FnPtr field: CFuncPtr
            assert(content.contains("CFuncPtr.toPtr"))
        }

        "emits struct with pointer fields on JS (handle + string + fnptr)" in {
            val tasty = findTastyFiles(
                testClassesDir,
                n =>
                    n == "StructPtrFieldBindings.tasty" || n == "StructWithPtrFields.tasty" ||
                        n == "FixtureHandle.tasty" || n == "FixtureOtherHandle.tasty"
            )
            assert(tasty.nonEmpty)
            val out    = Files.createTempDirectory("kyo-ffi-gen-struct-ptr-js")
            val result = FfiGenerator.generate(tasty, testClasspath, out, FfiGenerator.Platform.JS)
            val generated = result.files.find(_.getFileName.toString == "StructPtrFieldBindingsImpl.scala").getOrElse(
                fail("StructPtrFieldBindingsImpl.scala not generated")
            )
            val content = Files.readString(generated)
            // Struct registration with pointer fields
            assert(content.contains("\"void*\""))
            assert(content.contains("\"string\""))
        }
        // -----------------------------------------------------------------
        // Borrowed[A] return type: positive and negative cases
        // -----------------------------------------------------------------

        "Borrowed[String] emits Ffi.Borrowed.wrap in generated JVM code" in {
            val tasty = findTastyFiles(testClassesDir, _ == "BorrowedReturnBindings.tasty")
            assert(tasty.nonEmpty)
            val out    = Files.createTempDirectory("kyo-ffi-gen-borrowed-wrap-jvm")
            val result = FfiGenerator.generate(tasty, testClasspath, out, FfiGenerator.Platform.JVM)
            val generated = result.files.find(_.getFileName.toString == "BorrowedReturnBindingsImpl.scala").getOrElse(
                fail("BorrowedReturnBindingsImpl.scala not generated")
            )
            val content = Files.readString(generated)
            assert(content.contains("Ffi.Borrowed.wrap("))
            assert(content.contains("Ffi.Borrowed[String]"))
            assert(content.contains("Ffi.Borrowed[Buffer[Byte]]"))
        }

        "Borrowed[String] emits Ffi.Borrowed.wrap in generated Native code" in {
            val tasty = findTastyFiles(testClassesDir, _ == "BorrowedReturnBindings.tasty")
            assert(tasty.nonEmpty)
            val out    = Files.createTempDirectory("kyo-ffi-gen-borrowed-wrap-native")
            val result = FfiGenerator.generate(tasty, testClasspath, out, FfiGenerator.Platform.Native)
            val generated = result.files.find(_.getFileName.toString == "BorrowedReturnBindingsImpl.scala").getOrElse(
                fail("BorrowedReturnBindingsImpl.scala not generated")
            )
            val content = Files.readString(generated)
            assert(content.contains("Ffi.Borrowed.wrap("))
        }

        "Borrowed[String] emits Ffi.Borrowed.wrap in generated JS code" in {
            val tasty = findTastyFiles(testClassesDir, _ == "BorrowedReturnBindings.tasty")
            assert(tasty.nonEmpty)
            val out    = Files.createTempDirectory("kyo-ffi-gen-borrowed-wrap-js")
            val result = FfiGenerator.generate(tasty, testClasspath, out, FfiGenerator.Platform.JS)
            val generated = result.files.find(_.getFileName.toString == "BorrowedReturnBindingsImpl.scala").getOrElse(
                fail("BorrowedReturnBindingsImpl.scala not generated")
            )
            val content = Files.readString(generated)
            assert(content.contains("Ffi.Borrowed.wrap("))
        }

        "rejects Borrowed[Int], only String and Buffer[A] are valid inner types" in {
            val tasty = findTastyFiles(testClassesDir, _ == "BadBorrowedPrimitiveBindings.tasty")
            assert(tasty.nonEmpty)
            val out = Files.createTempDirectory("kyo-ffi-gen-bad-borrowed-prim")
            val ex = intercept[FfiExtractionError] {
                FfiGenerator.generate(tasty, testClasspath, out, FfiGenerator.Platform.JVM)
            }
            val msg = ex.errors.map(_.message).mkString("\n")
            assert(msg.contains("Borrowed"))
            assert(msg.contains("not supported"))
        }

        "rejects Borrowed[Buffer[A]] when no Int/Long parameter for size inference" in {
            val tasty = findTastyFiles(testClassesDir, _ == "BadBorrowedBufferNoSizeBindings.tasty")
            assert(tasty.nonEmpty)
            val out = Files.createTempDirectory("kyo-ffi-gen-bad-borrowed-nosize")
            val ex = intercept[FfiExtractionError] {
                FfiGenerator.generate(tasty, testClasspath, out, FfiGenerator.Platform.JVM)
            }
            val msg = ex.errors.map(_.message).mkString("\n")
            assert(msg.contains("Borrowed[Buffer[...]]"))
            assert(msg.contains("no `Int` or `Long` parameters"))
        }

        "rejects Borrowed[Buffer[A]] when multiple Int/Long parameters make size ambiguous" in {
            val tasty = findTastyFiles(testClassesDir, _ == "BadBorrowedBufferAmbiguousSizeBindings.tasty")
            assert(tasty.nonEmpty)
            val out = Files.createTempDirectory("kyo-ffi-gen-bad-borrowed-ambig")
            val ex = intercept[FfiExtractionError] {
                FfiGenerator.generate(tasty, testClasspath, out, FfiGenerator.Platform.JVM)
            }
            val msg = ex.errors.map(_.message).mkString("\n")
            assert(msg.contains("Borrowed[Buffer[...]]"))
            assert(msg.contains("ambiguous"))
        }

        // -----------------------------------------------------------------
        // Enum structural detection: positive and negative cases
        // -----------------------------------------------------------------

        "rejects enum without `val value: Int` used in a binding" in {
            val tasty = findTastyFiles(
                testClassesDir,
                n => n == "BadEnumNoValueBindings.tasty" || n == "BadEnumNoValue.tasty"
            )
            assert(tasty.nonEmpty)
            val out = Files.createTempDirectory("kyo-ffi-gen-bad-enum-novalue")
            val ex = intercept[FfiExtractionError] {
                FfiGenerator.generate(tasty, testClasspath, out, FfiGenerator.Platform.JVM)
            }
            val msg = ex.errors.map(_.message).mkString("\n")
            assert(msg.contains("val value: Int"))
        }

        "rejects enum without companion `fromInt` used in a binding" in {
            val tasty = findTastyFiles(
                testClassesDir,
                n => n == "BadEnumNoFromIntBindings.tasty" || n == "BadEnumNoFromInt.tasty"
            )
            assert(tasty.nonEmpty)
            val out = Files.createTempDirectory("kyo-ffi-gen-bad-enum-nofromint")
            val ex = intercept[FfiExtractionError] {
                FfiGenerator.generate(tasty, testClasspath, out, FfiGenerator.Platform.JVM)
            }
            val msg = ex.errors.map(_.message).mkString("\n")
            assert(msg.contains("fromInt"))
        }

        // -----------------------------------------------------------------
        // Outcome return type: end-to-end
        // -----------------------------------------------------------------

        "extracts WithErrorBindings and produces withError = true for riskyOp, false for safeOp" in {
            import kyo.ffi.codegen.model.*
            val tasty = findTastyFiles(testClassesDir, _ == "WithErrorBindings.tasty")
            assert(tasty.nonEmpty)
            val out    = Files.createTempDirectory("kyo-ffi-gen-witherror")
            val result = FfiGenerator.generate(tasty, testClasspath, out, FfiGenerator.Platform.JVM)
            val spec = result.traits.find(_.simpleName == "WithErrorBindings").getOrElse(
                fail("WithErrorBindings not extracted")
            )
            val riskyMethod = spec.methods.find(_.scalaName == "riskyOp").getOrElse(fail("riskyOp method not found"))
            assert(riskyMethod.withError == true)
            // riskyOp returns Ffi.Outcome[Int], so the inspector reads the [Int] width argument as the marshalling shape:
            // the descriptor reads the C return at JAVA_INT and the Int is sign-extended into the packed Long.
            assert(riskyMethod.returnShape == ReturnShape.Primitive(TypeRef.IntT))

            val safeMethod = spec.methods.find(_.scalaName == "safeOp").getOrElse(fail("safeOp method not found"))
            assert(safeMethod.withError == false)
        }

        "WithErrorBindings emits errno capture in generated JVM code" in {
            val tasty = findTastyFiles(testClassesDir, _ == "WithErrorBindings.tasty")
            assert(tasty.nonEmpty)
            val out    = Files.createTempDirectory("kyo-ffi-gen-witherror-jvm")
            val result = FfiGenerator.generate(tasty, testClasspath, out, FfiGenerator.Platform.JVM)
            val generated = result.files.find(_.getFileName.toString == "WithErrorBindingsImpl.scala").getOrElse(
                fail("WithErrorBindingsImpl.scala not generated")
            )
            val content = Files.readString(generated)
            assert(content.contains("Ffi.Outcome.fromValueErrno("))
            assert(content.contains("errnoSeg"))
            assert(content.contains(": Ffi.Outcome[Int] ="))
            // The safeOp method should NOT have Outcome in its return type.
            val safeDefLine = content.linesIterator.find(_.contains("def safeOp")).getOrElse(fail("safeOp def not found"))
            assert(!safeDefLine.contains("Outcome"))
        }

        "WithErrorBindings emits errno capture in generated Native code" in {
            val tasty = findTastyFiles(testClassesDir, _ == "WithErrorBindings.tasty")
            assert(tasty.nonEmpty)
            val out    = Files.createTempDirectory("kyo-ffi-gen-witherror-native")
            val result = FfiGenerator.generate(tasty, testClasspath, out, FfiGenerator.Platform.Native)
            val generated = result.files.find(_.getFileName.toString == "WithErrorBindingsImpl.scala").getOrElse(
                fail("WithErrorBindingsImpl.scala not generated")
            )
            val content = Files.readString(generated)
            assert(content.contains("Ffi.Outcome.fromValueErrno("))
            assert(content.contains("__errno"))
            assert(content.contains(": Ffi.Outcome[Int] ="))
        }

        "WithErrorBindings emits errno capture in generated JS code" in {
            val tasty = findTastyFiles(testClassesDir, _ == "WithErrorBindings.tasty")
            assert(tasty.nonEmpty)
            val out    = Files.createTempDirectory("kyo-ffi-gen-witherror-js")
            val result = FfiGenerator.generate(tasty, testClasspath, out, FfiGenerator.Platform.JS)
            val generated = result.files.find(_.getFileName.toString == "WithErrorBindingsImpl.scala").getOrElse(
                fail("WithErrorBindingsImpl.scala not generated")
            )
            val content = Files.readString(generated)
            assert(content.contains("Ffi.Outcome.fromValueErrno("))
            assert(content.contains("KoffiFacade.errno()"))
            assert(content.contains(": Ffi.Outcome[Int] ="))
        }

        "plain return in WithErrorBindings does NOT include errno capture for safeOp" in {
            val tasty = findTastyFiles(testClassesDir, _ == "WithErrorBindings.tasty")
            assert(tasty.nonEmpty)
            val out    = Files.createTempDirectory("kyo-ffi-gen-witherror-plain")
            val result = FfiGenerator.generate(tasty, testClasspath, out, FfiGenerator.Platform.JVM)
            val generated = result.files.find(_.getFileName.toString == "WithErrorBindingsImpl.scala").getOrElse(
                fail("WithErrorBindingsImpl.scala not generated")
            )
            val content = Files.readString(generated)
            // Split content by method to verify safeOp specifically has no Outcome
            val safeDefLine = content.linesIterator.find(_.contains("def safeOp")).getOrElse(fail("safeOp def not found"))
            assert(safeDefLine.contains(": Int ="))
            assert(!safeDefLine.contains("Outcome"))
        }

        // -----------------------------------------------------------------
        // @Ffi.byValue: by-value struct return shape selection (inspector)
        // -----------------------------------------------------------------

        "extracts ByValueBindings: @Ffi.byValue yields ReturnShape.Struct, plain case-class yields ReturnShape.MultiValue" in {
            import kyo.ffi.codegen.model.*
            val tasty = findTastyFiles(testClassesDir, _ == "ByValueBindings.tasty")
            assert(tasty.nonEmpty)
            val out    = Files.createTempDirectory("kyo-ffi-gen-byvalue")
            val result = FfiGenerator.generate(tasty, testClasspath, out, FfiGenerator.Platform.JVM)
            val spec = result.traits.find(_.simpleName == "ByValueBindings").getOrElse(
                fail("ByValueBindings not extracted")
            )

            // Plain case-class return: multi-value (C return + out-params).
            val plain = spec.methods.find(_.scalaName == "plainPoint").getOrElse(fail("plainPoint method not found"))
            assert(plain.returnShape.isInstanceOf[ReturnShape.MultiValue])
            assert(plain.returnShape.asInstanceOf[ReturnShape.MultiValue].spec.simpleName == "ByValuePoint")

            // @Ffi.byValue case-class return: by-value struct (C `void f(S* out, ...)`).
            val byVal = spec.methods.find(_.scalaName == "byValuePoint").getOrElse(fail("byValuePoint method not found"))
            assert(byVal.returnShape.isInstanceOf[ReturnShape.Struct])
            assert(byVal.returnShape.asInstanceOf[ReturnShape.Struct].spec.simpleName == "ByValuePoint")

            // @Ffi.byValue with a single-field struct: allowed (unlike a multi-value return, which needs >= 2 fields).
            val single = spec.methods.find(_.scalaName == "byValueSingle").getOrElse(fail("byValueSingle method not found"))
            assert(single.returnShape.isInstanceOf[ReturnShape.Struct])
            assert(single.returnShape.asInstanceOf[ReturnShape.Struct].spec.fields.map(_.name) == List("v"))
        }

        "@Ffi.byValue emits the unified out-pointer-first struct-return ABI on JVM, Native, and JS" in {
            val tasty = findTastyFiles(testClassesDir, _ == "ByValueBindings.tasty")
            assert(tasty.nonEmpty)

            // JVM: struct out-segment is allocated and passed FIRST among the C params (after errnoSeg), C returns void.
            val outJvm = Files.createTempDirectory("kyo-ffi-gen-byvalue-jvm")
            val jvm    = FfiGenerator.generate(tasty, testClasspath, outJvm, FfiGenerator.Platform.JVM)
            val jvmSrc = Files.readString(
                jvm.files.find(_.getFileName.toString == "ByValueBindingsImpl.scala").getOrElse(fail("JVM impl not generated"))
            )
            assert(jvmSrc.contains("val _: Unit = byValuePointMH.invokeExact(errnoSeg, out, seed)"))
            assert(jvmSrc.contains("FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT)"))

            // Native: extern decl declares the struct out-pointer FIRST and the call passes `out` FIRST.
            val outNative = Files.createTempDirectory("kyo-ffi-gen-byvalue-native")
            val native    = FfiGenerator.generate(tasty, testClasspath, outNative, FfiGenerator.Platform.Native)
            val nativeSrc = Files.readString(
                native.files.find(_.getFileName.toString == "ByValueBindingsImpl.scala").getOrElse(fail("Native impl not generated"))
            )
            assert(nativeSrc.contains("ext.by_value_point(out, seed)"))
            assert(nativeSrc.contains("def by_value_point(out: Ptr[CStruct2[CInt, CInt]], seed: CInt): Unit = extern"))

            // JS: koffi descriptor gains a leading struct out-pointer and a void return; the call passes the out buffer FIRST.
            val outJs = Files.createTempDirectory("kyo-ffi-gen-byvalue-js")
            val js    = FfiGenerator.generate(tasty, testClasspath, outJs, FfiGenerator.Platform.JS)
            val jsSrc = Files.readString(
                js.files.find(_.getFileName.toString == "ByValueBindingsImpl.scala").getOrElse(fail("JS impl not generated"))
            )
            assert(jsSrc.contains("""KoffiFacade.outStruct("ByValuePoint")"""))
            assert(jsSrc.contains("facade.byValuePoint(__kyoStructOut$.buf, seed)"))
            assert(jsSrc.contains("""Seq[js.Any]("ByValuePoint*".asInstanceOf[js.Any], "int".asInstanceOf[js.Any])"""))
        }
    }

end FfiGeneratorTest
