export declare class HttpCodec {
  decode(raw: string): Result<Throwable, A>;
  encode(value: A): string;

  static apply<A>(enc: (x1: A) => string, dec: (x1: string) => A): HttpCodec<A>;
  readonly given_HttpCodec_BigDecimal: HttpCodec<BigDecimal>;
  readonly given_HttpCodec_BigInt: HttpCodec<BigInt>;
  readonly given_HttpCodec_Boolean: HttpCodec<boolean>;
  readonly given_HttpCodec_Byte: HttpCodec<number>;
  readonly given_HttpCodec_Double: HttpCodec<number>;
  readonly given_HttpCodec_Duration: HttpCodec<Duration>;
  readonly given_HttpCodec_Float: HttpCodec<number>;
  readonly given_HttpCodec_Instant: HttpCodec<Instant>;
  readonly given_HttpCodec_Int: HttpCodec<number>;
  readonly given_HttpCodec_Long: HttpCodec<number>;
  readonly given_HttpCodec_Short: HttpCodec<number>;
  readonly given_HttpCodec_String: HttpCodec<string>;
  readonly given_HttpCodec_UUID: HttpCodec<UUID>;
};