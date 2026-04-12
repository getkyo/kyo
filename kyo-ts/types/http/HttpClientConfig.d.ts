export declare class HttpClientConfig {
  baseUrl(url: string): HttpClientConfig;
  readonly baseUrl: Maybe<HttpUrl>;
  connectTimeout(d: Duration): HttpClientConfig;
  readonly connectTimeout: Maybe<Duration>;
  followRedirects(v: boolean): HttpClientConfig;
  readonly followRedirects: boolean;
  maxRedirects(v: number): HttpClientConfig;
  readonly maxRedirects: number;
  retry(schedule: Schedule): HttpClientConfig;
  readonly retryOn: (x1: HttpStatus) => boolean;
  retryOn(f: (x1: HttpStatus) => boolean): HttpClientConfig;
  readonly retrySchedule: Maybe<Schedule>;
  readonly timeout: Maybe<Duration>;
  timeout(d: Duration): HttpClientConfig;
};