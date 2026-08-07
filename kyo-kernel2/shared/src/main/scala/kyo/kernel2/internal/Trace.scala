package kyo.kernel2.internal

/** Placeholder for the diagnostics trace carried across fork boundaries.
  *
  * The current kernel snapshots the running fiber's frame trace at detach points and restores it on the other side. kernel2's trace
  * machinery lands with the trace round; until then this type keeps the boundary signatures (Isolate.internal.runDetached) in the
  * current kernel's shape while carrying nothing.
  */
final private[kyo] class Trace private[kyo] ()

private[kyo] object Trace:
    private[kyo] val empty = new Trace
