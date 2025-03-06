package kyo.internal

private[internal] class OSSignalPlatformSpecific:
    val handle: OSSignal.Handler = OSSignal.Handler.Noop
