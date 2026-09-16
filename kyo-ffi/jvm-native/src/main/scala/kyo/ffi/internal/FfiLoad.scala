package kyo.ffi.internal

import kyo.ConcreteTag
import kyo.ffi.Ffi

/** `Ffi.load` on the JVM and Scala Native: the generated impl is found by name through [[FfiReflect]] on the first load.
  *
  * Expansions of `Ffi.load` land in the caller's package, so the members they call are public.
  */
object FfiLoad:

    /** Returns the cached impl of `T`, constructing it on the first load. */
    // The instantiation function is a shared instance: computeIfAbsent with a closure argument allocates the closure on every call, cache
    // hit or not, and load sits on callers' hot paths.
    inline def load[T <: Ffi](inline ct: ConcreteTag[T]): T =
        FfiLoadCore.cache.computeIfAbsent(ct.toClass, instantiate).asInstanceOf[T]

    /** The first load of a binding: the manifest pre-check, then the reflective construction of its impl. */
    val instantiate: java.util.function.Function[Class[?], AnyRef] = binding =>
        val traitFqn = binding.getName
        FfiLoadCore.precheck(traitFqn)
        FfiReflect.instantiate(traitFqn + "Impl", traitFqn)

end FfiLoad
