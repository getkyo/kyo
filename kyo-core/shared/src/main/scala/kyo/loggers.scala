package kyo

import org.slf4j.LoggerFactory

import ios._

object loggers {

  object Loggers {
    def init(name: String): Logger =
      new Logger(LoggerFactory.getLogger(name))
    def init(cls: Class[_]): Logger =
      new Logger(LoggerFactory.getLogger(cls))
  }

  class Logger private[loggers] (private val l: org.slf4j.Logger) extends AnyVal {

    /*inline*/
    def trace( /*inline*/ msg: => String): Unit > IOs =
      IOs(if (l.isTraceEnabled) l.trace(msg))

    /*inline*/
    def debug( /*inline*/ msg: => String): Unit > IOs =
      IOs(if (l.isDebugEnabled) l.debug(msg))

    /*inline*/
    def info( /*inline*/ msg: => String): Unit > IOs =
      IOs(if (l.isInfoEnabled) l.info(msg))

    /*inline*/
    def warn( /*inline*/ msg: => String): Unit > IOs =
      IOs(if (l.isWarnEnabled) l.warn(msg))

    /*inline*/
    def error( /*inline*/ msg: => String): Unit > IOs =
      IOs(if (l.isErrorEnabled) l.error(msg))

    /*inline*/
    def trace( /*inline*/ msg: => String, t: Throwable): Unit > IOs =
      IOs(if (l.isTraceEnabled) l.trace(msg, t))

    /*inline*/
    def debug( /*inline*/ msg: => String, t: Throwable): Unit > IOs =
      IOs(if (l.isDebugEnabled) l.debug(msg, t))

    /*inline*/
    def info( /*inline*/ msg: => String, t: Throwable): Unit > IOs =
      IOs(if (l.isInfoEnabled) l.info(msg, t))

    /*inline*/
    def warn( /*inline*/ msg: => String, t: Throwable): Unit > IOs =
      IOs(if (l.isWarnEnabled) l.warn(msg, t))

    /*inline*/
    def error( /*inline*/ msg: => String, t: Throwable): Unit > IOs =
      IOs(if (l.isErrorEnabled) l.error(msg, t))
  }
}
