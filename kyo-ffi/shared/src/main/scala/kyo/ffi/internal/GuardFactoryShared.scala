package kyo.ffi.internal

import kyo.ffi.Ffi

/** Shared scaffolding for per-platform [[Ffi.Guard]] factory objects. Centralizes [[GuardRegistry]] registration. */
private[ffi] object GuardFactoryShared:

    /** Register a freshly-built guard with [[GuardRegistry]] and return it. */
    def register[G <: Ffi.Guard](g: G): G =
        GuardRegistry.register(g)
        g
end GuardFactoryShared
