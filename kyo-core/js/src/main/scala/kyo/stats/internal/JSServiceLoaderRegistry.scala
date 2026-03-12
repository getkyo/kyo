package kyo.stats.internal

/** In-memory service registry for Scala.js, where `META-INF/services` file-based discovery is unavailable.
  *
  * Modules call `register` at initialization time to make their factory instances discoverable. The companion `java.util.ServiceLoader`
  * shim queries this registry to provide the same API surface as the JVM.
  */
object JSServiceLoaderRegistry:
    private val registry = new java.util.HashMap[String, java.util.ArrayList[Any]]()

    def register[A](cls: Class[A], provider: A): Unit =
        var list = registry.get(cls.getName)
        if list == null then
            list = new java.util.ArrayList[Any]()
            val _ = registry.put(cls.getName, list)
        val _ = list.add(provider)
    end register

    def get(name: String): java.util.ArrayList[Any] =
        registry.get(name)
end JSServiceLoaderRegistry
