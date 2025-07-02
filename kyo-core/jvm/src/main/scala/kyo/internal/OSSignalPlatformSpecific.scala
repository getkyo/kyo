package kyo.internal

import OsSignal.Handler
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.logging.Logger
import kyo.Result

/** This class provides a jvm-specific implementation of signal handling. It uses reflection to install signal handlers as they may not be
  * available on all implementations of the JVM.
  */
private[internal] class OsSignalPlatformSpecific:
    private val logger = Logger.getLogger("kyo.internal.Signal")
    val handle: Handler = {
        for
            signalClass        <- findClass("sun.misc.Signal")
            signalHandlerClass <- findClass("sun.misc.SignalHandler")
            lookup = MethodHandles.lookup()
            constructorHandle  <- initSignalConstructorMethodHandle(lookup, signalClass)
            staticMethodHandle <- initHandleStaticMethodHandle(lookup, signalClass, signalHandlerClass)
        yield SunMisc(signalHandlerClass, constructorHandle, staticMethodHandle)
    }.foldError(
        identity,
        { error =>
            logger.warning(
                s"sun.misc.Signal and sun.misc.SignalHandler are not available on this platform. Defaulting to no-op signal handling implementation. ${error.failureOrPanic}"
            )
            Handler.Noop
        }
    )
    end handle

    final class SunMisc(
        signalHandlerClass: Class[?],
        constructorMethodHandle: MethodHandle,
        staticMethodHandle: MethodHandle
    ) extends Handler:
        override def apply(signal: String, handle: () => Unit): Unit =
            val invocationHandler =
                new InvocationHandler:
                    def invoke(proxy: Any, method: Method, args: Array[AnyRef]): AnyRef =
                        if args.nonEmpty then handle()
                        this

            val proxy = Proxy.newProxyInstance(
                OsSignal.getClass.getClassLoader,
                Array(signalHandlerClass),
                invocationHandler
            )
            try
                val s = constructorMethodHandle.invoke(signal)
                staticMethodHandle.invoke(s, proxy)
            catch
                case e: Throwable =>
                    logger.warning(s"Failed to install signal handler for $signal. ${e.getMessage}")
            end try
        end apply
        override def toString = "Signal.Handler.SunMisc"
    end SunMisc

    private def findClass(name: String): Result[ClassNotFoundException, Class[?]] =
        Result.catching[ClassNotFoundException](Class.forName(name))

    private def initSignalConstructorMethodHandle(
        lookup: MethodHandles.Lookup,
        signalClass: Class[?]
    ): Result[Exception, MethodHandle] =
        Result.catching[Exception] {
            lookup.findConstructor(
                signalClass,
                MethodType.methodType(classOf[Unit], classOf[String])
            )
        }

    private def initHandleStaticMethodHandle(
        lookup: MethodHandles.Lookup,
        signalClass: Class[?],
        signalHandlerClass: Class[?]
    ): Result[Exception, MethodHandle] =
        Result.catching[Exception] {
            lookup.findStatic(
                signalClass,
                "handle",
                MethodType.methodType(signalHandlerClass, signalClass, signalHandlerClass)
            )
        }
    end initHandleStaticMethodHandle
end OsSignalPlatformSpecific
