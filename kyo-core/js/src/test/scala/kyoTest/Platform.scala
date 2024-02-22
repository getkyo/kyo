package kyoTest

object Platform:
    def executionContext        = scala.scalajs.concurrent.JSExecutionContext.queue
    def isJVM: Boolean          = false
    def isJS: Boolean           = true
    def isDebugEnabled: Boolean = false
end Platform
