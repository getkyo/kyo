package kyo.internal

import kyo.*

/** Kills a process together with every process descended from it.
  *
  * The tree is frozen before it is read. A stopped process cannot fork, so a walk repeated until it finds no new pid has the whole tree,
  * and a child of a stopped process that exits stays a zombie holding its pid, so no pid is reused before the kill. Killing the root first
  * would reparent its children to init, where no walk from the root finds them. On a platform that cannot stop a process the walk still
  * runs, and a descendant that forks while the tree is being killed can escape.
  */
private[kyo] object ProcessTree:

    // A SIGKILLed process dies in real time, so the wait for it runs on the live clock: a caller's virtual clock would never wake it.
    private val pollInterval: Duration = 5.millis
    private val maxPolls: Int          = 1000

    def destroy(proc: Process.Unsafe)(using Frame): Unit < Async =
        Sync.Unsafe.defer(proc.liveTreeRoots()).map { roots =>
            if roots.isEmpty then ()
            else
                ProcessTreePlatform.stop(roots).andThen(freeze(roots.toSet, roots)).map { tree =>
                    ProcessTreePlatform.kill(Chunk.from(tree))
                        .andThen(Sync.Unsafe.defer(proc.destroyForcibly()))
                        .andThen(Sync.Unsafe.defer(proc.waitFor().safe.get).unit)
                        .andThen(Sync.Unsafe.defer(proc.pid()))
                        .map(root => awaitGone(tree - root))
                }
        }

    private def freeze(tree: Set[Long], frontier: Chunk[Long])(using Frame): Set[Long] < Async =
        ProcessTreePlatform.children(frontier).map { found =>
            val fresh = found.filterNot(tree.contains)
            if fresh.isEmpty then tree
            else ProcessTreePlatform.stop(fresh).andThen(freeze(tree ++ fresh, fresh))
        }

    private def awaitGone(pids: Set[Long])(using Frame): Unit < Async =
        Loop(Chunk.from(pids), 0) { (left, polls) =>
            Kyo.filter(left)(ProcessTreePlatform.exists).map { still =>
                if still.isEmpty || polls >= maxPolls then Loop.done(())
                else Clock.let(Clock.live)(Async.sleep(pollInterval)).andThen(Loop.continue(still, polls + 1))
            }
        }

end ProcessTree
