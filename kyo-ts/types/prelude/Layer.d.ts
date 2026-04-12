export declare class Layer {
  and<Out2, S2>(that: Layer<Out2, S2>): Layer<Out & Out2, S & S2>;
  run<In, R>(): Kyo<TypeMap<Out>, S & SReduced & Memo>;
  to<Out2, S2, In2>(that: Layer<Out2, Env<In2> & S2>): Layer<Out2, S & S2>;
  using<Out2, S2, In2>(that: Layer<Out2, Env<In2> & S2>): Layer<Out & Out2, S & S2>;

  readonly empty: Layer<unknown, unknown>;
  static from<A, B, C, D, E, F, G, H, I, J, S>(f: (x1: A, x2: B, x3: C, x4: D, x5: E, x6: F, x7: G, x8: H, x9: I) => Kyo<J, S>): Layer<J, Env<A & B & C & D & E & F & G & H & I> & S>;
  static init<Target>(layers: Layer<unknown, unknown>[]): Layer<Target, unknown>;
};