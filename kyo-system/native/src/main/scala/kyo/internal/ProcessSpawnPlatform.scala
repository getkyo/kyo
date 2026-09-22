package kyo.internal

import java.lang.Process as JProcess
import java.lang.ProcessBuilder as JProcessBuilder

/** Starts a child process with no other spawn from this process in flight at the same time.
  *
  * Scala Native's `ProcessBuilder` creates a child's pipes with `pipe`, without `O_CLOEXEC`, and its spawn closes only that child's own
  * pipe ends in the child. A child spawned by another thread between this spawn's `pipe` and its `posix_spawn` therefore inherits these
  * pipe ends, and a read of this child's output never reaches EOF while that other child lives.
  *
  * The section held is the builder's setup and the spawn itself, microseconds, never a wait on the child.
  */
private[kyo] object ProcessSpawnPlatform:

    private val lock = new Object

    def start(builder: JProcessBuilder): JProcess = lock.synchronized(builder.start())

end ProcessSpawnPlatform
