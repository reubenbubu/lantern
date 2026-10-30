package io.lantern.extract.frontend;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads, as text, what a frontend's code says beyond its calls: how it signs requests and guards
 * its pages (Angular interceptors and guards), its Angular pages and services with what they inject
 * and call, and the absolute URLs its templates and styles load. See {@code
 * docs/adr/0083-frontend-containers.md}, amendment for #394.
 */
final class FrontendCode {

  /** A part of a file: what it is, its name, where it starts and ends, and what it says. */
  record Part(String kind, String name, int start, int end, Map<String, Object> attributes) {}

  /** A URL a template or style loads: where, the tag, the URL without its query, and more. */
  record Resource(int offset, String tag, String url, String host, boolean perItem) {}

  private static final Pattern FUNCTIONAL_INTERCEPTOR =
      Pattern.compile("\\bconst\\s+([A-Za-z_$][\\w$]*)\\s*:\\s*HttpInterceptorFn\\s*=");
  private static final Pattern CLASS_INTERCEPTOR =
      Pattern.compile(
          "\\bclass\\s+([A-Za-z_$][\\w$]*)\\s+implements\\s+[^{]*\\bHttpInterceptor\\b");
  private static final Pattern FUNCTIONAL_GUARD =
      Pattern.compile(
          "\\bconst\\s+([A-Za-z_$][\\w$]*)\\s*:\\s*"
              + "(CanActivateFn|CanActivateChildFn|CanMatchFn|CanDeactivateFn)\\b[^=]*=");
  private static final Pattern CLASS_GUARD =
      Pattern.compile(
          "\\bclass\\s+([A-Za-z_$][\\w$]*)\\s+implements\\s+[^{]*\\b"
              + "(CanActivate|CanActivateChild|CanMatch|CanDeactivate)\\b");
  private static final Pattern DECORATOR = Pattern.compile("@(Component|Injectable)\\s*\\(");
  private static final Pattern IMPLEMENTS_AUTH =
      Pattern.compile(
          "\\bimplements\\b.*\\b(HttpInterceptor|CanActivate|CanActivateChild|CanMatch|CanDeactivate)\\b");
  private static final Pattern CLASS = Pattern.compile("\\bclass\\s+([A-Za-z_$][\\w$]*)");
  private static final Pattern SELECTOR =
      Pattern.compile("\\bselector\\s*:\\s*['\"]([^'\"]+)['\"]");

  /** A field set by {@code inject(X)}: {@code private users = inject(UserService);}. */
  private static final Pattern INJECTED_FIELD =
      Pattern.compile("\\b([A-Za-z_$][\\w$]*)\\s*(?::[^=;]+)?=\\s*inject\\(\\s*([A-Z][\\w$]*)");

  /** A constructor parameter with a type: {@code private users: UserService}. */
  private static final Pattern CONSTRUCTOR_PARAMETER =
      Pattern.compile(
          "(?:(?:private|public|protected|readonly)\\s+)+([A-Za-z_$][\\w$]*)\\s*:\\s*([A-Z][\\w$]*)");

  private static final Pattern CONSTRUCTOR = Pattern.compile("\\bconstructor\\s*\\(");
  private static final Pattern MEMBER_CALL =
      Pattern.compile("\\bthis\\s*\\.\\s*([A-Za-z_$][\\w$]*)\\s*\\.\\s*([A-Za-z_$][\\w$]*)\\s*\\(");
  private static final Pattern SET_HEADERS = Pattern.compile("\\bsetHeaders\\s*:\\s*\\{");
  private static final Pattern HEADER_KEY =
      Pattern.compile("(?:^|[,{])\\s*['\"]?([A-Za-z][\\w-]*)['\"]?\\s*:");
  private static final Pattern HEADER_SET =
      Pattern.compile("\\bheaders\\s*\\.\\s*(?:set|append)\\s*\\(\\s*['\"]([^'\"]+)['\"]");
  private static final Pattern STORAGE =
      Pattern.compile(
          "\\b(localStorage|sessionStorage)\\s*\\.\\s*getItem\\s*\\(\\s*['\"]([^'\"]+)");
  private static final Pattern REDIRECT =
      Pattern.compile(
          "\\b(?:navigate|navigateByUrl|createUrlTree|parseUrl)\\s*\\(\\s*\\[?\\s*['\"]([^'\"]+)['\"]");

  /** A route's guards: {@code canActivate: [authGuard]}. */
  private static final Pattern ROUTE_GUARDS =
      Pattern.compile(
          "\\b(canActivate|canActivateChild|canMatch|canDeactivate)\\s*:\\s*\\[([^\\]]*)]");

  private static final Pattern ROUTE_PATH = Pattern.compile("\\bpath\\s*:\\s*['\"]([^'\"]*)['\"]");
  private static final Pattern ROUTE_COMPONENT =
      Pattern.compile("\\bcomponent\\s*:\\s*([A-Za-z_$][\\w$]*)|\\bm\\s*\\.\\s*([A-Z][\\w$]*)");

