export declare class Aspect {
  apply<C>(input: Input<C>, cont: (x1: Input<C>) => Kyo<Output<C>, S>): Kyo<Output<C>, S>;
  readonly asCut: Cut<Input, Output, S>;
  let_<A, S2>(a: Cut<Input, Output, S>, v: Kyo<A, S2>): Kyo<A, S & S2>;
  sandbox<A, S>(v: Kyo<A, S>): Kyo<A, S>;

  static init<I, O, S>(): Aspect<I, O, S>;
};