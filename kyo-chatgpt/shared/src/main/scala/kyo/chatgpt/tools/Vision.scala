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

object Vision {

  case class Input(
      @desc("A description of the environment in which the image is displayed. " +
        "This includes the webpage or application interface, nearby visual elements, " +
        "and surrounding textual content, which may provide additional insight or " +
        "relevance to the image in question.")
      environment: String,
      @desc("The question the AI needs to answer regarding the provided image.")
      question: String,
      @desc("The URL where the image can be found.")
      imageUrl: String
  )

  val tool = Tools.init[Input, String](
      "vision_interpret_image",
      "interprets the contents of the provided image",
      task => s"Using GPT Vision to interpret image: ${task.question}"
  ) { (_, task) =>
    Configs.let(_.model(Model.gpt4_vision).maxTokens(Some(4000))) {
      Requests[Array[Byte]](
          _.get(uri"${task.imageUrl}")
            .response(asByteArray)
      ).map { bytes =>
        val payload = encodeImage(bytes)
        AIs.init.map { ai =>
          ai.userMessage(
              p"""
                Context: ${task.environment}
                Question: ${task.question}
              """,
              s"data:image/jpeg;base64,$payload" :: Nil
          ).andThen(ai.ask)
        }
      }
    }
  }

  private def encodeImage(bytes: Array[Byte]) = {
    val inputStream = new ByteArrayInputStream(bytes)
    val image       = ImageIO.read(inputStream)
    val jpegStream  = new ByteArrayOutputStream()
    ImageIO.write(image, "jpg", jpegStream)
    Base64.getEncoder.encodeToString(jpegStream.toByteArray())
  }
}
