package kyo.scheduler.util

abstract class Singleton[A <: AnyRef](init: () => A) {

    lazy val get = init()
}
