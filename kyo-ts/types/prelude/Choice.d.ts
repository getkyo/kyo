export declare class Choice {

  static drop(): Kyo<never, Choice>;
  static eval<A>(a: A[]): Kyo<A, Choice>;
  static evalSeq<A>(seq: A[]): Kyo<A, Choice>;
  static evalWith<A, B, S>(seq: A[], f: (x1: A) => Kyo<B, S>): Kyo<B, Choice & S>;
};