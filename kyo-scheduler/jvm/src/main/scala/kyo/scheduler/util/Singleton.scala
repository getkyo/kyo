package kyo.scheduler.util

/** JVM-wide singleton that works across multiple classloaders.
  *
  * Regular singleton objects in Scala/Java are only unique within a single classloader. When multiple classloaders are involved (e.g. in
  * application servers or plugin systems), each classloader creates its own instance, breaking singleton semantics.
  *
  * This is particularly important for systems that make adaptive decisions based on global state. For example, Kyo's scheduler dynamically
  * adjusts its behavior based on system-wide metrics like thread scheduling delays. Having multiple scheduler instances would lead to:
  *   - Conflicting thread pool adjustments
  *   - Inaccurate system load measurements
  *   - Competing admission control decisions
  *   - Overall degraded performance
  *
  * This class ensures proper singleton semantics by:
  *   - Using System.properties as JVM-wide storage
  *   - Synchronizing on SystemClassLoader to ensure global atomic initialization
  *   - Caching the instance locally for fast access
  *
  * @tparam A
  *   The type of the singleton instance.
  * @param init
  *   A function that creates the singleton instance when needed. This will be called at most once per JVM, regardless of the number of
  *   classloaders.
  */
abstract class Singleton[A <: AnyRef] {

    @volatile private var instance: A = null.asInstanceOf[A]

    protected def init(): A

    def get: A = {

        // Fast path - check local cache first
        val cached = instance
        if (cached ne null) return cached

        // Slow path - instance not cached, need to coordinate across classloaders
        ClassLoader.getSystemClassLoader.synchronized {

            // Must check again after acquiring lock (double-check pattern)
            val doubleCheck = instance
            if (doubleCheck ne null) return doubleCheck

            // Try to get instance from global storage
            val sysProps = System.getProperties
            val key      = getClass.getName // Singleton object's class name as key

            val existing = sysProps.get(key).asInstanceOf[A]
            val result =
                if (existing ne null) {
                    // Another classloader created the instance, use it
                    existing
                } else {
                    // We're first - create and store globally
                    val created = init()
                    sysProps.put(key, created)
                    created
                }

            // Cache for future fast access from this classloader
            instance = result
            result
        }
    }
}
