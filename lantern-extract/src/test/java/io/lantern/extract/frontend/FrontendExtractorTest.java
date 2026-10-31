package io.lantern.extract.frontend;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.repo.Repo;
import io.lantern.extract.Fixtures;
import io.lantern.extract.RepoBuild;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code frontend} fixture: an Angular app in {@code web/} whose default build replaces its
 * environment file, served by nginx with proxies and with a development proxy; a React app in
 * {@code shop-ui/} built with Vite, calling through axios, fetch and a WebSocket with dotenv
 * values; and a package that is no frontend. Needs no Docker.
 */
class FrontendExtractorTest {

  private static final long SNAPSHOT = 4;

  @TempDir Path temp;

  @Test
  void fixture() throws IOException {
    Repo repo = Fixtures.repo("frontend", temp.resolve("shop"), "shop");
    List<RepoBuild> inputs = List.of(new RepoBuild(repo, Fixtures.head(repo), null));

    List<Fact> facts = new FrontendExtractor().extract(inputs, SNAPSHOT);

    Fixtures.checkEnvelope(
        facts, List.of(repo), SNAPSHOT, FrontendExtractor.NAME, FrontendExtractor.VERSION);
    assertThat(new FrontendExtractor().extract(inputs, SNAPSHOT)).isEqualTo(facts);
    Fixtures.assertGolden("frontend/frontend.txt", facts);
    // Tests, comments and a map's get are not calls; a package without a framework is no app.
    assertThat(Fixtures.render(facts))
        .doesNotContain("test-only", "/nowhere", "frontend:shop/tools", "{key}");
  }

  @Test
  void aFrontendWithoutABuildAtTheTopOfItsRepositoryIsNamedByItsManifest() throws IOException {
    Repo repo =
        Fixtures.repo(
            temp.resolve("CRM-Frontend"),
            "CRM-Frontend",
            Map.of(
                "package.json",
                "{\"name\": \"crm-frontend\", \"dependencies\": {\"vue\": \"^3.4.21\"}}\n",
                "src/api.js",
                "export const me = () => fetch('https://api.crm.example.com/users/me')\n"));

    List<Fact> facts =
        new FrontendExtractor()
            .extract(List.of(new RepoBuild(repo, Fixtures.head(repo), null)), SNAPSHOT);

    assertThat(Fixtures.render(facts))
        .isEqualTo(
            """
            frontend:CRM-Frontend frontend-app vue {"directory":"","framework":"Vue","name":"crm-frontend","package":"crm-frontend","technology":"Vue 3.4","version":"3.4"} @CRM-Frontend:package.json#1-1 1.0
            frontend:CRM-Frontend frontend-call GET https://api.crm.example.com/users/me {"httpMethod":"GET","protocol":"HTTP","url":"https://api.crm.example.com/users/me","written":"'https://api.crm.example.com/users/me'"} @CRM-Frontend:src/api.js#1-1 0.9
            """);
  }

  @Test
  void aFallbackIsTheValueWhenTheEnvironmentVariableIsNotSet() throws IOException {
    Repo repo =
        Fixtures.repo(
            temp.resolve("portal"),
            "portal",
            Map.of(
                "package.json",
                "{\"name\": \"portal\", \"dependencies\": {\"react\": \"^19.2.0\"}}\n",
                "src/portal.tsx",
                """
                const URL = import.meta.env.VITE_BFF_URL ?? 'http://localhost:8085/graphql';
                const OTHER = import.meta.env.VITE_OTHER || import.meta.env.VITE_UNSET;
                export const book = () => fetch(URL, { method: 'POST' });
                export const other = () => fetch(OTHER);
                """));

    List<String> calls =
        Fixtures.render(
                new FrontendExtractor()
                    .extract(List.of(new RepoBuild(repo, Fixtures.head(repo), null)), SNAPSHOT))
            .lines()
            .filter(l -> l.contains(" frontend-call "))
            .toList();

    assertThat(calls).hasSize(1);
    assertThat(calls.getFirst()).contains("frontend-call POST http://localhost:8085/graphql ");
  }

