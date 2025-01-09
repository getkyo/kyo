package kyo.scheduler.util

abstract class Singleton[A <: AnyRef] {

    protected def init(): A

    lazy val get = init()
}
