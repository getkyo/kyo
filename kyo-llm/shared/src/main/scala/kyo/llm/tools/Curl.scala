package kyo.llm.tools

import kyo._
import kyo.llm._

import sttp.client3._
import sttp.client3.ziojson._
import sttp.model._
import zio.json._

import scala.concurrent.duration._

class Curl(methods: Curl.Methods) extends Tool {
  private val allow = methods.s

  case class In(
      method: String,
      contentType: String,
      url: String,
      headers: Option[Map[String, String]],
      data: Option[String],
      followRedirects: Boolean,
      timeoutSeconds: Option[Int]
  )
  type Out = String

  val info =
    Info(
        s"http_curl",
        s"Performs an HTTP request. Allowed methods: ${allow.mkString(", ")}"
    )

  def run(input: In) =
    if (!allow.contains(input.method)) {
      IOs.fail(s"Method not allowed: ${input.method}. Allowed: ${allow.mkString(", ")}")
    } else {
      given inputDecoder: JsonDecoder[In] = DeriveJsonDecoder.gen[In]
      given inputEncoder: JsonEncoder[In] = DeriveJsonEncoder.gen[In]
      for {
        _ <- Logs.debug(input.toJsonPretty)
        res <- Requests(
            _.method(Method(input.method), uri"${input.url}")
              .contentType(input.contentType)
              .headers(input.headers.getOrElse(Map.empty))
              .body(input.data.getOrElse(""))
              .followRedirects(input.followRedirects)
              .readTimeout(input.timeoutSeconds.getOrElse(0).seconds)
        )
        _ <- Logs.debug(res)
      } yield res
    }
}

object Curl {

  def apply(f: Methods => Methods): Tool =
    apply(f(Methods.init))

  def apply(methods: Methods): Tool =
    new Curl(methods)

  case class Methods(s: Set[String]) {
    def get: Methods     = Methods(s + "GET")
    def head: Methods    = Methods(s + "HEAD")
    def post: Methods    = Methods(s + "POST")
    def put: Methods     = Methods(s + "PUT")
    def delete: Methods  = Methods(s + "DELETE")
    def options: Methods = Methods(s + "OPTIONS")
    def patch: Methods   = Methods(s + "PATCH")
    def reads: Methods =
      Methods(Set(get, head, options).flatMap(_.s))
    def writes: Methods =
      Methods(Set(post, put, delete, patch).flatMap(_.s))
    def all: Methods =
      Methods(Set(reads, writes).flatMap(_.s))
  }

  object Methods {
    val init = Methods(Set.empty)
  }

}
