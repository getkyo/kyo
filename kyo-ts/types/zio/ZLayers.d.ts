export declare class ZLayers {

  static get<E, A>(layer: () => ZLayer<unknown, E, A>): Layer<A, Abort<E> & Async & Scope>;
};