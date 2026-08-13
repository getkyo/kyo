package kyo.kernel.internal

/** Safepoint's thread operations on JS and Wasm.
  *
  * Both platforms are single-threaded, so there is exactly one thread handle, its id is
  * constant, and it is always alive. A `Safepoint.stop` request from another thread
  * cannot occur here by construction: there is no other thread to issue one.
  */
private[kernel] object Threads:

    final class Handle private[Threads] ()

    private val instance: Handle = new Handle

    inline def current(): Handle = instance

    inline def id(handle: Handle): Long = 0L

    inline def isAlive(handle: Handle): Boolean = true

end Threads
