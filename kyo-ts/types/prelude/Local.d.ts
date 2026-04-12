export declare class Local {
  readonly default_: A;
  get(): Kyo<A, unknown>;
  let_<B, S>(value: A, v: Kyo<B, S>): Kyo<B, S>;
  update<B, S>(f: (x1: A) => A, v: Kyo<B, S>): Kyo<B, S>;
  use<B, S>(f: (x1: A) => Kyo<B, S>): Kyo<B, S>;

  static init<A>(defaultValue: A): Local<A>;
  static initNoninheritable<A>(defaultValue: A): Local<A>;
  readonly internal: internal;
};