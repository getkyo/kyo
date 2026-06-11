package kyo.ffi.internal

import kyo.ffi.FfiInternalError

/** Type-checked casts for the kyo-ffi runtime. Used at sites where input provenance is user/host code (e.g. `Buffer.Raw` unwrapping). */
object FfiUnsafe:

    /** Verify `x` is an instance of `cls` and return it cast to `A`, or throw [[FfiInternalError]] with a diagnostic. */
    def expect[A <: AnyRef](x: AnyRef, cls: Class[?], expected: String, bindingFqn: String, methodName: String): A =
        if x != null && cls.isInstance(x) then x.asInstanceOf[A]
        else
            val actual = if x == null then "null" else x.getClass.getName
            throw new FfiInternalError(FfiGenErrors.expectTypeMismatch(expected, bindingFqn, methodName, actual))

end FfiUnsafe
