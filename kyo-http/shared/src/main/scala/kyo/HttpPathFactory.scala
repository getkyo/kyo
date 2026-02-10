package kyo

import java.util.UUID

/** Factory methods for HttpPath captures, defined outside the opaque type boundary so that `transparent inline` + macro can resolve
  * `HttpPath[Any]` as an applied type (not the underlying union).
  */
private[kyo] trait HttpPathFactory:

    transparent inline def int(inline name: String): Any =
        ${ internal.CaptureNameMacro.retype[HttpPath[Any], Int]('{ HttpPath.mkCapture(name, _.toInt) }, '{ name }) }

    transparent inline def long(inline name: String): Any =
        ${ internal.CaptureNameMacro.retype[HttpPath[Any], Long]('{ HttpPath.mkCapture(name, _.toLong) }, '{ name }) }

    transparent inline def string(inline name: String): Any =
        ${ internal.CaptureNameMacro.retype[HttpPath[Any], String]('{ HttpPath.mkCapture(name, identity) }, '{ name }) }

    transparent inline def uuid(inline name: String): Any =
        ${ internal.CaptureNameMacro.retype[HttpPath[Any], UUID]('{ HttpPath.mkCapture(name, UUID.fromString) }, '{ name }) }

    transparent inline def boolean(inline name: String): Any =
        ${ internal.CaptureNameMacro.retype[HttpPath[Any], Boolean]('{ HttpPath.mkCapture(name, _.toBoolean) }, '{ name }) }

end HttpPathFactory
