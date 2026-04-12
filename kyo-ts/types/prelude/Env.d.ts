export declare class Env {

  readonly eliminateEnv: eliminateEnv;
  static get<R>(): Kyo<R, Env<R>>;
  static getAll<R>(): Kyo<TypeMap<R>, Env<R>>;
  static run<R, A, S, VR>(env: R, v: Kyo<A, Env<R & VR> & S>): Kyo<A, S & SReduced>;
  static runLayer<A, S, V>(layers: Layer<unknown, unknown>[], value: Kyo<A, Env<V> & S>): Kyo<A, never>;
  static use<R, A, S>(f: (x1: R) => Kyo<A, S>): Kyo<A, Env<R> & S>;
  static useAll<R, A, S>(f: (x1: TypeMap<R>) => Kyo<A, S>): Kyo<A, Env<R> & S>;
};