  @Test
  void aDockerfileAtTheRootThatCopiesTheFolderServesTheFrontend() throws IOException {
    Repo repo =
        Fixtures.repo(
            temp.resolve("web"),
            "web",
            Map.of(
                "customer/package.json",
                "{\"name\": \"customer\", \"dependencies\": {\"react\": \"^19.2.0\"}}\n",
                "other/package.json",
                "{\"name\": \"other\", \"dependencies\": {\"react\": \"^19.2.0\"}}\n",
                "Dockerfile.customer",
                """
                FROM node:22 AS build
                COPY customer/ ./
                RUN npm run build
                FROM nginx:1.27.4-alpine
                COPY --from=build /dist /usr/share/nginx/html/customer
                COPY nginx.conf /etc/nginx/conf.d/default.conf
                EXPOSE 80
                """,
                "nginx.conf",
                """
                server {
                  location /api/ { proxy_pass http://edge-gateway:8080/api/; }
                }
                """));

    List<String> facts =
        Fixtures.render(
                new FrontendExtractor()
                    .extract(List.of(new RepoBuild(repo, Fixtures.head(repo), null)), SNAPSHOT))
            .lines()
            .toList();

    assertThat(facts)
        .anyMatch(
            l ->
                l.startsWith("frontend:web/customer frontend-proxy /api/ ")
                    && l.contains("\"target\":\"http://edge-gateway:8080/api/\"")
                    && l.contains("@web:nginx.conf#2-2"))
        .anyMatch(
            l ->
                l.startsWith("frontend:web/customer frontend-app ")
                    && l.contains("nginx:1.27.4-alpine"))
        // A folder no Dockerfile copies is not served by it.
        .noneMatch(l -> l.startsWith("frontend:web/other frontend-proxy"))
        .noneMatch(l -> l.startsWith("frontend:web/other frontend-app") && l.contains("nginx"));
  }

  @Test
  void brokenFilesAreSkippedWithoutFailing() throws IOException {
    Repo repo =
        Fixtures.repo(
            temp.resolve("broken"),
            "broken",
            Map.of(
                "bad/package.json",
                "{\"name\": \"bad\", \"dependencies\": {\"react\": ",
                "app/package.json",
                "{\"dependencies\": {\"react\": \"18\"}}\n",
                "app/angular.json",
                "{ not json",
                "app/src/a.ts",
                "fetch(`/api/${x(`\nconst ws = new WebSocket('ws://h:1'\nfetch(((("));

    List<Fact> facts =
        new FrontendExtractor()
            .extract(List.of(new RepoBuild(repo, Fixtures.head(repo), null)), SNAPSHOT);

    assertThat(Fixtures.render(facts))
        // An unterminated template literal runs to the end of the file, so nothing after it counts.
        .contains("frontend:broken/app frontend-app react")
        .doesNotContain("frontend:broken/bad");
  }

  @Test
  void expressionsAreReadWithTheirStringsAndBrackets() {
    String code = "get(`${a}/x/${f(b, c)}`, { p: 1 }), rest";
    assertThat(FrontendExtractor.argument(code, 4)).isEqualTo("`${a}/x/${f(b, c)}`");
    assertThat(FrontendExtractor.concatenated("a + 'b+c' + `${d + e}`"))
        .containsExactly("a", "'b+c'", "`${d + e}`");
    assertThat(FrontendExtractor.uncommented("x('http://a'); // y('b')\n/* z */ w"))
        .isEqualTo("x('http://a');" + " ".repeat(10) + "\n" + " ".repeat(8) + "w");
    assertThat(FrontendExtractor.url("api/x?q=1#top")).isEqualTo("/api/x");
    assertThat(FrontendExtractor.url("http://h:1//a//b")).isEqualTo("http://h:1/a/b");
    assertThat(FrontendExtractor.version("^19.2.0")).isEqualTo("19.2");
    assertThat(FrontendExtractor.version("7.x")).isEqualTo("7");
    assertThat(FrontendExtractor.unscoped("@acme/web")).isEqualTo("web");
  }

