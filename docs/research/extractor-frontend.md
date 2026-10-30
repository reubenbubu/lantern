# Frontends of Java systems: what the Frontend extractor reads

Research for #394, the review of the Frontend extractor in the milestone "Extractor revisit". It
leads to the amendment of `docs/adr/0083-frontend-containers.md` for #394.

**Depth: high.** The extractor reads several frontend ecosystems that change often, and three of
project-3's misses (pages and services, auth, loaded hosts) are things it had no concept for. 17
pages were read in full.

## Question

How are the frontends of Java systems built, shipped and wired to their backends? What in each
shows, statically:

- that a frontend container exists
- which backend APIs and external services it calls
- with which base URL, configured how per environment
- how it authenticates

## What we learned

**Base URLs are fixed at build time unless the team adds runtime config.**

- **Angular** swaps `environment.ts` for the file named in a configuration's `fileReplacements`.
  `ng build --configuration x` picks the configuration, else `defaultConfiguration` applies. Code
  always imports `environment.ts`. <https://angular.dev/tools/cli/environments>
- **Vite** replaces `import.meta.env.VITE_*` statically. In order of precedence it reads
  `.env.[mode].local`, `.env.[mode]`, `.env.local`, then `.env`. Shell variables beat them all,
  and `vite build` uses mode `production`. <https://vite.dev/guide/env-and-mode>
- **Create React App** embeds `process.env.REACT_APP_*` at build.
  <https://create-react-app.dev/docs/adding-custom-environment-variables/>
- **Nuxt** is the exception: `runtimeConfig.public.apiBase` is overridden at run time by
  `NUXT_PUBLIC_API_BASE`, so the URL lives in the deployment.
  <https://nuxt.com/docs/4.x/guide/going-further/runtime-config>
- **Build once, configure at run time.** A common pattern fetches `assets/config.json` in an
  `APP_INITIALIZER`. <https://www.thisdot.co/blog/runtime-environment-configuration-with-angular>

**Proxies decide where a relative URL goes.**

- **Angular's `proxyConfig`** is a development-server feature built on Vite's proxy.
  <https://angular.dev/tools/cli/serve>
- **Vite's `server.proxy`** applies to the development server only.
  <https://vite.dev/config/server-options>
- **Next.js `rewrites`** can send a path to an external URL, and they are a deployed proxy.
  <https://nextjs.org/docs/app/api-reference/config/next-config-js/rewrites>
- **The official nginx image** runs envsubst on `/etc/nginx/templates/*.template` at start, so
  `proxy_pass ${API_URL}` is filled from the container's environment.
  <https://raw.githubusercontent.com/docker-library/docs/master/nginx/content.md>

**Auth is visible in interceptors and guards.**

- **Interceptors.**
  - Functional interceptors (`HttpInterceptorFn`) are registered with
    `withInterceptors([...])`.
  - Class interceptors use `HTTP_INTERCEPTORS` with `withInterceptorsFromDi()`.
  - Both clone the request to add headers.

  <https://angular.dev/guide/http/interceptors>
- **Guards** (`CanActivateFn` and its kin) are attached with `canActivate: [guard]`. Angular
  warns: "Never rely on client-side guards as the sole source of access control."
  <https://angular.dev/guide/routing/route-guards>

**Frontends inside Maven.** frontend-maven-plugin installs Node and runs npm from a working
directory such as `src/main/frontend`. It does not package the output.
<https://github.com/eirslett/frontend-maven-plugin>

**Generated clients.** openapi-generator's `typescript-angular` services call
`` `${basePath}${localVarPath}` ``, with `basePath` from a `BASE_PATH` token. A text reader sees
neither the base nor the method.
<https://raw.githubusercontent.com/OpenAPITools/openapi-generator/master/samples/client/petstore/typescript-angular-v19/builds/default/api/pet.service.ts>,
<https://openapi-generator.tech/docs/generators/typescript-angular/>

**What other tools do.**

- **Code2DFD** searches for keywords, then follows the identifiers it finds. Its only frontend
  notion is a "web application" stereotype. <https://arxiv.org/pdf/2304.12769>
- **The nine-tool comparison** says nothing of frontends, but finds that deployment files alone
  miss items. <https://arxiv.org/html/2412.08352v1>
- **Wittern et al.** (ICSE 2017) slice backwards from jQuery requests within one file, with
  unknowns as `{x}`. They reach 96% on URL and method. Their failures are values from other files
  and variables spanning path segments. Lantern's text reader follows the same design and has the
  same limits. <https://ar5iv.labs.arxiv.org/html/1702.03906>

