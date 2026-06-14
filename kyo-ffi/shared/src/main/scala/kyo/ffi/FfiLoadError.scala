package kyo.ffi

import kyo.Chunk

/** Sealed hierarchy for every failure produced by [[Ffi.load]] and the platform [[kyo.ffi.internal.NativeLoader]].
  *
  * A caller who wants a single catch surface writes `catch { case _: FfiLoadError => ... }` and gets:
  *   - [[FfiLoadError.LibraryNotFound]] when the native library could not be resolved (bundled resource, system path, and any fallback all
  *     failed). Exposes `libraryId` and the ordered `candidates` chunk so retry / alternate-name logic can inspect what was tried.
  *   - [[FfiLoadError.AbiMismatch]] when the generated impl's ABI does not match the runtime's, typically a packed-struct layout
  *     disagreement or a koffi version outside the supported range. Exposes `expected` and `actual` version strings.
  *   - [[FfiLoadError.Unsupported]] for runtime-level refusals unrelated to ABI: 32-bit hosts and browser Scala.js targets.
  *   - [[FfiLoadError.ImplNotFound]] when reflective lookup cannot find the generated impl class, the code generator did not run, its
  *     output is not on the runtime classpath, or (on Scala.js / Native) the linker erased the annotation. Exposes `traitFqcn`.
  *
  * The legacy types [[FfiUnsupported]], [[FfiAbiMismatch]], and [[FfiKoffiVersionMismatch]] remain as `@deprecated` subclasses so existing
  * `catch FfiUnsupported` blocks continue to compile and match.
  */
sealed abstract class FfiLoadError(msg: String, cause: Throwable | Null)
    extends RuntimeException(msg, cause)

object FfiLoadError:

    /** The native library could not be located via any of the searched paths (bundled resource, system path, operator override). */
    final class LibraryNotFound(
        val libraryId: String,
        val candidates: Chunk[String],
        msg: String,
        cause: Throwable | Null
    ) extends FfiLoadError(msg, cause):
        /** Convenience constructor using the default `"Library '<id>' not found. Tried: ..."` message. */
        def this(libraryId: String, candidates: Chunk[String], cause: Throwable | Null) =
            this(libraryId, candidates, s"Library '$libraryId' not found. Tried: ${candidates.mkString(", ")}", cause)
    end LibraryNotFound

    /** The generated impl's ABI expectation does not match the runtime, packed-struct layout disagreement, koffi version out of range,
      * etc.
      *
      * Non-final so legacy [[FfiAbiMismatch]] / [[FfiKoffiVersionMismatch]] shim subclasses can inherit.
      */
    class AbiMismatch(
        val expected: String,
        val actual: String
    ) extends FfiLoadError(
            s"ABI mismatch: expected $expected, got $actual",
            null
        )

    /** The current runtime refuses to load, 32-bit host, or a browser-only Scala.js target without Node / Bun / Deno globals.
      *
      * Non-final so the legacy [[FfiUnsupported]] shim subclass can inherit.
      */
    class Unsupported(val reason: String)
        extends FfiLoadError(reason, null)

    /** Reflective instantiation could not find the generated `{T}Impl` class for the binding trait. Regenerate with `sbt clean compile`. */
    final class ImplNotFound(
        val traitFqcn: String,
        msg: String,
        cause: Throwable | Null
    ) extends FfiLoadError(msg, cause):
        /** Convenience constructor using the default `"Impl class for '<traitFqcn>' not found. …"` message. */
        def this(traitFqcn: String, cause: Throwable | Null) =
            this(traitFqcn, s"Impl class for '$traitFqcn' not found. Regenerate with `sbt clean compile`.", cause)
    end ImplNotFound
end FfiLoadError
