package kyo.ffi.codegen

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kyo.ffi.codegen.emitters.*
import kyo.ffi.codegen.emitters.EmitterFixtures.*
import kyo.ffi.codegen.model.*

/** Byte-exact snapshot test for the three platform emitters.
  *
  * Approach: a curated list of hand-written [[TraitSpec]] fixtures exercises the emission shapes that matter (primitive return, struct
  * return, borrowed string, borrowed buffer, multi-value, callbacks, variadic, enum, handle, union, WithError). For each fixture we run
  * each emitter and compare the output against a captured golden file under `src/test/resources/golden/<platform>/<fixture>.txt`.
  *
  * Workflow:
  *   - Pre-refactor capture: run the test with `-Dupdate-golden=true`. The test writes the current output to the golden file and passes.
  *   - Post-refactor verification: run without the flag. Any byte-level diff fails the test.
  *
  * Why not enumerate kyo-ffi-it bindings: kyo-ffi-it is a separate sbt project whose bindings only surface as TASTy after a full compile
  * cycle. The hand-written fixtures here cover the same emission paths with lower setup cost.
  *
  * Rationale for the hand-written corpus: every branch inside the three emitters routes through one of a small number of emission
  * primitives, param marshal by TypeRef, return-shape dispatch, struct field read/write, callback stub emission, errno capture. The
  * fixtures together touch every such primitive at least once.
  */
