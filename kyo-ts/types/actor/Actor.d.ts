export declare class Actor {
  ask<B>(f: (x1: Subject<B>) => A): Kyo<B, Async & Abort<Closed>>;
  await(): Kyo<B, Async & Abort<Closed | E>>;
  close(): Kyo<Maybe<A[]>, Sync>;
  readonly fiber: Fiber<B, Abort<Closed | E>>;
  send(message: A): Kyo<void, Async & Abort<Closed>>;
  readonly subject: Subject<A>;
  trySend(message: A): Kyo<boolean, Sync & Abort<Closed>>;

  readonly defaultCapacity: number;
  static receiveAll<A, S>(f: (x1: A) => Kyo<unknown, S>): Kyo<void, Context<A> & S>;
  static receiveLoop<A, State1, State2, State3, State4, S>(state1: State1, state2: State2, state3: State3, state4: State4, f: (x1: A, x2: State1, x3: State2, x4: State3, x5: State4) => Kyo<Outcome4<State1, State2, State3, State4, [State1, State2, State3, State4]>, S>): Kyo<[State1, State2, State3, State4], Context<A> & S>;
  static receiveLoop<A, State1, State2, S>(state1: State1, state2: State2, f: (x1: A, x2: State1, x3: State2) => Kyo<Outcome2<State1, State2, [State1, State2]>, S>): Kyo<[State1, State2], Context<A> & S>;
  static receiveLoop<A, State, S>(state: State, f: (x1: A, x2: State) => Kyo<Outcome<State, State>, S>): Kyo<State, Context<A> & S>;
  static receiveLoop<A, S>(f: (x1: A) => Kyo<Outcome<void, void>, S>): Kyo<void, Context<A> & S>;
  static receiveLoop<A, State1, State2, State3, S>(state1: State1, state2: State2, state3: State3, f: (x1: A, x2: State1, x3: State2, x4: State3) => Kyo<Outcome3<State1, State2, State3, [State1, State2, State3]>, S>): Kyo<[State1, State2, State3], Context<A> & S>;
  static reenqueue<A>(msg: A): Kyo<void, Context<A>>;
  static self<A>(): Kyo<Subject<A>, Context<A>>;
  static selfWith<A, B, S>(f: (x1: Subject<A>) => Kyo<B, S>): Kyo<B, Context<A> & S>;
};