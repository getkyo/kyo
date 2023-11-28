package kyo.llm.tools

import kyo._
import kyo.requests._
import kyo.llm.ais._
import kyo.llm.contexts._
import kyo.llm.configs._
import kyo.lists.Lists
import java.util.Base64
import sttp.client3._
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import java.util.Base64
import java.awt.image.BufferedImage

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
        if (payload.isEmpty) {
          AIs.fail(s"Failed to encode image at ${task.imageUrl}")
        } else {
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
  }

  private def encodeImage(bytes: Array[Byte]) = {
    val inputStream   = new ByteArrayInputStream(bytes)
    val originalImage = ImageIO.read(inputStream)

    // Convert image to a type compatible with JPEG (if needed)
    val image =
      if (
          originalImage.getType == BufferedImage.TYPE_INT_ARGB ||
          originalImage.getType == BufferedImage.TYPE_4BYTE_ABGR
      ) {
        val convertedImg = new BufferedImage(
            originalImage.getWidth,
            originalImage.getHeight,
            BufferedImage.TYPE_INT_RGB
        )
        val g = convertedImg.createGraphics()
        g.drawImage(originalImage, 0, 0, null)
        g.dispose()
        convertedImg
      } else {
        originalImage
      }

    val jpegStream = new ByteArrayOutputStream()
    ImageIO.write(image, "jpg", jpegStream)
    Base64.getEncoder.encodeToString(jpegStream.toByteArray())
  }

}
