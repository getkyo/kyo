package kyo

import org.slf4j.LoggerFactory

import core._
import frames._
import ios._

object logs {

  opaque type Logger = org.slf4j.Logger

  object Logger {
    def apply(name: String): Logger =
      LoggerFactory.getLogger(name)
    def apply(cls: Class[_]): Logger =
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
  }
}
