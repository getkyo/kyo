package kyo.ai

import kyo.*
import kyo.ai.Context.*
import scala.annotation.tailrec

/** Counts real tokens for each text against a specific model's tokenizer.
  *
  * `count` is effectful so ONE trait backs both an offline tokenizer (the pure-Scala tiktoken,
  * which lifts its local counts with no I/O) and a provider count endpoint (Anthropic's
  * `count_tokens`, Gemini's `countTokens`, which issue one count request per distinct string). The
  * completion provider's default tokenizer is chosen by `Completion.defaultTokenizer`; a user
  * overrides it via `kyo.ai.Config.tokenizer`. The count is the COMPLETION model's tokenization
  * (the window the compactor manages), never the embedding model's.
  */
trait Tokenizer:

    /** The real token count of each input text, in input order. Offline tokenizers never fail; the
      * endpoint-backed defaults may fail transport (`HttpException`) or the typed `AIGenException`
      * leaves (`AIMissingApiKeyException`, `AIDecodeException`).
      */
    def count(texts: Chunk[String])(using Frame): Chunk[Int] < (LLM & Async & Abort[HttpException | AIGenException])

    /** Whether this tokenizer's `count` already frames each input as a message, so its result already
      * includes the provider's per-message envelope (role/formatting framing). An offline tokenizer
      * counts raw text only and leaves this false, so `Tokenizer.internal.countMessages` adds
      * `perMessageEnvelope` to bring the offline stamp up to endpoint parity. A provider count-endpoint
      * tokenizer, whose count POSTs each string as a one-message body and returns an already-inclusive
      * total, overrides this to true so the envelope is never added twice. INTERNAL capability, not part
      * of the public surface; a user's custom Tokenizer inherits false (its raw counts get the envelope).
      */
    private[kyo] def includesMessageEnvelope: Boolean = false

end Tokenizer

object Tokenizer:

    /** The tiktoken encoding selector for `Tokenizer.tiktoken`. `O200kBase` is the bundled default
      * (the GPT-4o/o200k vocabulary); `Cl100kBase` is the alternate encoding.
      */
    enum Encoding derives CanEqual:
        case O200kBase, Cl100kBase

    /** The built-in pure-Scala offline tiktoken tokenizer for `encoding`: a byte-level
      * BPE over a bundled rank table, cross-platform (JVM/JS/Native/Wasm), no JVM-only library and no
      * regex. Offline counting is a pure local call, so its `count` never touches the `Async`/`Abort`
      * capabilities the trait row permits.
      */
    def tiktoken(encoding: Encoding): Tokenizer = internal.Tiktoken(encoding)

    private[kyo] object internal:

        /** The per-message framing tokens a provider spends on role/formatting that raw-text tiktoken
          * does not see (the old heuristic's +4). OFFLINE-ONLY: `countMessages` adds it solely for a
          * tokenizer whose count is NOT already envelope-inclusive (offline tiktoken and user custom
          * tokenizers, `includesMessageEnvelope == false`), to bring that offline stamp UP to the
          * count-endpoint providers' envelope-inclusive parity. It is NEVER stacked on
          * a count-endpoint total, which already includes request framing. A real per-message cost in
          * the overflow direction, not a fudge multiplier.
          */
        val perMessageEnvelope: Int = 4

        /** A fixed per-image surcharge on the conservative (overflow) side of what providers bill for
          * vision content (roughly 1-2k tokens per image), so an image message is never counted as
          * zero. Text tiktoken counts text only; this is added per image message.
          */
        val imageSurcharge: Int = 2000

        /** The text a message contributes to its token count: content plus, for an assistant message,
          * its tool-call argument JSON (the bytes the provider actually bills).
          */
        def messageText(message: Message): String =
            message match
                case AssistantMessage(content, calls, _, _) =>
                    if calls.isEmpty then content
                    else content + " " + calls.map(_.arguments).mkString(" ")
                case other => other.content

        /** Stamps each message's real token count: the tokenizer's count of its text, plus the
          * per-message envelope for an offline tokenizer (a count-endpoint total is already
          * envelope-inclusive, so the envelope is skipped there, `includesMessageEnvelope`), plus the
          * image surcharge for an image-bearing user message (added on BOTH paths, since the endpoint
          * sees text-only via `messageText`). This is the enrichment token-stamp's counting core; the
          * seam calls it over the new-message suffix. Batched through one `count`
          * call so an endpoint tokenizer issues one request per distinct text.
          */
        def countMessages(tokenizer: Tokenizer, messages: Chunk[Message])(using
            Frame
        ): Chunk[Int] < (LLM & Async & Abort[HttpException | AIGenException]) =
            val envelope = if tokenizer.includesMessageEnvelope then 0 else perMessageEnvelope
            tokenizer.count(messages.map(messageText)).map { base =>
                messages.zip(base).map { (message, textTokens) =>
                    val imageTokens = message match
                        case UserMessage(_, image, _, _) => if image.isDefined then imageSurcharge else 0
                        case _                           => 0
                    textTokens + envelope + imageTokens
                }
            }
        end countMessages

        export kyo.ai.tokenizer.Ranks
        // The tiktoken implementation and its rank-table type live in kyo.ai.tokenizer, the impl
        // package. Re-exported so every Tokenizer.internal.<name> reference resolves unchanged.
        export kyo.ai.tokenizer.Tiktoken

    end internal
end Tokenizer