No tool models a frontend's pages, auth or loaded hosts. project-3's reference asks for all three.

## Evidence from the test sets

- **project-0.** No frontend.
- **project-1, `angular-ui`.** Angular served by nginx (`location /bff/` → `http://bff:8081/bff/`).
  - `environment.ts:3` sets `bffUrl: '/bff'`.
  - Auth is a BFF session: an interceptor sets `withCredentials`, and sign-in is a navigation.
  - Google Fonts in `index.html` is not in the reference.
  - Miss: no fact cited `environment.ts`.
- **project-2, `frontend`.** React with Vite and nginx, no calls, fully backed.
- **project-3, `mediahub-frontend-ui`.** Angular 20 standalone. Six of its 12 needs were unmet:
  - Lorem Picsum is an `<img [src]>` built per item in an inline template (`catalog.ts:42`).
  - The base URL is the field `apiUrl` on `media.ts:12`, while the call is on line 15.
  - The functional guard and interceptor (`auth.guard.ts:5-15`, `jwt.interceptor.ts:3-16`) had no
    facts.
  - Pages and services (`home.ts:118-125`, `auth.service.ts`, `user.ts`, `subscription.ts`) had
    no facts.
- **project-4, `CRM-Frontend`.** Angular with nginx, and its Dockerfile builds
  `--configuration development`.
  - The reference cites `environment.ts:2`, which no fact cited.
  - Auth is a class interceptor with a functional guard.
- **None of the sets** has frontend-maven-plugin, server-side templates, Next.js, Nuxt, runtime
  config or a mobile client in code.

## What the extractor missed

1. Facts cited only the call line, not the field, constant or environment entry the URL comes
   from.
2. URLs that templates and styles load were not read.
3. There were no auth facts: interceptors and guards.
4. There were no facts for Angular pages and services.
5. Runtime config, nginx templates, Next.js rewrites, Nuxt, generated clients and cross-file
   values were not read.

## Options

- **A. Keep the text reader and add rules.** One fact for each line that decides where the
  browser goes, plus resource, auth and component facts. Cheap, fits ADR 0083 and the tools
  above, and fixes every current miss. It is brittle on cross-file values.
- **B. A real TypeScript parser.** Exact syntax and imports, but it needs a new dependency or
  runtime: tree-sitter, swc4j or Node at analysis time.
- **C. Draw frontend components in the report.** Needed for project-3's component view, but it
  changes the design (ADR 0083's "no components", ADR 0074).
- **D. Runtime and deployment configuration.** Real patterns, but in no development set.

## Recommendation

- **A now**, with the facts for C.
- **B in #416.**
- **Drawing C in #415.**
- **D waits** until a set has it.

**Rule:** a frontend's facts cite every line that decides where the browser goes: the call, each
constant and environment entry its URL is built from, and the proxy that passes it on. Lantern
records:

- its calls (requests, sockets, navigations)
- the absolute URLs its templates and styles load, marking those built per item
- how it signs requests and guards pages, with guards recorded as what the browser shows, never
  as access control
- for Angular, its pages and services, with what they inject and call

Values a deployment sets are followed only where the repository states them.

## What it changes

- `docs/adr/0083-frontend-containers.md`, amended for #394.
- `docs/design.md`, the Frontend row of the extractor table.
- `docs/quality-bar.md`: nothing now. Whether a host loaded per item is an external system is
  decided with #415.

## Sources read in full

- Angular: HTTP interceptors, environments, `ng serve`, route guards
- Vite: env and mode, server options
- Create React App: custom environment variables
- Next.js: rewrites
- Nuxt: runtime config
- Docker library nginx documentation
- frontend-maven-plugin README
- This Dot Labs: runtime environment configuration with Angular
- openapi-generator: the `typescript-angular` sample service, and the generator's docs
- arXiv 2412.08352, the nine-tool comparison
- Wittern et al., ICSE 2017 (ar5iv 1702.03906)
- Code2DFD, arXiv 2304.12769 (method, evaluation and discussion; dataset tables skimmed)

## Sources not read or read only in part (carry no weight)

- Spring Boot servlet web reference: the first part only.
- Thymeleaf with Spring tutorial: the link and form parts only.
- Vaadin Hilla browser-callable services: the first part only.
- docker-nginx README: points elsewhere.
- Search results only: Nitro config, SafeRESTScript, API Ghost Hunter, AXA (ASE 2024), env.js
  articles.
