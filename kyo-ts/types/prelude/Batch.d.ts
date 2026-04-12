export declare class Batch {

  static eval<A>(seq: A[]): Kyo<A, Batch>;
  static foreach<A, B, S>(seq: A[], f: (x1: A) => Kyo<B, S>): Kyo<B, Batch & S>;
  readonly internal: internal;
  static source<A, B, S>(f: (x1: A[]) => Kyo<(x1: A) => Kyo<B, S>, S>): (x1: A) => Kyo<B, Batch & S>;
  static sourceMap<A, B, S>(f: (x1: A[]) => Kyo<Map<A, B>, S>): (x1: A) => Kyo<B, Batch & S>;
  static sourceSeq<A, B, S>(f: (x1: A[]) => Kyo<B[], S>): (x1: A) => Kyo<B, Batch & S>;
};