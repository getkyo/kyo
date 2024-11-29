package kyo.internal

import kyo.Log

trait LogPlatformSpecific:
    val live: Log = Log(Log.Unsafe.ConsoleLogger("kyo.logs", Log.Level.debug))
