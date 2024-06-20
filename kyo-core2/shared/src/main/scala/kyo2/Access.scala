package kyo2

sealed abstract class Access derives CanEqual

object Access:
    case object Mpmc extends Access
    case object Mpsc extends Access
    case object Spmc extends Access
    case object Spsc extends Access
end Access
