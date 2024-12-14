package kyo.internal

private[internal] class SignalPlatformSpecific:
    val handle: Signal.Handler = Signal.Handler.Noop
