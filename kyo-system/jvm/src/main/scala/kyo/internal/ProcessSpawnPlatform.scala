package kyo.internal

import java.lang.Process as JProcess
import java.lang.ProcessBuilder as JProcessBuilder

/** Starts a child process.
  *
  * Nothing to guard on the JVM: its pipes carry `FD_CLOEXEC`, so a child spawned concurrently by another thread inherits none of them.
  * The Scala Native counterpart serializes spawns for that reason.
  */
private[kyo] object ProcessSpawnPlatform:

    def start(builder: JProcessBuilder): JProcess = builder.start()

end ProcessSpawnPlatform
