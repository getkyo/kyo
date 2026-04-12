export declare class HttpFormCodec {
  decode(s: string): Result<Throwable, A>;
  encode(a: A): string;

  static derived<A>(): HttpFormCodec<A>;
};