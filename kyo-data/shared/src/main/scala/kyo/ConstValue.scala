package kyo

import scala.compiletime.constValue as scConstValue

opaque type ConstValue[A] <: A = A

object ConstValue:
    inline given [A]: ConstValue[A] = scConstValue[A]
