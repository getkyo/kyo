package kyo.internal

object OSSignalTestPlatform:
    def send(signal: String): Unit =
        sun.misc.Signal.raise(new sun.misc.Signal(signal))
end OSSignalTestPlatform
