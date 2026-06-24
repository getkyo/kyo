package kyo.test.runner.internal

/** Hang-diagnostics thread dump. JS and Wasm are single-threaded with no thread enumeration, so this is a
  * placeholder; the hang diagnostics still emit the cross-platform `kyo.internal.Diagnostics` snapshot.
  */
private[runner] object ThreadDump:
    def render(): String = "\n(thread dump unavailable on JS)\n"
end ThreadDump
