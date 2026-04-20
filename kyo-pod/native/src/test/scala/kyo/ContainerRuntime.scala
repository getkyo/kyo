package kyo

object ContainerRuntime:
    lazy val hasPodman: Boolean                = false
    lazy val hasDocker: Boolean                = false
    lazy val available: Seq[String]            = Seq.empty
    def isPodman: Boolean                      = false
    def isDocker: Boolean                      = false
    def findSocket(rt: String): Option[String] = None
end ContainerRuntime
