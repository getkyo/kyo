export declare class HttpServerConfig {
  readonly backlog: number;
  backlog(v: number): HttpServerConfig;
  readonly cors: Maybe<Cors>;
  cors(c: Cors): HttpServerConfig;
  flushConsolidationLimit(v: number): HttpServerConfig;
  readonly flushConsolidationLimit: number;
  host(h: string): HttpServerConfig;
  readonly host: string;
  readonly keepAlive: boolean;
  keepAlive(v: boolean): HttpServerConfig;
  readonly maxContentLength: number;
  maxContentLength(v: number): HttpServerConfig;
  readonly openApi: Maybe<OpenApiEndpoint>;
  openApi(path: string, title: string, version: string, description: string | undefined): HttpServerConfig;
  port(p: number): HttpServerConfig;
  readonly port: number;
  readonly strictCookieParsing: boolean;
  strictCookieParsing(v: boolean): HttpServerConfig;
  tcpFastOpen(v: boolean): HttpServerConfig;
  readonly tcpFastOpen: boolean;

  readonly default_: HttpServerConfig;
};