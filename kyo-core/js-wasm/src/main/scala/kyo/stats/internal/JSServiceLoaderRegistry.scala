package kyo.stats.internal

import kyo.Chunk

/** In-memory service registry for Scala.js, where `META-INF/services` file-based discovery is unavailable.
  *
  * Modules call `register` at initialization time to make their factory instances discoverable. The companion `java.util.ServiceLoader`
  * shim queries this registry to provide the same API surface as the JVM.
  *
  * Public because the `java.util.ServiceLoader` shim lives in the `java.util` package, outside `kyo`'s scope.
  */
object JSServiceLoaderRegistry:
    private var registry = Map.empty[String, Chunk[Any]]

    def register[A](cls: Class[A], provider: A): Unit =
        val name     = cls.getName
        val existing = registry.getOrElse(name, Chunk.empty)
        registry = registry.updated(name, existing.append(provider))
    end register

    def get(name: String): Chunk[Any] =
        registry.getOrElse(name, Chunk.empty)
end JSServiceLoaderRegistry
