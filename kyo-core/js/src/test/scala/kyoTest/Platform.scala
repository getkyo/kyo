package kyoTest

object Platform:
    def executionContext        = org.scalajs.macrotaskexecutor.MacrotaskExecutor
    def isJVM: Boolean          = false
    def isJS: Boolean           = true
    def isDebugEnabled: Boolean = false
end Platform
