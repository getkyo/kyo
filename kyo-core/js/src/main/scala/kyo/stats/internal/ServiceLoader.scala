package java.util

/** Scala.js shim for `java.util.ServiceLoader` that delegates to [[kyo.stats.internal.JSServiceLoaderRegistry]]. */
class ServiceLoader[A] private (providers: ArrayList[A]):
    def iterator(): Iterator[A] =
        providers.iterator()

object ServiceLoader:
    def load[A](cls: Class[A]): ServiceLoader[A] =
        val providers  = new ArrayList[A]()
        val registered = kyo.stats.internal.JSServiceLoaderRegistry.get(cls.getName)
        if registered != null then
            val it = registered.iterator()
            while it.hasNext() do
                val _ = providers.add(it.next().asInstanceOf[A])
        end if
        new ServiceLoader(providers)
    end load
end ServiceLoader
