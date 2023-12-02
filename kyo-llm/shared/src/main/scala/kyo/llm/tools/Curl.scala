package kyo.llm.tools

import kyo._
import kyo.requests._
import sttp.client3._
import sttp.model._
import sttp.client3.ziojson._
import kyo.logs._
import zio.json._
import scala.concurrent.duration._
import kyo.llm.ais.AIs

object Curl {

  import internal._

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

  case class Input(
      method: String,
      contentType: String,
      url: String,
      headers: Option[Map[String, String]],
      data: Option[String],
      followRedirects: Boolean,
      timeoutSeconds: Option[Int]
  )

  def tool(f: Methods => Methods): Tool[Input, String] =
    tool(f(Methods.init))

  def tool(methods: Methods): Tool[Input, String] = {
    val allow = methods.s
    Tools.init[Input, String](
        s"http_curl",
        s"Performs an HTTP request. Allowed methods: ${allow.mkString(", ")}",
        params => s"Performing HTTP request: $params"
    ) { (ai, params) =>
      if (!allow.contains(params.method)) {
        AIs.fail(s"Method not allowed: ${params.method}. Allowed: ${allow.mkString(", ")}")
      } else {
        for {
          _ <- Logs.debug(params.toJsonPretty)
          res <- Requests(
              _.method(Method(params.method), uri"${params.url}")
                .contentType(params.contentType)
                .headers(params.headers.getOrElse(Map.empty))
                .body(params.data.getOrElse(""))
                .followRedirects(params.followRedirects)
                .readTimeout(params.timeoutSeconds.getOrElse(0).seconds)
          )
          _ <- Logs.debug(res)
        } yield res
      }
    }
  }

  object internal {
    implicit val inputDecoder: JsonDecoder[Input] = DeriveJsonDecoder.gen[Input]
    implicit val inputEncoder: JsonEncoder[Input] = DeriveJsonEncoder.gen[Input]
  }
}
