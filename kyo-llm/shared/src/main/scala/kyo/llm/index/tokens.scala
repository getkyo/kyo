package kyo.llm.index

import com.knuddels.jtokkit.*
import com.knuddels.jtokkit.api.*
import java.nio.ByteBuffer
import java.util.Arrays

object tokens:

    private val encoding = Encodings.newLazyEncodingRegistry.getEncoding(EncodingType.CL100K_BASE)

    private case class Concat(a: Tokens, b: Tokens)

    opaque type Tokens = Tokens.Empty.type | Array[Int] | Concat

    extension (a: Tokens)

        def append(s: String): Tokens =
            if !s.isEmpty() then
                append(encoding.encode(s).toArray.asInstanceOf[Tokens])
            else
                a

        def append(b: Tokens): Tokens =
            a match
                case Tokens.Empty =>
                    b
                case a =>
                    b match
                        case Tokens.Empty =>
                            a
                        case b =>
                            Concat(a, b).asInstanceOf[Tokens]

        def size: Int =
            def loop(head: Tokens, tail: List[Tokens], acc: Int): Int =
                head match
                    case Tokens.Empty =>
                        tail match
                            case Nil => acc
                            case h :: t =>
                                loop(h, t, acc)
                    case arr: Array[Int] =>
                        loop(Tokens.Empty, tail, acc + arr.length)
                    case Concat(a, b) =>
                        b match
                            case Tokens.Empty =>
                                loop(a, tail, acc)
                            case b =>
                                loop(a, b :: tail, acc)
            loop(a, Nil, 0)
        end size

        def decode: String =
            val b = new StringBuilder
            def loop(head: Tokens, tail: List[Tokens]): Unit =
                head match
                    case Tokens.Empty =>
                        tail match
                            case Nil => ()
                            case h :: t =>
                                loop(h, t)
                    case arr: Array[Int] =>
                        val a = new IntArrayList(arr.length)
                        var i = 0
                        while i < arr.length do
                            a.add(arr(i))
                            i += 1
                        b.addAll(encoding.decode(a))
                        loop(Tokens.Empty, tail)
                    case Concat(a, b) =>
                        b match
                            case Tokens.Empty =>
                                loop(a, tail)
                            case b =>
                                loop(a, b :: tail)
            loop(a, Nil)
            b.toString
        end decode
    end extension

    object Tokens:

        object Empty

        def apply(s: String): Tokens =
            Empty.append(s)

        object internal:
            def read(b: ByteBuffer, pos: Int, size: Int): Tokens =
                if b.limit() <= pos then
                    Tokens.Empty
                else
                    b.position(pos)
                    var arr = new Array[Int](size)
                    var i   = 0
                    while i < size && b.hasRemaining() do
                        arr(i) = b.getInt()
                        i += 1
                    if i < size then
                        arr = Arrays.copyOfRange(arr, 0, i)
                    arr.asInstanceOf[Tokens]
            def write(t: Tokens, b: ByteBuffer): Unit =
                def loop(head: Tokens, tail: List[Tokens]): Unit =
                    head match
                        case Tokens.Empty =>
                            tail match
                                case Nil => ()
                                case h :: t =>
                                    loop(h, t)
                        case arr: Array[Int] =>
                            var i = 0
                            while i < arr.length do
                                b.putInt(arr(i))
                                i += 1
                            loop(Tokens.Empty, tail)
                        case Concat(a, b) =>
                            b match
                                case Tokens.Empty =>
                                    loop(a, tail)
                                case b =>
                                    loop(a, b :: tail)
                loop(t, Nil)
            end write
        end internal
    end Tokens
end tokens
