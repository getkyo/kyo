package kyo.internal

import kyo.*
import scala.jdk.CollectionConverters.*

/** The process-tree operations [[ProcessTree]] composes, on `java.lang.ProcessHandle`.
  *
  * `ProcessHandle` reads children and kills, but has no way to stop a process, so the stop runs the host's `kill` utility. Where there is
  * none (Windows), the tree is killed unstopped.
  */
private[kyo] object ProcessTreePlatform:

    def children(parents: Chunk[Long])(using Frame): Chunk[Long] < Sync =
        Sync.defer {
            parents.flatMap { parent =>
                ProcessHandle.of(parent).map(handle => Chunk.from(handle.children().iterator().asScala.map(_.pid()).toSeq))
                    .orElse(Chunk.empty[Long])
            }
        }

    def stop(pids: Chunk[Long])(using Frame): Unit < Async =
        if pids.isEmpty then ()
        else Abort.run[CommandException](Command(("kill" +: "-STOP" +: pids.map(_.toString).toSeq)*).waitFor).unit

    def kill(pids: Chunk[Long])(using Frame): Unit < Sync =
        Sync.defer(pids.foreach(pid => ProcessHandle.of(pid).ifPresent(handle => discard(handle.destroyForcibly()))))

    def exists(pid: Long)(using Frame): Boolean < Sync =
        Sync.defer(ProcessHandle.of(pid).filter(_.isAlive).isPresent)

    // `ProcessHandle` has no notion of a group, so a group is read through the C library, as it was started.
    def groupExists(group: Long)(using Frame): Boolean < Sync =
        Sync.defer(ProcessGroupPlatform.groupExists(group))

end ProcessTreePlatform
