package kyo.chatgpt

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.EncodingType

object tokens {

  private val enc = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);

  object Tokens {
    def apply(s: String): Int =
      enc.encode(s).size()
  }
}
