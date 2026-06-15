package kyo.ffi.internal

/** Error / diagnostic message strings used by generated FFI code, callback trampolines, scratch arenas, type-shape checks, and variadic
  * marshalling. Kept separate from [[FfiErrors]] (runtime shared) and [[FfiPlatformErrors]] (platform detection) to make the audience of
  * each message clear.
  */
private[ffi] object FfiGenErrors:

    // --- Callback exception handling ---

    /** Diagnostic emitted when a user-supplied FFI callback throws; exceptions must not propagate into C. */
    def callbackFailed(bindingFqn: String, methodName: String, kind: String, t: Throwable): String =
        val cls = if t == null then "<null>" else t.getClass.getName
        val msg = if t == null || t.getMessage == null then "" else s": ${t.getMessage}"
        s"[kyo-ffi] callback failed in '$bindingFqn.$methodName' ($kind): $cls$msg, returning zero default to C."
    end callbackFailed

    /** Print the `callbackFailed` diagnostic and stack trace to stderr. Shared entry point for all platform callback trampolines. */
    def reportCallbackFailed(bindingFqn: String, methodName: String, kind: String, t: Throwable): Unit =
        java.lang.System.err.println(callbackFailed(bindingFqn, methodName, kind, t))
        if t != null then t.printStackTrace()
    end reportCallbackFailed

    // --- FfiMalformedResult ---

    /** Message for [[kyo.ffi.FfiMalformedResult]] when a `char*` field lacks a NUL terminator within the allowed byte window. */
    def stringFieldUnbounded(bindingFqn: String, methodName: String, fieldName: String, maxBytes: Long): String =
        s"[kyo-ffi] String field '$fieldName' in '$bindingFqn.$methodName' is not NUL-terminated within $maxBytes bytes. " +
            "Raise -Dkyo.ffi.stringFieldMaxBytes= or fix the C library to return a bounded NUL-terminated string."

    // --- Scratch overflow diagnostics ---

    /** Diagnostic emitted when the per-thread `Scratch` arena spills to a fresh confined Arena (gated by
      * `-Dkyo.ffi.scratch.logSpills=true`).
      */
    def scratchSpilled(bindingFqn: String, methodName: String, size: Long): String =
        s"[kyo-ffi] Scratch spill in '$bindingFqn.$methodName': $size bytes exceeded per-thread block, allocated fresh confined arena. " +
            "Raise -Dkyo.ffi.scratch.size= or override Ffi.Config.scratchSize to reduce spills."

    /** Message for [[kyo.ffi.FfiInternalError]] when `FfiUnsafe.expect` receives an unexpected runtime type. */
    def expectTypeMismatch(expected: String, bindingFqn: String, methodName: String, actualClass: String): String =
        s"[kyo-ffi] Expected $expected in '$bindingFqn.$methodName' but got $actualClass. This indicates a runtime type-shape mismatch " +
            "between the generated code and the kyo-ffi runtime, regenerate with `sbt clean compile`."

    // --- Unsupported variadic argument ---

    /** Message for [[kyo.ffi.FfiLoadError.Unsupported]] when a variadic argument has an unsupported runtime type. */
    def unsupportedVararg(
        bindingFqn: String,
        methodName: String,
        runtimeClass: String,
        supported: String = "supported: Int, Long, Double, String, Buffer[A]"
    ): String =
        s"kyo-ffi: unsupported variadic argument type `$runtimeClass` at $bindingFqn.$methodName, $supported"

    // --- Struct Buffer size inference ---

    /** Emitted when a struct contains a `Buffer[A]` field but no `Int`/`Long` sibling field to infer the buffer extent from. */
    def missingBorrowedBufferSize(structFqcn: String, bufferFieldName: String): String =
        s"[kyo-ffi] struct '$structFqcn' field '$bufferFieldName' is a Buffer[...] but the struct has no Int or Long sibling field to infer size from. " +
            "Add exactly one Int or Long field to this struct (used as the buffer size)."

    /** Emitted when a struct contains a `Buffer[A]` field but has more than one `Int`/`Long` sibling, size inference is ambiguous. */
    def ambiguousBorrowedBufferSize(structFqcn: String, bufferFieldName: String, candidates: List[String]): String =
        s"[kyo-ffi] struct '$structFqcn' field '$bufferFieldName' is a Buffer[...] but the struct has ${candidates.size} Int/Long sibling fields (${candidates.mkString(", ")}), size inference is ambiguous. " +
            "Reduce to exactly one Int/Long field, or split the struct."

end FfiGenErrors
