package kyo.internal

import kyo.Log

trait LogPlatformSpecific:
    val unsafe: Log.Unsafe = Log.Unsafe.ConsoleLogger("kyo.logs")
