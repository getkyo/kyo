package kyo.llm.index

import com.knuddels.jtokkit._
import com.knuddels.jtokkit.api._
import java.nio.ByteBuffer
import java.util.Arrays
import java.util.ArrayList

object tokens {

  private val encoding = Encodings.newLazyEncodingRegistry.getEncoding(EncodingType.CL100K_BASE)


  private case class Concat(a: Tokens, b: Tokens)


  type Tokens // = Unit | Array[Int] | Concat

  implicit class TokensOps(a: Tokens) {

    def append(s: String): Tokens =
      if (!s.isEmpty()) {
        append(encoding.encode(s).toArray[Int].asInstanceOf[Tokens])
      } else {
        a
      }

    def append(b: Tokens): Tokens =
      a match {
        case Tokens.empty =>
          b
        case a =>
          b match {
            case Tokens.empty =>
              a
            case b =>
              Concat(a, b).asInstanceOf[Tokens]
          }
      }

    def size: Int = {
      def loop(head: Tokens, tail: List[Tokens], acc: Int): Int =
        head match {
          case Tokens.empty =>
            tail match {
              case Nil => acc
              case h :: t =>
                loop(h, t, acc)
            }
          case arr: Array[Int] =>
            loop(Tokens.empty, tail, acc + arr.length)
          case Concat(a, b) =>
            b match {
              case Tokens.empty =>
                loop(a, tail, acc)
              case b =>
                loop(a, b :: tail, acc)
            }
        }
      loop(a, Nil, 0)
    }

    def decode: String = {
      val b = new StringBuilder
      def loop(head: Tokens, tail: List[Tokens]): Unit =
        head match {
          case Tokens.empty =>
            tail match {
              case Nil => ()
              case h :: t =>
                loop(h, t)
            }
          case arr: Array[Int] =>
            // TODO JTokkit should support arrays
            val a = new ArrayList[Integer](arr.length)
            var i = 0
            while (i < arr.length) {
              a.add(arr(i))
              i += 1
            }
            b.addAll(encoding.decode(a))
            loop(Tokens.empty, tail)
          case Concat(a, b) =>
            b match {
              case Tokens.empty =>
                loop(a, tail)
              case b =>
                loop(a, b :: tail)
            }
        }
      loop(a, Nil)
      b.toString
    }
  }

  object Tokens {

    val empty: Tokens = ().asInstanceOf[Tokens]

    def apply(s: String): Tokens =
      empty.append(s)

    object internal {
      def read(b: ByteBuffer, pos: Int, size: Int): Tokens = {
        if (b.limit() <= pos) {
          Tokens.empty
        } else {
          b.position(pos)
          var arr = new Array[Int](size)
          var i   = 0
          while (i < size && b.hasRemaining()) {
            arr(i) = b.getInt()
            i += 1
          }
          if (i < size) {
            arr = Arrays.copyOfRange(arr, 0, i)
          }
          arr.asInstanceOf[Tokens]
        }
      }
      def write(t: Tokens, b: ByteBuffer): Unit = {
        def loop(head: Tokens, tail: List[Tokens]): Unit =
          head match {
            case Tokens.empty =>
              tail match {
                case Nil => ()
                case h :: t =>
                  loop(h, t)
              }
            case arr: Array[Int] =>
              var i = 0
              while (i < arr.length) {
                b.putInt(arr(i))
                i += 1
              }
              loop(Tokens.empty, tail)
            case Concat(a, b) =>
              b match {
                case Tokens.empty =>
                  loop(a, tail)
                case b =>
                  loop(a, b :: tail)
              }
          }
        loop(t, Nil)
      }
    }
  }
}
