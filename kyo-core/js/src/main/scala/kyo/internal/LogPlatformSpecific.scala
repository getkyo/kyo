package kyo
trait logsPlatformSpecific:
    val unsafe: Logs.Unsafe = Logs.Unsafe.ConsoleLogger("kyo.logs")
