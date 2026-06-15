package kyo.ffi.internal

import kyo.ffi.FfiLoadError

/** Struct byte-size self-check emitted at each generated impl's static initialization. Throws [[kyo.ffi.FfiLoadError.AbiMismatch]] on
  * mismatch.
  */
object StructAbiCheck:

    /** Throw [[kyo.ffi.FfiLoadError.AbiMismatch]] if `actualSize` != `expectedSize` for the given struct and binding. */
    def verifyByteSize(traitFqn: String, structName: String, expectedSize: Long, actualSize: Long): Unit =
        if expectedSize != actualSize then
            throw new FfiLoadError.AbiMismatch(
                expectedSize.toString,
                actualSize.toString,
                FfiErrors.structAbiMismatch(traitFqn, structName, expectedSize, actualSize)
            )
    end verifyByteSize
end StructAbiCheck
