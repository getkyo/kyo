package kyo.internal

private[internal] class OsSignalPlatformSpecific:
    val handle: OsSignal.Handler = OsSignal.Handler.Noop
