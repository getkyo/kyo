package kyo.test.runner.internal

/** Hang-diagnostics thread dump. Scala Native exposes no portable all-thread stack capture, so this is a
  * placeholder; the hang diagnostics still emit the cross-platform `kyo.internal.Diagnostics` snapshot.
  */
private[runner] object ThreadDump:
    def render(): String = "\n(thread dump unavailable on Scala Native)\n"
end ThreadDump