class EmitterGoldenTest extends kyo.test.Test[Any]:

    /** Resolve the golden root anchored at the kyo-ffi-codegen module directory, independent of the forked test JVM's working directory. We
      * probe upward from the current working dir until we find a directory that contains `src/test/resources`.
      */
    private val goldenRoot: Path =
        val cwd = Paths.get("").toAbsolutePath
        // The codegen module lives at kyo-ffi/codegen. The forked test JVM's cwd is that module dir; a repo-root cwd
        // resolves into it. If cwd already ends with kyo-ffi/codegen use it, otherwise resolve it as a child of cwd.
        val marker = Paths.get("kyo-ffi", "codegen")
        val base =
            if cwd.endsWith(marker) then cwd
            else cwd.resolve(marker)
        base.resolve("src/test/resources/golden")
    end goldenRoot

    private val updateGolden: Boolean =
        java.lang.System.getProperty("update-golden") == "true"

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private val fixturePoint = StructSpec(
        fqcn = "kyo.example.golden.Point",
        simpleName = "Point",
        fields = List(StructField("x", TypeRef.IntT), StructField("y", TypeRef.IntT)),
        packed = false
    )

    private val fixtureReadResult = StructSpec(
        fqcn = "kyo.example.golden.ReadResult",
        simpleName = "ReadResult",
        fields = List(
            StructField("bytesRead", TypeRef.IntT),
            StructField("eof", TypeRef.BooleanT)
        ),
        packed = false
    )

    /** Primitive-return: `int add(int, int)`. Covers the simplest emission path. */
    private val specPrimitive: TraitSpec = mkTrait(
        "PrimitiveBindings",
        "kyo_prim",
        List(
            mkMethod(
                "add",
                "add",
                List(ParamSpec("a", TypeRef.IntT), ParamSpec("b", TypeRef.IntT)),
                ReturnShape.Primitive(TypeRef.IntT)
            )
        )
    )

    /** Void return: exercises the special void code path. */
    private val specVoid: TraitSpec = mkTrait(
        "VoidBindings",
        "kyo_void",
        List(mkMethod("noop", "noop", Nil, ReturnShape.Void))
    )

    /** Struct return + struct param. Covers struct layout, field reads/writes, coalesced out-cell allocation. */
    private val specStruct: TraitSpec = mkTrait(
        "StructBindings",
        "kyo_struct",
        List(
            mkMethod(
                "move",
                "move",
                List(ParamSpec("p", TypeRef.StructT("kyo.example.golden.Point")), ParamSpec("dx", TypeRef.IntT)),
                ReturnShape.Struct(fixturePoint)
            )
        ),
        structs = List(fixturePoint)
    )

    /** Multi-value return: head primitive, tail out-params. */
    private val specMultiValue: TraitSpec = mkTrait(
        "MultiValueBindings",
        "kyo_mv",
        List(
            mkMethod(
                "readInto",
                "read_into",
                List(ParamSpec("fd", TypeRef.IntT), ParamSpec("buf", TypeRef.BufferT(TypeRef.ByteT))),
                ReturnShape.MultiValue(fixtureReadResult),
                blocking = true
            )
        ),
        structs = List(fixtureReadResult)
    )

    /** String param + borrowed-string return. Exercises UTF-8 marshalling + readCStringBounded. */
    private val specBorrowedString: TraitSpec = mkTrait(
        "BorrowedStringBindings",
        "kyo_borrowed_str",
        List(
            mkMethod(
                "getenvLike",
                "getenv",
                List(ParamSpec("name", TypeRef.StringT)),
                ReturnShape.BorrowedString(4096)
            )
        )
    )

    /** Borrowed buffer return. */
    private val specBorrowedBuffer: TraitSpec = mkTrait(
        "BorrowedBufferBindings",
        "kyo_borrowed_buf",
        List(
            mkMethod(
                "mallocChunk",
                "malloc_chunk",
                List(ParamSpec("n", TypeRef.LongT)),
                ReturnShape.BorrowedBuffer(TypeRef.ByteT, "n")
            )
        )
    )

    /** WithError: plain primitive wrapped in errno capture. */
    private val specWithError: TraitSpec = mkTrait(
        "WithErrorBindings",
        "kyo_we",
        List(
            mkMethod(
                "failable",
                "failable",
                List(ParamSpec("x", TypeRef.IntT)),
                ReturnShape.Primitive(TypeRef.IntT),
                withError = true
            )
        )
    )

    /** Buffer + blocking. */
    private val specBuffer: TraitSpec = mkTrait(
        "BufferBindings",
        "kyo_buf",
        List(
            mkMethod(
                "process",
                "process",
                List(ParamSpec("buf", TypeRef.BufferT(TypeRef.ByteT)), ParamSpec("len", TypeRef.IntT)),
                ReturnShape.Primitive(TypeRef.IntT),
                blocking = true
            )
        )
    )

    /** Handle param + Handle return. */
    private val specHandle: TraitSpec = mkTrait(
        "HandleBindings",
        "kyo_handle",
        List(
            mkMethod(
                "open",
                "open",
                List(ParamSpec("path", TypeRef.StringT)),
                ReturnShape.HandleReturn("kyo.example.golden.MyHandle", nullable = false)
            ),
            mkMethod(
                "close",
                "close",
                List(ParamSpec("h", TypeRef.HandleT("kyo.example.golden.MyHandle"))),
                ReturnShape.Void
            )
        )
    )

    /** Blocking + WithError: `long recv(int fd, Buffer[Byte] buf, int len)` annotated `@Ffi.blocking`.
      *
      * Exercises the `A < kyo.Async` signature plus the per-backend fiber-suspending body: a `kyo.Sync.defer` block
      * on JVM/Native and the koffi `.async` + `Promise` bridge on JS. `WithError[Long]` additionally covers errno
      * capture inside the async completion callback.
      */
    private val specBlocking: TraitSpec = mkTrait(
        "BlockingBindings",
        "kyo_blocking",
        List(
            mkMethod(
                "recv",
                "recv",
                List(
                    ParamSpec("fd", TypeRef.IntT),
                    ParamSpec("buf", TypeRef.BufferT(TypeRef.ByteT)),
                    ParamSpec("len", TypeRef.IntT)
                ),
                ReturnShape.Primitive(TypeRef.LongT),
                blocking = true,
                withError = true
            )
        )
    )

    /** Array param (non-blocking, pin path). */
    private val specArray: TraitSpec = mkTrait(
        "ArrayBindings",
        "kyo_arr",
        List(
            mkMethod(
                "sum",
                "sum_ints",
                List(ParamSpec("xs", TypeRef.ArrayT(TypeRef.IntT)), ParamSpec("n", TypeRef.IntT)),
                ReturnShape.Primitive(TypeRef.IntT)
            )
        )
    )

    private val allFixtures: List[(String, TraitSpec)] = List(
        "primitive"       -> specPrimitive,
        "void"            -> specVoid,
        "struct"          -> specStruct,
        "multivalue"      -> specMultiValue,
        "borrowed_string" -> specBorrowedString,
        "borrowed_buffer" -> specBorrowedBuffer,
        "with_error"      -> specWithError,
        "buffer"          -> specBuffer,
        "blocking"        -> specBlocking,
        "handle"          -> specHandle,
        "array"           -> specArray
    )

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    private def checkOrUpdate(platform: String, key: String, actual: String)(using kyo.test.AssertScope): Unit =
        val file = goldenRoot.resolve(platform).resolve(s"$key.txt")
        if updateGolden then
            Files.createDirectories(file.getParent)
            Files.write(file, actual.getBytes(StandardCharsets.UTF_8))
            succeed
        else if !Files.exists(file) then
            fail(
                s"golden file $file missing, run the test with -Dupdate-golden=true to capture the current output."
            )
        else
            val expected = new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
            if expected != actual then
                val expectedLines = expected.linesIterator.toList
                val actualLines   = actual.linesIterator.toList
                val firstDiff     = expectedLines.zip(actualLines).zipWithIndex.find { case ((e, a), _) => e != a }
                val where = firstDiff match
                    case Some(((e, a), idx)) => s"line ${idx + 1}:\n  expected: $e\n  actual:   $a"
                    case None                => s"lengths differ: expected=${expectedLines.size}, actual=${actualLines.size}"
                val excerpt = actualLines.take(10).mkString("\n")
                fail(
                    s"golden diff for $platform/$key:\n$where\n\nfirst 10 lines of actual output:\n$excerpt"
                )
            else
                // The emitted output matches the golden file; record an evaluated assertion so the leaf is not
                // flagged by the no-assertion check.
                assert(expected == actual)
            end if
        end if
    end checkOrUpdate

    "JvmEmitter golden" - {
        allFixtures.foreach { case (key, spec) =>
            key in {
                val out = JvmEmitter.emit(spec)
                checkOrUpdate("jvm", key, out)
            }
        }
    }

    "NativeEmitter golden" - {
        allFixtures.foreach { case (key, spec) =>
            key in {
                val out = NativeEmitter.emit(spec)
                checkOrUpdate("native", key, out)
            }
        }
    }

    "JsEmitter golden" - {
        allFixtures.foreach { case (key, spec) =>
            key in {
                val out = JsEmitter.emit(spec)
                checkOrUpdate("js", key, out)
            }
        }
    }

end EmitterGoldenTest
