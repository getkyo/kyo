package kyo.ffi.internal

import kyo.ffi.FfiLoadError

/** Shared reflective instantiation shell for [[kyo.ffi.Ffi.load]]. Platform supplies a `lookup` function; this handles the Option flow. */
private[ffi] object FfiReflectCore:

    /** Invoke the platform lookup and return the instance, or throw [[FfiLoadError.ImplNotFound]] with the platform not-found error
      * message.
      */
    def instantiate(
        implName: String,
        traitFqn: String,
        lookup: String => Option[() => AnyRef],
        notFoundErr: (String, String) => String
    ): AnyRef =
        lookup(implName) match
            case Some(ctor) => ctor()
            case None       => throw new FfiLoadError.ImplNotFound(traitFqn, notFoundErr(implName, traitFqn), null)
end FfiReflectCore
