package kyo.ffi.internal

/** Runtime ABI version check embedded in every generated impl initializer. Increment [[runtimeAbi]] on binary-incompatible changes. */
object AbiCheck:

    /** Current ABI version. */
    val runtimeAbi: Int = 1

    /** Throw [[java.lang.IllegalStateException]] if `generatedAbi` does not match [[runtimeAbi]]. */
    def verify(generatedAbi: Int, bindingFqn: String): Unit =
        if generatedAbi != runtimeAbi then
            throw new IllegalStateException(FfiErrors.abiMismatch(generatedAbi, runtimeAbi, bindingFqn))
end AbiCheck
