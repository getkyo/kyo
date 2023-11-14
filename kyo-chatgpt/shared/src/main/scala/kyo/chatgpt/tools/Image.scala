package kyo.chatgpt.tools

import kyo._
import kyo.requests._
import kyo.chatgpt.ais._
import kyo.chatgpt.contexts._
import kyo.chatgpt.configs._
import kyo.lists.Lists
import java.util.Base64
import sttp.client3._
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import java.util.Base64
import sttp.client3._
import sttp.client3.ziojson._
import zio.json._
import scala.concurrent.duration.Duration
import kyo.loggers.Loggers

object Image {

  private val logger = Loggers.init("kyo.chatgpt.tools.Image")

  import internal._

  case class Input(
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

  case class Output(
      imageUrl: String,
      revisedPrompt: String
  )

  val createTool =
    Tools.init[Input, Output](
        "image_create",
        "Generates an image via DALL-E",
        task => s"Generating an image via DALL-E: ${task.prompt}"
    ) { (_, task) =>
      val req = Request(
          task.prompt,
          if (task.hdQuality) "hd" else "standard",
          task.size,
          task.style
      )
      for {
        key    <- Configs.apiKey
        config <- Configs.get
        _      <- logger.debug(req.toJsonPretty)
        resp <- Requests[Response](
            _.contentType("application/json")
              .header("Authorization", s"Bearer $key")
              .post(uri"${config.apiUrl}/v1/images/generations")
              .body(req)
              .readTimeout(Duration.Inf)
              .response(asJson[Response])
        )
        _ <- logger.debug(resp.toJsonPretty)
        r <- resp.data.headOption.map(r => Output(r.url, r.revised_prompt))
          .getOrElse(AIs.fail[Output]("Can't find the generated image URL."))
      } yield r
    }

  object internal {
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
