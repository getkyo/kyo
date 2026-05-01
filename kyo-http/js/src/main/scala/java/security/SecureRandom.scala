package java.security

import scala.annotation.tailrec
import scala.scalajs.js
import scala.scalajs.js.typedarray.Int8Array

class SecureRandom extends java.util.Random(0L):

    override def nextBytes(bytes: Array[Byte]): Unit =
        val len = bytes.length
        if len > 0 then
            val buf = new Int8Array(len)
            val _   = js.Dynamic.global.crypto.getRandomValues(buf)
            @tailrec def loop(i: Int): Unit =
                if i < len then
                    bytes(i) = buf(i)
                    loop(i + 1)
            loop(0)
        end if
    end nextBytes

    override protected def next(bits: Int): Int =
        val bytes = new Array[Byte](4)
        nextBytes(bytes)
        val n = ((bytes(0) & 0xff) << 24) |
            ((bytes(1) & 0xff) << 16) |
            ((bytes(2) & 0xff) << 8) |
            (bytes(3) & 0xff)
        n >>> (32 - bits)
    end next

    override def setSeed(seed: Long): Unit = ()
end SecureRandom
