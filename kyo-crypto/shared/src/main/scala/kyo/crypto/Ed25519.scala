package kyo.crypto

import kyo.*
import kyo.internal.crypto.Ed25519Field
import kyo.internal.crypto.Ed25519Field.Fe
import kyo.internal.crypto.Ed25519Field.Scratch
import kyo.internal.crypto.Ed25519Scalar

/** Ed25519 signature verification (RFC 8032 section 5.1.7) on sixteen-limb field arithmetic shared by every platform.
  *
  * A public key is decoded once, strictly, into a [[VerificationKey]] by [[VerificationKey.fromBytes]]: 32 bytes whose `y` is below `p`,
  * whose `x` exists on the curve, which is not `x = 0` with the sign bit set (section 5.1.3), and whose point is not of small order.
  * What fails those checks is a [[KeyFailure]] naming the check. The key holds the odd multiples `-A, -3A, .., -15A` of the negated point, so a verification does no
  * decoding and no square root. A signature `R || S` then verifies when `S` is below the group order `L` and
  * `encode([S]B + [k](-A))` equals the 32 bytes of `R`, for `k = SHA-512(R || A || M) mod L`; the two scalar multiplications share one
  * chain of at most 254 doublings over width-5 signed digits, from the highest nonzero digit of either scalar down. Comparing the
  * encoding with the received bytes accepts exactly what decoding `R`
  * strictly and comparing points accepts: the encoding is injective on the curve, and a byte string decodes strictly to a point only when
  * it is that point's encoding, so a non-canonical `R`, an `R` off the curve and `x = 0` with the sign bit set are all rejected. A
  * signature that is not 64 bytes, whose `S` is not below `L` or whose `R` is not the encoding of the computed point is not valid, never
  * a failure value: the signature is the untrusted input, and there is nothing a caller does with the reason.
  *
  * The field arithmetic is [[kyo.internal.crypto.Ed25519Field]], TweetNaCl's `gf` function for function; the scalar reduction and the
  * digit recoding are [[kyo.internal.crypto.Ed25519Scalar]]; the point addition is TweetNaCl's `add` and the doubling is section 5.1.4's
  * `dbl`, always computing `T`. The base-point table is computed from that arithmetic on first use, never typed. Every call allocates its
  * own temporaries, so nothing is shared between fibers.
  *
  * IMPORTANT: the group equation is the cofactorless one. RFC 8032 also allows `[8][S]B = [8]R + [8][k]A`; the two differ only when `A`
  * or `R` carries a small-order component, and the cofactorless equation is the one used by the verifiers Discord documents for its
  * interactions endpoint (tweetnacl, libsodium, Tink) and by Go, BoringSSL and OpenSSL. Changing it changes which forged edge cases
  * verify, not which honest signatures do. A small-order `A` is refused at the key ([[KeyFailure.SmallOrder]]): no honest key is one,
  * and under one a signature with `S = 0` verifies for some messages. A mixed-order `A` and a small-order or mixed-order `R` are
  * accepted whenever the equation holds, as RFC 8032 has no blocklist: of the ed25519-speccheck cases, 0 and 1 are refused at the key,
  * 2 and 3 verify and 4 to 11 do not; RFC 8032's reference code verifies 0 to 3, and libsodium refuses 0 to 2 through its blocklist on
  * both `A` and `R`.
  *
  * Note: verification handles only public values (the key, the message and the signature), so it is not constant time and does not need
  * to be.
  *
  * @see
  *   [[Ed25519.VerificationKey.fromBytes]], the one way to obtain a key
  * @see
  *   [[Ed25519.KeyFailure]], what decoding refuses with
  * @see
  *   [[Sha512.hashAll]], the hash of `R || A || M`
  * @see
  *   [[Hex.decode]], the decoder a hex-encoded key or signature goes through
  */