  /** An attribute that loads a URL: {@code src}, {@code href} and their bindings. */
  private static final Pattern URL_ATTRIBUTE =
      Pattern.compile(
          "(?<![\\w-])(?:\\[|:|v-bind:)?(src|href|srcset|poster|data)\\]?\\s*=\\s*"
              + "(\"[^\"]*\"|'[^']*'|\\{[^}\\n]*})");

  private static final Pattern CSS_URL =
      Pattern.compile("\\burl\\(\\s*['\"]?(https?://[^'\")\\s]+)");
  private static final Pattern ABSOLUTE_URL = Pattern.compile("https?://[^\\s'\"`)}<>]+");
  private static final Pattern TAG = Pattern.compile("<\\s*([A-Za-z][\\w-]*)");

  /** Tags whose URL the browser loads by itself, not one a user follows. */
  private static final Set<String> LOADING_TAGS =
      Set.of(
          "img", "script", "link", "iframe", "source", "video", "audio", "embed", "object", "image",
          "track");

  private FrontendCode() {}

  /** The interceptors and guards of a file, each from its declaration to the end of its body. */
  static List<Part> auth(String text) {
    List<Part> parts = new ArrayList<>();
    for (Pattern p : List.of(FUNCTIONAL_INTERCEPTOR, CLASS_INTERCEPTOR)) {
      Matcher m = p.matcher(text);
      while (m.find()) {
        int end = bodyEnd(text, m.end());
        String body = text.substring(m.end(), end);
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("name", m.group(1));
        Set<String> headers = new LinkedHashSet<>();
        Matcher set = SET_HEADERS.matcher(body);
        while (set.find()) {
          int close = FrontendExtractor.closing(body, set.end() - 1);
          String block = body.substring(set.end(), close < 0 ? body.length() : close);
          Matcher key = HEADER_KEY.matcher(block);
          while (key.find()) {
            headers.add(key.group(1));
          }
        }
        Matcher header = HEADER_SET.matcher(body);
        while (header.find()) {
          headers.add(header.group(1));
        }
        if (!headers.isEmpty()) {
          attributes.put("headers", List.copyOf(headers));
        }
        if (body.contains("Bearer")) {
          attributes.put("bearer", true);
        }
        if (body.matches("(?s).*\\bwithCredentials\\s*:\\s*true\\b.*")) {
          attributes.put("withCredentials", true);
        }
        Matcher storage = STORAGE.matcher(body);
        if (storage.find()) {
          attributes.put("storage", storage.group(1) + ":" + storage.group(2));
        }
        parts.add(new Part("interceptor", m.group(1), m.start(), end, attributes));
      }
    }
    for (Pattern p : List.of(FUNCTIONAL_GUARD, CLASS_GUARD)) {
      Matcher m = p.matcher(text);
      while (m.find()) {
        int end = bodyEnd(text, m.end());
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("name", m.group(1));
        attributes.put("type", m.group(2).replaceAll("Fn$", ""));
        Matcher redirect = REDIRECT.matcher(text.substring(m.end(), end));
        if (redirect.find()) {
          attributes.put("redirect", redirect.group(1));
        }
        parts.add(new Part("guard", m.group(1), m.start(), end, attributes));
      }
    }
    return parts;
  }

  /**
   * Angular's pages and services in a file: each {@code @Component} or {@code @Injectable} class
   * from its decorator to the end of its body, with what it injects and the methods it calls on
   * what it injects.
   */
  static List<Part> components(String text) {
    List<Part> parts = new ArrayList<>();
    Matcher m = DECORATOR.matcher(text);
    while (m.find()) {
      int close = FrontendExtractor.closing(text, m.end() - 1);
      if (close < 0) {
        continue;
      }
      Matcher type = CLASS.matcher(text);
      if (!type.find(close)) {
        continue;
      }
      int end = bodyEnd(text, type.end());
      int open = text.indexOf('{', type.end());
      if (open >= 0 && IMPLEMENTS_AUTH.matcher(text.substring(type.end(), open)).find()) {
        // An interceptor or guard class is recorded as such, not as a service.
        continue;
      }
      String body = text.substring(type.end(), end);
      Map<String, Object> attributes = new LinkedHashMap<>();
      attributes.put("name", type.group(1));
      Matcher selector = SELECTOR.matcher(text.substring(m.end(), close));
      if (selector.find()) {
        attributes.put("selector", selector.group(1));
      }
      // Field or parameter name to the type injected.
      Map<String, String> injected = new LinkedHashMap<>();
      Matcher field = INJECTED_FIELD.matcher(body);
      while (field.find()) {
        injected.putIfAbsent(field.group(1), field.group(2));
      }
      Matcher constructor = CONSTRUCTOR.matcher(body);
      if (constructor.find()) {
        int closing = FrontendExtractor.closing(body, constructor.end() - 1);
        String parameters =
            body.substring(constructor.end(), closing < 0 ? body.length() : closing);
        Matcher parameter = CONSTRUCTOR_PARAMETER.matcher(parameters);
        while (parameter.find()) {
          injected.putIfAbsent(parameter.group(1), parameter.group(2));
        }
      }
      if (!injected.isEmpty()) {
        attributes.put("injects", List.copyOf(new LinkedHashSet<>(injected.values())));
      }
      Set<String> calls = new LinkedHashSet<>();
      Matcher call = MEMBER_CALL.matcher(body);
      while (call.find()) {
        String injectedType = injected.get(call.group(1));
        if (injectedType != null) {
          calls.add(injectedType + "." + call.group(2));
        }
      }
      if (!calls.isEmpty()) {
        attributes.put("calls", List.copyOf(calls));
      }
      String kind = m.group(1).equals("Component") ? "component" : "service";
      parts.add(new Part(kind, type.group(1), m.start(), end, attributes));
    }
    return parts;
  }

