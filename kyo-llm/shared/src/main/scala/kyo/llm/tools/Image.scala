package kyo.llm.tools

import kyo._
import kyo.llm._

import sttp.client3._
import sttp.client3.ziojson._
import zio.json._

import scala.concurrent.duration.Duration

object Image extends Tool {

  import internal._

  case class In(
      @desc("A text description of the desired image")
      prompt: String,
      @desc("""
        The quality of the image that will be generated. hd creates 
        images with finer details and greater consistency across the image.
      """)
      hdQuality: Boolean,
      @desc("Must be one of 1024x1024, 1792x1024, or 1024x1792")
      size: String,
      @desc("""
        The style of the generated images. Must be one of vivid 
        or natural. Vivid causes the model to lean towards generating
        hyper-real and dramatic images. Natural causes the model to produce
        more natural, less hyper-real looking images.
      """)
      style: String
  )

  case class Out(
      imageUrl: String,
      revisedPrompt: String
  )

  val info =
    Info(
        "image_create",
        "Generates an image via DALL-E"
    )

  def run(input: In) = {
    val req = Request(
        input.prompt,
        if (input.hdQuality) "hd" else "standard",
        input.size,
        input.style
    )
    for {
      key    <- Configs.apiKey
      config <- Configs.get
      _      <- Logs.debug(req.toJsonPretty)
      resp <- Requests[Response](
          _.contentType("application/json")
            .header("Authorization", s"Bearer $key")
            .post(uri"${config.apiUrl}/v1/images/generations")
            .body(req)
            .readTimeout(Duration.Inf)
            .response(asJson[Response])
      )
      _ <- Logs.debug(resp.toJsonPretty)
      r <- resp.data.headOption.map(r => Out(r.url, r.revised_prompt))
        .getOrElse(AIs.fail[Out]("Can't find the generated image URL."))
    } yield r
  }

  private object internal {
    case class Data(
        url: String,
        revised_prompt: String
    )
    case class Request(
        prompt: String,
        quality: String,
        size: String,
        style: String,
        n: Int = 1,
        model: String = "dall-e-3"
    )
    case class Response(
        created: Int,
        data: List[Data]
    )

    implicit val requestEncoder: JsonEncoder[Request]   = DeriveJsonEncoder.gen[Request]
    implicit val dataDecoder: JsonDecoder[Data]         = DeriveJsonDecoder.gen[Data]
    implicit val dataEncoder: JsonEncoder[Data]         = DeriveJsonEncoder.gen[Data]
    implicit val responseEncoder: JsonEncoder[Response] = DeriveJsonEncoder.gen[Response]
    implicit val responseDecoder: JsonDecoder[Response] = DeriveJsonDecoder.gen[Response]
  }
}