  @Test
  void angularGuardsInterceptorsPagesServicesAndLoadedUrlsAreRecorded() throws IOException {
    Repo repo =
        Fixtures.repo(
            temp.resolve("media"),
            "media",
            Map.of(
                "package.json",
                "{\"name\": \"media-ui\", \"dependencies\": {\"@angular/core\": \"^20.0.0\"}}",
                "src/app/app.routes.ts",
                """
                import { Routes } from '@angular/router';
                export const routes: Routes = [
                  { path: '', component: HomeComponent },
                  { path: 'catalog', component: CatalogComponent, canActivate: [authGuard] },
                ];
                """,
                "src/app/core/auth.guard.ts",
                """
                import { CanActivateFn, Router } from '@angular/router';

                export const authGuard: CanActivateFn = (route, state) => {
                  if (inject(AuthService).isLoggedIn()) {
                    return true;
                  }
                  inject(Router).navigate(['/login']);
                  return false;
                };
                """,
                "src/app/core/jwt.interceptor.ts",
                """
                import { HttpInterceptorFn } from '@angular/common/http';

                export const jwtInterceptor: HttpInterceptorFn = (req, next) => {
                  const token = localStorage.getItem('token');
                  return next(req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }));
                };
                """,
                "src/app/core/session.interceptor.ts",
                """
                @Injectable()
                export class SessionInterceptor implements HttpInterceptor {
                  intercept(req: HttpRequest<any>, next: HttpHandler) {
                    return next.handle(req.clone({ withCredentials: true }));
                  }
                }
                """,
                "src/app/services/media.ts",
                """
                @Injectable({ providedIn: 'root' })
                export class MediaService {
                  private http = inject(HttpClient);
                  private apiUrl = '/api/v1/media';

                  getAll() {
                    return this.http.get(this.apiUrl);
                  }
                }
                """,
                "src/app/features/catalog.ts",
                """
                @Component({
                  selector: 'app-catalog',
                  template: `
                    <img [src]="media.thumbnailUrl || 'https://picsum.photos/400/600?random=' + media.id">
                    <a href="https://example.org/help">Help</a>
                  `
                })
                export class CatalogComponent {
                  constructor(private media: MediaService) {}

                  load() {
                    this.media.getAll();
                  }
                }
                """,
                "src/index.html",
                "<link href=\"https://fonts.googleapis.com/css2?family=Inter\" rel=\"stylesheet\">\n"));
    List<Fact> facts =
        new FrontendExtractor()
            .extract(List.of(new RepoBuild(repo, Fixtures.head(repo), null)), SNAPSHOT);

    assertThat(Fixtures.render(facts).lines().filter(l -> !l.contains(" frontend-app ")))
        .containsExactlyInAnyOrder(
            "frontend:media frontend-auth guard {\"name\":\"authGuard\",\"redirect\":\"/login\","
                + "\"routes\":[\"/catalog\"],\"type\":\"CanActivate\"}"
                + " @media:src/app/core/auth.guard.ts#3-9 0.9",
            "frontend:media frontend-auth interceptor {\"bearer\":true,"
                + "\"headers\":[\"Authorization\"],\"name\":\"jwtInterceptor\","
                + "\"storage\":\"localStorage:token\"}"
                + " @media:src/app/core/jwt.interceptor.ts#3-6 0.9",
            "frontend:media frontend-auth interceptor {\"name\":\"SessionInterceptor\","
                + "\"withCredentials\":true} @media:src/app/core/session.interceptor.ts#1-6 0.9",
            "frontend:media frontend-component service {\"calls\":[\"HttpClient.get\"],"
                + "\"injects\":[\"HttpClient\"],\"name\":\"MediaService\"}"
                + " @media:src/app/services/media.ts#1-9 0.9",
            "frontend:media frontend-component component {\"calls\":[\"MediaService.getAll\"],"
                + "\"injects\":[\"MediaService\"],\"name\":\"CatalogComponent\","
                + "\"routes\":[\"/catalog\"],\"selector\":\"app-catalog\"}"
                + " @media:src/app/features/catalog.ts#1-14 0.9",
            "frontend:media frontend-call GET /api/v1/media {\"httpMethod\":\"GET\","
                + "\"protocol\":\"HTTP\",\"url\":\"/api/v1/media\",\"written\":\"this.apiUrl\"}"
                + " @media:src/app/services/media.ts#7-7 0.9",
            "frontend:media frontend-url /api/v1/media {\"name\":\"apiUrl\",\"source\":\"constant\"}"
                + " @media:src/app/services/media.ts#4-4 0.9",
            "frontend:media frontend-resource https://picsum.photos/400/600"
                + " {\"host\":\"picsum.photos\",\"perItem\":true,\"tag\":\"img\"}"
                + " @media:src/app/features/catalog.ts#4-4 0.9",
            "frontend:media frontend-resource https://fonts.googleapis.com/css2"
                + " {\"host\":\"fonts.googleapis.com\",\"tag\":\"link\"}"
                + " @media:src/index.html#1-1 0.9");
  }

