package kyo.ffi.internal

import kyo.ffi.FfiAbiMismatch

/** Struct byte-size self-check emitted at each generated impl's static initialization. Throws [[kyo.ffi.FfiAbiMismatch]] on mismatch. */
object StructAbiCheck:

    /** Throw [[kyo.ffi.FfiAbiMismatch]] if `actualSize` != `expectedSize` for the given struct and binding. */
    def verifyByteSize(traitFqn: String, structName: String, expectedSize: Long, actualSize: Long): Unit =
        if expectedSize != actualSize then
            throw new FfiAbiMismatch(
                traitFqn,
                structName,
                expectedSize,
                actualSize,
                FfiErrors.structAbiMismatch(traitFqn, structName, expectedSize, actualSize)
            )
    end verifyByteSize
end StructAbiCheck
