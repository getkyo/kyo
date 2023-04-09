package kyo

import kyo.ais._
import kyo.aspects._
import kyo.consoles._
import kyo.core._
import kyo.envs._
import kyo.ios._
import kyo.locals._
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.queryparser.classic.QueryParser
import java.nio.file.Paths
import org.apache.lucene.store._
import org.apache.lucene.document._
import org.apache.lucene.index._
import org.apache.lucene.search._
import scala.util.Try

object traits {

  abstract class Trait private[traits] (val ais: Set[AI])
      extends Cut[(AI, String), String, AIs] {
    def apply[S2, S3](v: (AI, String) > S2)(next: ((AI, String)) => String > (S3 | Aspects))
        : String > (AIs | S2 | S3 | Aspects) =
      v {
        case tup @ (ai, msg) =>
          if (ais.contains(ai))
            this(ai, msg)(next(ai, _))
          else
            next(tup)
      }

    def apply[S](
        ai: AI,
        v: String
    )(next: String => String > (S | Aspects)): String > (S | Aspects | AIs)
  }

  object Traits {

    private class Introspection(prompt: String, ais: Set[AI])
        extends Trait(ais) {
      def apply[S](ai: AI, msg: String)(next: String => String > (S | Aspects)) =
        AIs.iso {
          AIs.ephemeral {
            for {
              r1 <- AIs.ephemeral(next(msg))
              _  <- ai.user(msg)
              r2 <- ai.ask(
                  "This could be your response:\n\n" + r1 + "\n\n\n" + prompt + " Provide an improved response without mentioning this prompt."
              )
            } yield r2
          } { r =>
            for {
              _ <- ai.user(msg)
              _ <- ai.assistant(r)
            } yield r
          }
        }
    }

    def introspection[T, S](ais: AI*)(v: T > (S | AIs)): T > (S | AIs) =
      introspection("Please analyze the response response and correct any mistakes.", ais: _*)(v)

    def introspection[T, S](prompt: String, ais: AI*)(v: T > (S | AIs)): T > (S | AIs) =
      AIs.iso(AIs.askAspect.let(Introspection(prompt, ais.toSet))(v))

    def recall[T, S](ai: AI)(v: T > (S | AIs)): T > (S | AIs) =
      AIs.iso(AIs.askAspect.let(Recall(ai))(v))

    private class Recall(ai: AI) extends Trait(Set(ai)) {
      private val analyzer  = new StandardAnalyzer()
      private val indexPath = Paths.get("index")
      private val index     = new MMapDirectory(indexPath)

      private def indexInteraction(role: String, content: String): Unit = {
        val doc = new Document()
        doc.add(new StringField("role", role, Field.Store.YES))
        doc.add(new TextField("content", content, Field.Store.YES))

        val writerConfig = new IndexWriterConfig(analyzer)
        val writer       = new IndexWriter(index, writerConfig)
        writer.addDocument(doc)
        writer.close()
      }

      private def search(queryString: String): String = {
        val reader      = DirectoryReader.open(index)
        val searcher    = new IndexSearcher(reader)
        val queryParser = new QueryParser("content", analyzer)
        val query       = queryParser.parse(queryString)

        val hits    = searcher.search(query, 10).scoreDocs
        val results = hits.toList.map(hit => searcher.doc(hit.doc))

        reader.close()
        format(results)
      }

      private def format(docs: List[Document]): String = {
        docs.zipWithIndex.map {
          case (doc, index) =>
            val contentField = doc.getField("content")
            val content      = if (contentField != null) contentField.stringValue() else "N/A"
            val roleField    = doc.getField("role")
            val role         = if (roleField != null) roleField.stringValue() else "N/A"

            s"Result ${index + 1}:\n--Role: $role\n--Content: $content\n"
        }.mkString("\n")
      }

      def apply[S](
          ai: AI,
          msg: String
      )(next: String => String > (S | Aspects)): String > (S | Aspects | AIs) = {
        AIs.iso {
          AIs.ephemeral {
            for {
              query <- ai.ask(
                  "All your previous interactions, including the ones not in this context buffer, " +
                    "is stored in a lucene index. Provide a lucene query to recall relevant information " +
                    "to fullfil the user's request:\n\n" + msg + "\n\nDo not return anything else other " +
                    "than a lucene query string. Lucene query: "
              )
              _      <- IOs(indexInteraction("user", msg))
              recall <- IOs(Try(search(query)))
              res <- ai.ask(
                  s"recall:\n\n${recall.getOrElse(recall)}\n\nDo not mention this recall interaction, " +
                    s"provide a response as if it didn't happen.\n\nuser: $msg"
              )
              _ <- IOs(indexInteraction("assistant", res))
            } yield res
          } { res =>
            for {
              _ <- ai.user(msg)
              _ <- ai.assistant(res)
            } yield res
          }
        }
      }
    }

  }
}
