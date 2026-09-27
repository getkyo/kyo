package kyo.ffi.internal

import kyo.Maybe
import kyo.discard
import kyo.ffi.FfiLoadError

/** Shared reflective instantiation shell for [[kyo.ffi.Ffi.load]]. Platform supplies a `lookup` function; this handles the Option flow. */
private[ffi] object FfiReflectCore:

    // Keyed by impl name. Only a failed construction is recorded: the platform makes that one permanent (a poisoned class on the JVM, a
    // half-built module on JS), where a failed lookup or a browser refusal can change once the runtime does.
    private val constructionFailures = new java.util.concurrent.ConcurrentHashMap[String, Throwable]()

    /** Invoke the platform lookup and return the instance, or throw [[FfiLoadError.ImplNotFound]] with the platform not-found error
      * message. A construction that failed once rethrows that failure, unwrapped from `ExceptionInInitializerError`.
      */
    def instantiate(
        implName: String,
        traitFqn: String,
        lookup: String => Option[() => AnyRef],
        notFoundErr: (String, String) => String
    ): AnyRef =
        Maybe(constructionFailures.get(implName)).foreach(failure => throw failure)
        lookup(implName) match
            case Some(ctor) =>
                try ctor()
                catch
                    case e: VirtualMachineError => throw e
                    case e: Throwable           =>
                        val failure = e match
                            case e: ExceptionInInitializerError if e.getCause ne null => e.getCause
                            case e                                                    => e
                        discard(constructionFailures.putIfAbsent(implName, failure))
                        throw constructionFailures.get(implName)
            case None => throw new FfiLoadError.ImplNotFound(traitFqn, notFoundErr(implName, traitFqn), null)
        end match
    end instantiate
end FfiReflectCore
