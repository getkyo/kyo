package kyoTest

object Platform {
  def executionContext = scala.concurrent.ExecutionContext.global
  def isJVM: Boolean   = true
  def isJS: Boolean    = false
}
