export declare class Memo {

  static apply<A, B, S>(f: (x1: A) => Kyo<B, S>): (x1: A) => Kyo<B, S & Memo>;
  readonly isolate: Isolate<Memo, unknown, Memo>;
};