  @Test
  void classGuardsHeaderCallsLazyRoutesStylesAndBindingsAreReadAndEdgeCasesAreNot()
      throws IOException {
    Repo repo =
        Fixtures.repo(
            temp.resolve("edges"),
            "edges",
            Map.of(
                "package.json",
                "{\"name\": \"edges\", \"dependencies\": {\"@angular/core\": \"^19.0.0\"}}",
                "src/app/app.routes.ts",
                """
                export const routes: Routes = [
                  {
                    data: { title: 'Admin' },
                    path: 'admin',
                    canMatch: [AdminGuard],
                    loadComponent: () => import('./admin').then(m => m.AdminComponent),
                  },
                ];
                """,
                "src/app/admin.guard.ts",
                """
                @Injectable({ providedIn: 'root' })
                export class AdminGuard implements CanMatch {
                  canMatch() {
                    return inject(Router).parseUrl('/forbidden');
                  }
                }
                """,
                "src/app/quick.guard.ts",
                "export const quickGuard: CanActivateFn = () => inject(Auth).ok();\n"
                    + "export function other() { inject(Router).navigate(['/elsewhere']); }\n",
                "src/app/tenant.interceptor.ts",
                """
                export const tenantInterceptor: HttpInterceptorFn = (req, next) => {
                  const headers = req.headers.set('X-Tenant', sessionStorage.getItem('tenant'));
                  return next(req.clone({ headers }));
                };
                """,
                "src/app/avatar.ts",
                """
                @Component({
                  selector: 'app-avatar',
                  template: `
                    <img [src]="user.avatar || 'https://cdn.example.com/default.png'">
                    <img src="https://img.example.com/{{user.id}}/200">
                    <!-- <script src="https://old.example.com/x.js"></script> -->
                  `
                })
                export class AvatarComponent {}
                """,
                "src/styles.scss",
                "body { background: url('https://images.example.com/bg.png'); }\n",
                "src/app/Card.vue",
                "<template><img :src=\"'https://thumbs.example.com/' + item.id\"></template>\n"));
    List<Fact> facts =
        new FrontendExtractor()
            .extract(List.of(new RepoBuild(repo, Fixtures.head(repo), null)), SNAPSHOT);
    List<String> lines =
        Fixtures.render(facts).lines().filter(l -> !l.contains(" frontend-app ")).toList();

    assertThat(lines)
        .contains(
            // A class guard, from its decorator, on a lazy route whose object has data first.
            "frontend:edges frontend-auth guard {\"name\":\"AdminGuard\",\"redirect\":\"/forbidden\","
                + "\"routes\":[\"/admin\"],\"type\":\"CanMatch\"}"
                + " @edges:src/app/admin.guard.ts#1-6 0.9",
            // An expression body ends at its semicolon, so the next function's navigate is not its.
            "frontend:edges frontend-auth guard {\"name\":\"quickGuard\",\"type\":\"CanActivate\"}"
                + " @edges:src/app/quick.guard.ts#1-1 0.9",
            "frontend:edges frontend-auth interceptor {\"headers\":[\"X-Tenant\"],"
                + "\"name\":\"tenantInterceptor\",\"storage\":\"sessionStorage:tenant\"}"
                + " @edges:src/app/tenant.interceptor.ts#1-4 0.9",
            // A fallback to a fixed URL is not per item; an interpolated one is, cut at the {{.
            "frontend:edges frontend-resource https://cdn.example.com/default.png"
                + " {\"host\":\"cdn.example.com\",\"tag\":\"img\"} @edges:src/app/avatar.ts#4-4 0.9",
            "frontend:edges frontend-resource https://img.example.com/"
                + " {\"host\":\"img.example.com\",\"perItem\":true,\"tag\":\"img\"}"
                + " @edges:src/app/avatar.ts#5-5 0.9",
            "frontend:edges frontend-resource https://images.example.com/bg.png"
                + " {\"host\":\"images.example.com\",\"tag\":\"css\"} @edges:src/styles.scss#1-1 0.9",
            "frontend:edges frontend-resource https://thumbs.example.com/"
                + " {\"host\":\"thumbs.example.com\",\"perItem\":true,\"tag\":\"img\"}"
                + " @edges:src/app/Card.vue#1-1 0.9");
    // The guard class is not also a service, and a commented-out tag loads nothing.
    assertThat(lines)
        .noneMatch(l -> l.contains("frontend-component service") && l.contains("AdminGuard"))
        .noneMatch(l -> l.contains("old.example.com"))
        .noneMatch(l -> l.contains("/elsewhere"));
  }
}
