package kyo.ffi.internal

/** Runtime shared error / diagnostic message strings used across the ffi runtime by shared code.
  *
  * Kept in one place so wording is consistent and easily auditable. Tests in `JvmGuardSpec` and `BrowserDetectionSpec` assert on substrings
  * of these messages, wording must not change without updating those tests in lock-step.
  *
  * Platform-specific messages live in [[FfiPlatformErrors]]. Generated-code messages live in [[FfiGenErrors]].
  */
private[ffi] object FfiErrors:

    // --- Buffer ---

    /** Message for Buffer-closed access errors. */
    val BufferClosed: String = "Buffer is closed"

    /** Message for Buffer bounds-check failures. */
    def bufferIndexOutOfRange(i: Int): String = i.toString

    /** Message for `wrapBorrowed` raw-carrier type mismatch. */
    def wrapBorrowedExpected(platformQualified: String, actualClass: String): String =
        s"wrapBorrowed: expected raw to wrap $platformQualified, got $actualClass"

    // --- Guard leak diagnostic ---

    /** Diagnostic emitted when an `Ffi.Guard` is GC'd without being closed. */
    def leakWarning(frame: String): String =
        s"[kyo-ffi] Ffi.Guard opened at $frame was garbage-collected without close(). " +
            "Callbacks registered with this guard may have been invalidated."

    // --- Native retained-callback pool observability ---

    /** Diagnostic for Native retained-callback pool high-watermark threshold crossing. */
    def poolHighWatermark(shape: String, used: Int, total: Int, pct: Int): String =
        s"[kyo-ffi-warn] Native retained-callback pool for shape '$shape' at $used/$total ($pct%). " +
            "Raise -Dkyo.ffi.native.retainedCallbackPoolSize= or close unused Ffi.Guard instances."

    // --- Guard drain / callback-after-close ---

    /** Diagnostic emitted when `Ffi.Guard.close` drain times out with in-flight retained callbacks. */
    def guardCloseDrainTimeout(inFlight: Int, timeoutMs: Long): String =
        s"[kyo-ffi] Ffi.Guard.close() drain timed out after ${timeoutMs}ms with $inFlight retained callback(s) still in flight. " +
            "platformCloser has been DEFERRED, the arena / retained slots stay alive until the last in-flight callback returns via endCallback()."

    /** Diagnostic emitted when a retained callback is invoked after its guard was closed. */
    def callbackInvokedAfterClose(bindingFqn: String, methodName: String): String =
        s"[kyo-ffi] retained callback '$bindingFqn.$methodName' invoked after its Ffi.Guard was closed, returning zero default to C. " +
            "C code should not call a kyo-ffi callback outside the guard's lifetime; this is almost always a lifecycle bug on the C side."

    // --- Native retained-callback pool backpressure ---

    /** Diagnostic emitted when pool backpressure wait times out without a slot becoming available. */
    def poolBackpressureTimeout(shape: String, timeoutMs: Long): String =
        s"[kyo-ffi] Native retained-callback pool backpressure for shape '$shape' timed out after ${timeoutMs}ms, no slot became available. " +
            "Raise -Dkyo.ffi.native.retainedCallbackPoolBackpressureTimeoutMs= or -Dkyo.ffi.native.retainedCallbackPoolSize=."

    // --- ABI ---

    /** Message for ABI version mismatch between generated impl and runtime. */
    def abiMismatch(generatedAbi: Int, runtimeAbi: Int, bindingFqn: String): String =
        s"Generated FFI impl for binding '$bindingFqn' was produced with kyo-ffi ABI v$generatedAbi " +
            s"but the runtime is ABI v$runtimeAbi. " +
            s"Regenerate with `sbt clean compile`, or align versions."

    /** Message for struct byte-size mismatch between generated impl and platform measurement. */
    def structAbiMismatch(traitFqn: String, structName: String, expectedSize: Long, actualSize: Long): String =
        s"[kyo-ffi] Struct ABI mismatch for '$structName' in binding '$traitFqn': expected $expectedSize bytes " +
            s"but platform reports $actualSize bytes. " +
            s"This usually means the C declaration uses #pragma pack(1) (or equivalent) but the Scala case class is not listed in " +
            s"`Ffi.Config.packedStructs`, or vice versa. Align the Scala + C layouts and regenerate with `sbt clean compile`."

end FfiErrors
