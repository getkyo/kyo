package kyo

import org.slf4j.LoggerFactory

import frames._
import ios._

object loggers {

  opaque type Logger = org.slf4j.Logger

  object Loggers {
    def init(name: String): Logger =
      LoggerFactory.getLogger(name)
    def init(cls: Class[_]): Logger =
      LoggerFactory.getLogger(cls)
  }

  extension (l: Logger) {

    /*inline(3)*/
    def trace( /*inline(3)*/ msg: => String): Unit > IOs =
      IOs(if (l.isTraceEnabled) l.trace(msg))

    /*inline(3)*/
    def debug( /*inline(3)*/ msg: => String): Unit > IOs =
      IOs(if (l.isDebugEnabled) l.debug(msg))

    /*inline(3)*/
    def info( /*inline(3)*/ msg: => String): Unit > IOs =
      IOs(if (l.isInfoEnabled) l.info(msg))

    /*inline(3)*/
    def warn( /*inline(3)*/ msg: => String): Unit > IOs =
      IOs(if (l.isWarnEnabled) l.warn(msg))

    /*inline(3)*/
    def error( /*inline(3)*/ msg: => String): Unit > IOs =
      IOs(if (l.isErrorEnabled) l.error(msg))

    /*inline(3)*/
    def trace( /*inline(3)*/ msg: => String, t: Throwable): Unit > IOs =
      IOs(if (l.isTraceEnabled) l.trace(msg, t))

    /*inline(3)*/
    def debug( /*inline(3)*/ msg: => String, t: Throwable): Unit > IOs =
      IOs(if (l.isDebugEnabled) l.debug(msg, t))

    /*inline(3)*/
    def info( /*inline(3)*/ msg: => String, t: Throwable): Unit > IOs =
      IOs(if (l.isInfoEnabled) l.info(msg, t))

    /*inline(3)*/
    def warn( /*inline(3)*/ msg: => String, t: Throwable): Unit > IOs =
      IOs(if (l.isWarnEnabled) l.warn(msg, t))

    /*inline(3)*/
    def error( /*inline(3)*/ msg: => String, t: Throwable): Unit > IOs =
      IOs(if (l.isErrorEnabled) l.error(msg, t))
  }
}
