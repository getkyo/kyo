package prodm

import scala.scalanative.unsafe.*

@extern
object ProdM:
    def prodm_hypot(a: Double, b: Double): Double = extern
