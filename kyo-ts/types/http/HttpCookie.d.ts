export declare class HttpCookie {
  readonly codec: HttpCodec<A>;
  domain(d: string): HttpCookie<A>;
  readonly domain: Maybe<string>;
  readonly httpOnly: boolean;
  httpOnly(b: boolean): HttpCookie<A>;
  maxAge(d: Duration): HttpCookie<A>;
  readonly maxAge: Maybe<Duration>;
  readonly path: Maybe<string>;
  path(p: string): HttpCookie<A>;
  readonly sameSite: Maybe<SameSite>;
  sameSite(s: SameSite): HttpCookie<A>;
  secure(b: boolean): HttpCookie<A>;
  readonly secure: boolean;
  readonly value: A;

  static apply<A>(value: A): HttpCookie<A>;
  static apply<A>(value: A, codec: HttpCodec<A>, maxAge: Maybe<Duration>, domain: Maybe<string>, path: Maybe<string>, secure: boolean, httpOnly: boolean, sameSite: Maybe<SameSite>): HttpCookie<A>;
  static given_CanEqual_HttpCookie_HttpCookie<A, B>(): CanEqual<HttpCookie<A>, HttpCookie<B>>;
};