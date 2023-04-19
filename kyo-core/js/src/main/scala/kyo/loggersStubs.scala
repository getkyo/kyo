package org.slf4j

object LoggerFactory {
  def getLogger(name: String): Logger =
    Logger(name)
  def getLogger(cls: Class[_]): Logger =
    Logger(cls.getName())
}

case class Logger(name: String) {

  def isTraceEnabled = true
  def isDebugEnabled = true
  def isInfoEnabled  = true
  def isWarnEnabled  = true
  def isErrorEnabled = true

  def trace(msg: => String) =
    println(s"TRACE $name: $msg")

  def debug(msg: => String) =
    println(s"DEBUG $name: $msg")

  def info(msg: => String) =
    println(s"INFO $name: $msg")

  def warn(msg: => String) =
    println(s"WARN $name: $msg")

  def error(msg: => String) =
    println(s"ERROR $name: $msg")

  def trace(msg: => String, t: Throwable) =
    println(s"TRACE $name: $msg")

  def debug(msg: => String, t: Throwable) =
    println(s"DEBUG $name: $msg $t")

  def info(msg: => String, t: Throwable) =
    println(s"INFO $name: $msg $t")

  def warn(msg: => String, t: Throwable) =
    println(s"WARN $name: $msg $t")

  def error(msg: => String, t: Throwable) =
    println(s"ERROR $name: $msg $t")
}
