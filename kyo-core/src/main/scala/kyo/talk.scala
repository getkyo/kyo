package kyo

import java.util.concurrent.TimeoutException
import scala.util.Try
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object talk extends App {

  import core._
  import options._
  import tries._
  import lists._
  import futures._

  val ex = new Exception

  for {
    v1 <- Option(1) > Options
    v2 <- v1 + 1
    v3 <- Try(10 / v2) > Tries
  } yield v3


}
