package kyo

import java.nio.charset.StandardCharsets

// Top-level wrapper objects (not nested in the test class) so the lambda bodies hoisted by Scala's
// closure compilation land in the holder's own class file rather than in `SqlRunBytecodeTest`'s
// anonfun pool. This is required for the bytecode substring scan below to be method-scoped.

case class SqlRunBytecodePerson(id: Long, name: String, age: Int, deptId: Long) derives Schema
case class SqlRunBytecodeUser(id: Long, email: String) derives Schema

private object SqlRunBytecodeStaticFastPathHolder:
    // `inline given` is required for the static fast-path: the macro's SqlSchemaInfo.extract can only
    // see the schema's construction body when the given is declared inline (Scala 3 hides plain-given
    // RHSes behind a lazy-val reference at macro-expansion time).
    inline given SqlSchema[SqlRunBytecodePerson] = SqlSchema.derived

    // Fully-inline literal query: `isStaticallyReducible` returns true; the macro splices the
    // compile-time-rendered SqlStatic.Rendered constant, never naming SqlRender in the emitted code.
    def runFastPath(using Frame): Chunk[SqlRunBytecodePerson] < (Async & Abort[SqlException] & Scope) =
        Sql.from[SqlRunBytecodePerson]("p").run
end SqlRunBytecodeStaticFastPathHolder

private object SqlRunBytecodeRuntimeFallbackHolder:
    given SqlSchema[SqlRunBytecodeUser] = SqlSchema.derived

    // `Update.set(...)` carries a lambda whose body is not statically liftable, so
    // `isStaticallyReducible` returns false and `.run`'s splice takes the runtime fallback —
    // which calls `kyo.internal.SqlRender.render(...)` directly. That Methodref lands in this
    // holder's constant pool, giving the substring scan a positive signal.
    def runRuntime(using Frame): Long < (Async & Abort[SqlException] & Scope) =
        Sql.update[SqlRunBytecodeUser].set(_.email := "new").where(_.id == 1L).run
end SqlRunBytecodeRuntimeFallbackHolder

/** Bytecode-level verification that the `.run` static fast-path actually emits the compile-time render and does NOT fall back to a runtime
  * `kyo.internal.SqlRender.render` call (PHASE-7-AUDIT B-1).
  *
  * The macro in `kyo.internal.SqlRunMacro.runQueryImpl` checks `isStaticallyReducible(q)` at expansion. On `true`, it splices a literal
  * `SqlStatic.Rendered` value and never names `SqlRender` in the emitted bytecode. On `false` (runtime fallback) the splice contains
  * `kyo.internal.SqlRender.render(...)`, which materialises as a `Methodref` to `kyo/internal/SqlRender` in the enclosing class file's
  * constant pool.
  *
  * This test isolates each path in its own holder class so the class file's constant pool reflects only that path's referenced classes,
  * then scans the raw bytes for the UTF-8 sequence `SqlRender`. UTF-8 entries in the JVM class file format store identifier strings as
  * length-prefixed raw bytes — a substring scan is sufficient to confirm presence or absence.
  *
  * JVM-only: class files exist only on the JVM. JS / Native runtimes have separate representations; the same wiring is verified there by
  * the compile-time `runStatic` leaves in `SqlRunStaticTest`.
  */
class SqlRunBytecodeTest extends Test:

    // Two top-level holders defined below the test class — see file-level scaladoc for why the
    // holders must NOT be nested objects (closure-hoisting would push lambda bodies into the
    // enclosing class, polluting the substring scan with both paths' constants).

    /** Loads `<className>.class` from the holder's classloader and returns its raw bytes. */
    private def classFileBytes(cls: Class[?]): Array[Byte] =
        val name     = cls.getName.replace('.', '/') + ".class"
        val resource = cls.getClassLoader.getResourceAsStream(name)
        if resource == null then
            throw new AssertionError(s"Could not locate class file for $name on the test classpath")
        try resource.readAllBytes()
        finally resource.close()
    end classFileBytes

    /** Returns true if the class file's bytes contain the UTF-8 sequence for `needle` (typically a class binary name fragment like
      * `kyo/internal/SqlRender`). UTF-8 string entries in the JVM constant pool are stored as length-prefixed raw bytes; a substring search
      * reliably detects any reference to the named identifier without needing a full class-file parser.
      */
    private def containsUtf8(bytes: Array[Byte], needle: String): Boolean =
        val needleBytes = needle.getBytes(StandardCharsets.UTF_8)
        if needleBytes.isEmpty || needleBytes.length > bytes.length then false
        else
            var i         = 0
            val lastStart = bytes.length - needleBytes.length
            while i <= lastStart do
                var j       = 0
                var matched = true
                while matched && j < needleBytes.length do
                    if bytes(i + j) != needleBytes(j) then matched = false
                    j += 1
                if matched then return true
                i += 1
            end while
            false
        end if
    end containsUtf8

    // The negative-control assertion: the static fast-path's class file must NOT reference SqlRender.
    "static fast-path bytecode does NOT reference kyo.internal.SqlRender" in {
        val bytes = classFileBytes(SqlRunBytecodeStaticFastPathHolder.getClass)
        assert(
            !containsUtf8(bytes, "kyo/internal/SqlRender"),
            "static fast-path holder unexpectedly references kyo/internal/SqlRender — the macro is emitting the runtime fallback instead of the compile-time splice"
        )
    }

    // Positive-control assertion: the runtime fallback's class file DOES reference SqlRender.
    // This proves the substring scan would catch a regression (the test isn't trivially green).
    "runtime fallback bytecode DOES reference kyo.internal.SqlRender (positive control)" in {
        val bytes = classFileBytes(SqlRunBytecodeRuntimeFallbackHolder.getClass)
        assert(
            containsUtf8(bytes, "kyo/internal/SqlRender"),
            "runtime fallback holder unexpectedly does NOT reference kyo/internal/SqlRender — the substring-scan check has no signal"
        )
    }

end SqlRunBytecodeTest