  /**
   * The route paths of a routes file by what each route names: its component, by name, and its
   * guards, each prefixed {@code guard:}.
   */
  static Map<String, Set<String>> routes(String text) {
    Map<String, Set<String>> routes = new LinkedHashMap<>();
    Matcher m = ROUTE_PATH.matcher(text);
    while (m.find()) {
      int open = text.lastIndexOf('{', m.start());
      if (open < 0) {
        continue;
      }
      int close = FrontendExtractor.closing(text, open);
      // The route's own properties: its children are left to their own paths.
      String route = text.substring(open, close < 0 ? text.length() : close);
      int children = route.indexOf("children");
      if (children >= 0) {
        route = route.substring(0, children);
      }
      String path = "/" + m.group(1);
      Matcher component = ROUTE_COMPONENT.matcher(route);
      if (component.find()) {
        String name = component.group(1) != null ? component.group(1) : component.group(2);
        routes.computeIfAbsent(name, k -> new LinkedHashSet<>()).add(path);
      }
      Matcher guards = ROUTE_GUARDS.matcher(route);
      while (guards.find()) {
        for (String guard : guards.group(2).split(",")) {
          String name = guard.trim().replaceAll("\\(.*$", "");
          if (!name.isEmpty()) {
            routes.computeIfAbsent("guard:" + name, k -> new LinkedHashSet<>()).add(path);
          }
        }
      }
    }
    return routes;
  }

  /**
   * The absolute URLs a file's templates and styles load: in the {@code src}, {@code href} and
   * similar attributes of tags that load what they name (not links a user follows), and in CSS
   * {@code url()}. A URL joined to an expression in a binding is loaded per item.
   */
  static List<Resource> resources(String text) {
    List<Resource> resources = new ArrayList<>();
    Matcher m = URL_ATTRIBUTE.matcher(text);
    while (m.find()) {
      String tag = tag(text, m.start());
      if (tag == null || !LOADING_TAGS.contains(tag)) {
        continue;
      }
      String value = m.group(2).substring(1, m.group(2).length() - 1);
      Matcher url = ABSOLUTE_URL.matcher(value);
      while (url.find()) {
        String rest = value.substring(0, url.start()) + value.substring(url.end());
        boolean perItem =
            m.group(0).startsWith("[")
                    && rest.replaceAll("['\"`\\s]", "").replaceAll("^\\|\\|", "").length() > 0
                || value.contains("${")
                || value.contains("{{")
                || m.group(2).startsWith("{");
        add(resources, m.start(2) + url.start() + 1, tag, url.group(), perItem);
      }
    }
    m = CSS_URL.matcher(text);
    while (m.find()) {
      add(resources, m.start(1), "css", m.group(1), false);
    }
    return resources;
  }

  private static void add(
      List<Resource> resources, int offset, String tag, String url, boolean perItem) {
    String plain = url.replaceAll("[?#].*$", "").replaceAll("[.,;]$", "");
    Matcher host = Pattern.compile("^https?://([^/:]+)").matcher(plain);
    if (host.find() && host.group(1).contains(".")) {
      resources.add(
          new Resource(offset, tag, plain, host.group(1).toLowerCase(Locale.ROOT), perItem));
    }
  }

  /** The name of the tag an attribute at an offset belongs to, or null outside a tag. */
  private static String tag(String text, int offset) {
    int open = text.lastIndexOf('<', offset);
    if (open < 0 || text.lastIndexOf('>', offset) > open) {
      return null;
    }
    Matcher m = TAG.matcher(text);
    return m.find(open) && m.start() == open ? m.group(1).toLowerCase(Locale.ROOT) : null;
  }

  /** The end of the body that opens at the first brace after an offset, or the text's end. */
  private static int bodyEnd(String text, int from) {
    int open = text.indexOf('{', from);
    if (open < 0) {
      return text.length();
    }
    int close = FrontendExtractor.closing(text, open);
    return close < 0 ? text.length() : close + 1;
  }
}
