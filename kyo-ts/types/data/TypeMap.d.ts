export declare class TypeMap {
  add<B>(b: B): TypeMap<A & B>;
  get<B>(): B;
  isEmpty(): boolean;
  prune<B>(): TypeMap<B>;
  show(): string;
  size(): number;
  union<B>(that: TypeMap<B>): TypeMap<A & B>;

  static apply<A, B>(a: A, b: B): TypeMap<A & B>;
  static apply<A, B, C, D>(a: A, b: B, c: C, d: D): TypeMap<A & B & C & D>;
  static apply<A, B, C>(a: A, b: B, c: C): TypeMap<A & B & C>;
  static apply<A>(a: A): TypeMap<A>;
  readonly empty: TypeMap<unknown>;
};