package kyo.ffi.internal

import kyo.ConcreteTag
import kyo.ffi.Ffi
import kyo.ffi.FfiLoadError

/** `Ffi.load` on Scala.js: the generated impl is constructed where the load is written, not looked up by name.
  *
  * The linker keeps a class registered for reflective lookup in the module that starts the program, whether or not anything loads it, so a
  * page linked from the same sources as a Node program would fetch every binding, koffi and the native loader. [[FfiLoadMacro]] names the
  * impl at the call site instead, so a binding is part of whichever module loads it.
  *
  * Expansions of `Ffi.load` land in the caller's package, so the members they call are public.
  */
object FfiLoad:

    /** Returns the cached impl of `T`, constructing it on the first load. */
    inline def load[T <: Ffi](inline ct: ConcreteTag[T]): T = ${ FfiLoadMacro.load[T]('ct) }

    /** The first load of `binding`: the manifest pre-check and the host check, then `create`, cached. Nothing is cached when either throws. */
    def construct(binding: Class[?], create: () => AnyRef): AnyRef =
        FfiLoadCore.cache.computeIfAbsent(
            binding,
            _ =>
                admit(binding)
                create()
        )

    /** The load of a binding with no generated impl: the same checks as [[construct]], then `ImplNotFound`. */
    def missing(binding: Class[?]): Nothing =
        admit(binding)
        val traitFqn = binding.getName
        throw new FfiLoadError.ImplNotFound(traitFqn, FfiPlatformErrors.implClassNotFoundJs(traitFqn + "Impl", traitFqn), null)
    end missing

    // A browser is rejected before the impl is constructed: its companion loads koffi, which needs a Node-like host, and a failure raised
    // from there would be a companion initializer error rather than this one.
    private def admit(binding: Class[?]): Unit =
        FfiLoadCore.precheck(binding.getName)
        if NativeLoader.detectBrowser() then
            throw new FfiLoadError.Unsupported(FfiPlatformErrors.BrowserUnsupportedLoad)
    end admit

end FfiLoad
