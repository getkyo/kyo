package kyo.concurrent

sealed trait Access

object Access {
  case object Mpmc extends Access
  case object Mpsc extends Access
  case object Spmc extends Access
  case object Spsc extends Access
}