object Ed25519:

    import Ed25519Field.*

    private val KeySize = 32

    private val SignatureSize = 64

    /** A point in extended coordinates `(X, Y, Z, T)` with `x = X / Z`, `y = Y / Z`, `x y = T / Z`. */
    final private[kyo] class Point(val x: Fe, val y: Fe, val z: Fe, val t: Fe):
        def copyFrom(p: Point): Unit =
            copy(x, p.x)
            copy(y, p.y)
            copy(z, p.z)
            copy(t, p.t)
        end copyFrom
    end Point

    private[kyo] object Point:
        def apply(): Point = new Point(fe(), fe(), fe(), fe())

        /** The neutral element `(0, 1, 1, 0)`. */
        def identity(): Point =
            val p = Point()
            p.y(0) = 1
            p.z(0) = 1
            p
        end identity
    end Point

    /** The temporaries of one verification: field scratch, the nine elements the point formulas use, the accumulator, a negated table
      * entry, the two digit arrays, and the buffers of the hash reduction and the encoding. Allocated once per call.
      */
    final private[kyo] class Workspace:
        val scratch: Scratch         = new Scratch
        val a: Fe                    = fe()
        val b: Fe                    = fe()
        val c: Fe                    = fe()
        val d: Fe                    = fe()
        val e: Fe                    = fe()
        val f: Fe                    = fe()
        val g: Fe                    = fe()
        val h: Fe                    = fe()
        val u: Fe                    = fe()
        val q: Point                 = Point.identity()
        val negated: Point           = Point()
        val sDigits: Array[Byte]     = new Array[Byte](256)
        val kDigits: Array[Byte]     = new Array[Byte](256)
        val reduction: Array[Double] = new Array[Double](64)
        val k: Array[Byte]           = new Array[Byte](32)
        val encoded: Array[Byte]     = new Array[Byte](32)
    end Workspace

    /** TweetNaCl's `add`: `out = p + q` in extended coordinates; `out` may be `p` or `q`. Inputs reduced (a `mul` output, an `unpack`
      * output, a constant, or the negation of one of those); the multiplication inputs reach three reduced elements; outputs reduced.
      */
    private[kyo] def add(out: Point, p: Point, q: Point, ws: Workspace): Unit =
        val s = ws.scratch
        sub(ws.a, p.y, p.x)
        sub(ws.u, q.y, q.x)
        mul(ws.a, ws.a, ws.u, s)
        Ed25519Field.add(ws.b, p.x, p.y)
        Ed25519Field.add(ws.u, q.x, q.y)
        mul(ws.b, ws.b, ws.u, s)
        mul(ws.c, p.t, q.t, s)
        mul(ws.c, ws.c, D2, s)
        mul(ws.d, p.z, q.z, s)
        Ed25519Field.add(ws.d, ws.d, ws.d)
        sub(ws.e, ws.b, ws.a)
        sub(ws.f, ws.d, ws.c)
        Ed25519Field.add(ws.g, ws.d, ws.c)
        Ed25519Field.add(ws.h, ws.b, ws.a)
        mul(out.x, ws.e, ws.f, s)
        mul(out.y, ws.h, ws.g, s)
        mul(out.z, ws.g, ws.f, s)
        mul(out.t, ws.e, ws.h, s)
    end add

    /** RFC 8032 section 5.1.4's `dbl`: `out = 2 p`, `T` computed; `out` may be `p`. Inputs reduced, the multiplication inputs reach four
      * reduced elements, outputs reduced.
      */
    private[kyo] def double(out: Point, p: Point, ws: Workspace): Unit =
        val s = ws.scratch
        square(ws.a, p.x, s)
        square(ws.b, p.y, s)
        square(ws.c, p.z, s)
        Ed25519Field.add(ws.c, ws.c, ws.c)
        Ed25519Field.add(ws.h, ws.a, ws.b)
        Ed25519Field.add(ws.u, p.x, p.y)
        square(ws.u, ws.u, s)
        sub(ws.e, ws.h, ws.u)
        sub(ws.g, ws.a, ws.b)
        Ed25519Field.add(ws.f, ws.c, ws.g)
        mul(out.x, ws.e, ws.f, s)
        mul(out.y, ws.g, ws.h, s)
        mul(out.t, ws.e, ws.h, s)
        mul(out.z, ws.f, ws.g, s)
    end double

    /** `out = -p`: `x` and `t` negated, `y` and `z` copied. */
    private[kyo] def negate(out: Point, p: Point): Unit =
        sub(out.x, Zero, p.x)
        copy(out.y, p.y)
        copy(out.z, p.z)
        sub(out.t, Zero, p.t)
    end negate

    /** TweetNaCl's `pack`: the 32-byte encoding of section 5.1.2, `y` little-endian with the parity of `x` in bit 255. */
    private[kyo] def encode(out: Array[Byte], p: Point, ws: Workspace): Unit =
        val s = ws.scratch
        invert(ws.u, p.z, s)
        mul(ws.a, p.x, ws.u, s)
        mul(ws.b, p.y, ws.u, s)
        pack(out, ws.b, s)
        out(31) = (out(31) ^ (parity(ws.a, s) << 7)).toByte
    end encode

    /** The odd multiples `p, 3p, .., 15p` of `p`, from the point arithmetic. */
    private def oddMultiples(p: Point, ws: Workspace): Array[Point] =
        val table   = new Array[Point](8)
        val doubled = Point()
        double(doubled, p, ws)
        table(0) = Point()
        table(0).copyFrom(p)
        var i = 1
        while i < 8 do
            table(i) = Point()
            add(table(i), table(i - 1), doubled, ws)
            i += 1
        end while
        table
    end oddMultiples

    /** `B, 3B, .., 15B`, the base point of section 5.1 from TweetNaCl's `X` and `Y`, computed once. */
    private[kyo] val BaseTable: Array[Point] =
        val ws   = new Workspace
        val base = Point()
        copy(base.x, BaseX)
        copy(base.y, BaseY)
        copy(base.z, One)
        mul(base.t, BaseX, BaseY, ws.scratch)
        oddMultiples(base, ws)
    end BaseTable

    /** An Ed25519 public key that decoded under the strict rules of RFC 8032 section 5.1.3 and is not of small order, holding the 32 bytes
      * it came from and the odd multiples `-A, -3A, .., -15A` of its negated point, so that every verification under it skips the
      * decoding and the square root. Equal to another key exactly when the bytes are equal; its `toString` prints the bytes as hex, which
      * are public.
      *
      * Obtained only through [[VerificationKey.fromBytes]]. A plain class rather than a case class so that no synthesized `fromProduct` or
      * `copy` can build a key past the decoding.
      *
      * @see
      *   [[VerificationKey.fromBytes]], the factory
      * @see
      *   [[KeyFailure]], what the factory refuses with
      * @see
      *   [[Ed25519.verify]], the operation over this key
      * @see
      *   [[bytes]], the encoding the key came from
      */
    final class VerificationKey private[Ed25519] (
        private[Ed25519] val encoded: Array[Byte],
        private[kyo] val negatedTable: Array[Point]
    ) derives CanEqual:

        /** The 32 bytes the key was decoded from. */
        def bytes: Span[Byte] = Span.from(encoded)

        override def equals(other: Any): Boolean = other match
            case that: VerificationKey => java.util.Arrays.equals(encoded, that.encoded)
            case _                     => false

        override def hashCode: Int = java.util.Arrays.hashCode(encoded)

        override def toString: String = s"VerificationKey(${Hex.encodeArray(encoded)})"
    end VerificationKey

    object VerificationKey:

        /** The key `bytes` encode, or the first decoding rule they break. */
        def fromBytes(bytes: Span[Byte]): Result[KeyFailure, VerificationKey] =
            // Unsafe: the array is only read; the key copies it.
            fromArray(bytes.toArrayUnsafe)

        private[kyo] def fromArray(bytes: Array[Byte]): Result[KeyFailure, VerificationKey] =
            if bytes.length != KeySize then Result.fail(KeyFailure.Length(bytes.length))
            else if !isReducedY(bytes) then Result.fail(KeyFailure.YNotReduced)
            else
                val ws      = new Workspace
                val negated = Point()
                decodeNegated(negated, bytes, ws) match
                    case Root.Absent       => Result.fail(KeyFailure.NotOnCurve)
                    case Root.ZeroWithSign => Result.fail(KeyFailure.ZeroWithSign)
                    case Root.Present      =>
                        if hasSmallOrder(negated, ws) then Result.fail(KeyFailure.SmallOrder)
                        else Result.succeed(new VerificationKey(bytes.clone(), oddMultiples(negated, ws)))
                end match
            end if
        end fromArray
    end VerificationKey

    /** Why bytes did not decode to a key: the length, a `y` at or above `p`, a `y` with no `x` on the curve, `x = 0` with the sign bit
      * set, or a point of small order. The checks run in that order, so a key that fails several reports the first; the first four are
      * RFC 8032 section 5.1.3's decoding rules and the fifth is this module's policy on the eight points of order dividing 8. None
      * carries the bytes: a key is public, but a failure that carried it would invite logging it.
      *
      * @see
      *   [[VerificationKey.fromBytes]], which produces it
      * @see
      *   [[VerificationKey]], what a decoding that passes yields
      * @see
      *   [[Rsa.KeyFailure]], the counterpart for an RSA verification key
      */
    enum KeyFailure derives CanEqual:

        /** `length` bytes, where a key is 32. */
        case Length(length: Int)

        /** The encoded `y` is at or above `p`, so the encoding is not canonical. */
        case YNotReduced

        /** No `x` on the curve has this `y`. */
        case NotOnCurve

        /** The recovered `x` is 0 and the sign bit asks for the odd root, which 0 is not. */
        case ZeroWithSign

        /** The point has order dividing 8: one of the eight small-order points, which no honest key is, and under which a signature
          * with `S = 0` verifies for some messages.
          */
        case SmallOrder

    end KeyFailure

    /** Whether `signature` is a valid Ed25519 signature of `message` under `key`. No argument is modified. */
    def verify(key: VerificationKey, message: Span[Byte], signature: Span[Byte]): Boolean =
        // Unsafe: both arrays are only read.
        verifyArrays(key, message.toArrayUnsafe, signature.toArrayUnsafe)

    private[kyo] def verifyArrays(key: VerificationKey, message: Array[Byte], signature: Array[Byte]): Boolean =
        signature.length == SignatureSize && Ed25519Scalar.isBelowL(signature, 32) && {
            val ws       = new Workspace
            val encodedR = java.util.Arrays.copyOfRange(signature, 0, 32)
            Ed25519Scalar.reduce(ws.k, Sha512.hashArrays(Chunk(encodedR, key.encoded, message)), ws.reduction)
            Ed25519Scalar.slide(ws.sDigits, java.util.Arrays.copyOfRange(signature, 32, 64))
            Ed25519Scalar.slide(ws.kDigits, ws.k)
            doubleScalarMultiply(ws.q, ws.sDigits, BaseTable, ws.kDigits, key.negatedTable, ws)
            encode(ws.encoded, ws.q, ws)
            java.util.Arrays.equals(ws.encoded, encodedR)
        }

    /** `out = [s]P + [k]Q` for the digit arrays of `s` and `k` and the odd-multiple tables of `P` and `Q`, one shared chain of
      * doublings from the highest nonzero digit of either array down: ref10's `ge_double_scalarmult_vartime`
      * (`crypto_sign/ed25519/ref10/ge_double_scalarmult.c` of supercop-20120210, with `slide` in the same file), statement for
      * statement, with a subtraction written as the addition of the negated entry and the precomputed `B` table replaced by
      * [[BaseTable]], odd multiples computed like the key's. The identity when both arrays are zero.
      */
    private[kyo] def doubleScalarMultiply(
        out: Point,
        sDigits: Array[Byte],
        pTable: Array[Point],
        kDigits: Array[Byte],
        qTable: Array[Point],
        ws: Workspace
    ): Unit =
        out.copyFrom(Point.identity())
        var i = 255
        while i >= 0 && sDigits(i) == 0 && kDigits(i) == 0 do i -= 1
        while i >= 0 do
            double(out, out, ws)
            addDigit(out, sDigits(i), pTable, ws)
            addDigit(out, kDigits(i), qTable, ws)
            i -= 1
        end while
    end doubleScalarMultiply

    private def addDigit(acc: Point, digit: Byte, table: Array[Point], ws: Workspace): Unit =
        if digit > 0 then add(acc, acc, table((digit - 1) >> 1), ws)
        else if digit < 0 then
            negate(ws.negated, table((-digit - 1) >> 1))
            add(acc, acc, ws.negated, ws)
        end if
    end addDigit

    /** Whether `p` has order dividing 8: three doublings reach the identity, `X = 0` and `Y = Z`. libsodium's `ge25519_has_small_order`
      * answers the same question by comparing the encoding with its blocklist of the seven non-identity small-order encodings; the
      * doublings need no table and take the point already decoded.
      */
    private def hasSmallOrder(p: Point, ws: Workspace): Boolean =
        val q = ws.q
        double(q, p, ws)
        double(q, q, ws)
        double(q, q, ws)
        isZero(q.x, ws.scratch) && isEqual(q.y, q.z, ws.scratch)
    end hasSmallOrder

    /** Whether the low 255 bits of the 32 bytes, read little-endian, are below `p`: not every bit set outside byte 0 with byte 0 at least
      * `0xed`.
      */
    private def isReducedY(bytes: Array[Byte]): Boolean =
        if (bytes(31) & 0x7f) != 0x7f then true
        else
            var i   = 1
            var all = true
            while i < 31 do
                if (bytes(i) & 0xff) != 0xff then all = false
                i += 1
            end while
            !all || (bytes(0) & 0xff) < 0xed
        end if
    end isReducedY

    /** The outcome of RFC 8032 section 5.1.3 steps 3 and 4 for one encoding. */
    private enum Root derives CanEqual:
        case Absent
        case ZeroWithSign
        case Present
    end Root

    /** TweetNaCl's `unpackneg`, with the strict checks: decodes the 32 bytes (a canonical `y`) into `out = -A`, the negation of the point
      * they encode, or says why they encode none. The square root is `u v^3 (u v^7)^((p - 5) / 8)`, corrected by `sqrt(-1)` when its
      * square is `-u / v`, and the sign bit selects the root's parity.
      */
    private def decodeNegated(out: Point, bytes: Array[Byte], ws: Workspace): Root =
        val s = ws.scratch
        unpack(out.y, bytes, 0)
        copy(out.z, One)
        val num  = ws.a
        val den  = ws.b
        val den2 = ws.c
        val den4 = ws.d
        val den6 = ws.e
        val t    = ws.f
        val chk  = ws.g
        square(num, out.y, s)
        mul(den, num, D, s)
        sub(num, num, One)
        Ed25519Field.add(den, One, den)
        square(den2, den, s)
        square(den4, den2, s)
        mul(den6, den4, den2, s)
        mul(t, den6, num, s)
        mul(t, t, den, s)
        pow2523(t, t, s)
        mul(t, t, num, s)
        mul(t, t, den, s)
        mul(t, t, den, s)
        mul(out.x, t, den, s)
        square(chk, out.x, s)
        mul(chk, chk, den, s)
        if !isEqual(chk, num, s) then mul(out.x, out.x, SqrtMinusOne, s)
        square(chk, out.x, s)
        mul(chk, chk, den, s)
        if !isEqual(chk, num, s) then Root.Absent
        else
            val sign = (bytes(31) & 0xff) >> 7
            if sign == 1 && isZero(out.x, s) then Root.ZeroWithSign
            else
                if parity(out.x, s) == sign then sub(out.x, Zero, out.x)
                mul(out.t, out.x, out.y, s)
                Root.Present
            end if
        end if
    end decodeNegated

end Ed25519
