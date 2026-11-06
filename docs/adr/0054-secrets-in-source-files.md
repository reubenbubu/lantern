# 0054: Secrets in the source files shown to the LLM

Status: accepted (#189)

## Context

Type prompts show whole source files (ADR 0024). The config extractor keeps secrets out of its
facts (ADR 0032), but a credential written in Java code, such as `String apiKey = "sk-..."` or a
database URL with a password, was sent to the LLM provider as written.

## Decisions

- **Source files are scrubbed before they are shown**, with the config extractor's rules. The
  summarizer checks the file against the evidence's content hash as read, then passes its text
  through `SourceSecrets` (in `lantern-extract`, next to `UrlCredentials`); `lantern-summarize`
  now depends on `lantern-extract` for it (amending ADR 0010). Files are scrubbed, not skipped, so the model still sees
  the code. Masking a text block keeps its line breaks.
- **A string literal is masked (`"******"`) when a secret's name gives it its meaning**: the
  variable, field, constant or annotation element it is assigned to, the method or constructor it
  is an argument of (`setPassword(...)`, `.apiKey(...)`, `new Credentials(...)`), the literal or constant key it
  follows (`put("db.password", ...)`, `Map.of("api-key", ...)`,
  `set(HttpHeaders.AUTHORIZATION, ...)`), the array it initializes, or the
  variable it is compared with (`password.equals(...)`). A name is a secret's when a config key
  would be (`ConfigExtractor.isSecret`: `password`, `apiKey`, `accessToken`, `DB_PWD`, ...), or it
  is `authorization` or `passphrase`, which `UrlCredentials` already treats as secret. Literals
  joined with `+` count as one. An empty literal, or one that is only placeholders without defaults
  (`"${DB_PASSWORD}"`), is kept, as a secret key's value is, since it says where the secret comes
  from. Removing too much is the safe side: `new BadCredentialsException("...")` loses its message.
- **Every other literal, and the text of every comment, goes through `UrlCredentials`**: credentials in URLs,
  `user:password@host` and secret options (`password="x"`) are removed. The default of a
  placeholder naming a secret is masked (`${db.password:******}`), and a literal that still looks
  like a URL holding a credential is masked whole, as config values are.
- **A literal holding a PEM private key** (`-----BEGIN ... PRIVATE KEY-----`) is masked whatever it
  is named; config values have no such rule, but a key in code is rarely named as one.
- **The prompt version becomes 5**, so summaries made from unscrubbed files are not reused.

## Not covered

A secret not named as one (`signWith(alg, "my-secret")`), returned from a method
(`getPassword() { return "x"; }`), or written as characters or bytes (`{'p', 'w'}`) is still
shown, and so is one named as a secret in a form not read: inside JSON or YAML in a literal
(`"{\"password\":\"x\"}"`, `password: x`), nested in another expression (`setPassword(new
String("x"))`, `prefix + "x"`, `c ? "a" : "b"`, `() -> "x"`, `new String[] {"x"}`), as an
annotation default (`String password() default "x"`), or compared through `Objects.equals` or
`this.password`. Tokens recognised by their shape alone (high entropy, provider prefixes) are not detected.

## Amendment (#912): a placeholder keeps its variable name

A secret's value that holds placeholders was masked whole, so `bff.jwt.signing-key=${BFF_JWT_SIGNING_KEY:dev-key}`
was shown as `bff.jwt.signing-key=******` and a model could not tell a value taken from the
environment from one committed in the file, which is what a plaintext-secret lead asks. Now
(`SecretPlaceholders`), in config, `.env` and Compose files and in a one-line Java literal named
as a secret, each placeholder keeps its name and only what is written is masked:

- `${NAME:default}` shows as `${NAME:******}`; the shell's `${NAME:-default}`, `${NAME:?message}`
  and `${NAME:=default}` keep their sign (`${NAME:-******}`); nested placeholders are read
  (`${OUTER:${INNER:******}}`); an empty default stays empty; text outside a placeholder is
  masked (`******${TOKEN}******`).
- A name is kept only if it is an environment variable (`[A-Za-z_][A-Za-z0-9_]*`) or a Spring
  property, which alone may hold a hyphen since it holds a dot (`app.jwt-secret`). Anything else,
  `${DB-hunter2}` (the shell's default without a colon) or an unclosed placeholder, is masked whole,
  so a default is never passed off as a name. Before, a value of placeholders without defaults was
  kept whatever their names, so `${DB-hunter2}` was shown; it no longer is.
- Under a key not named as a secret, a placeholder named as one has its default masked, in config
  as already in code (`other: ${A_TOKEN:-******}`).

The masked-values list (ADR 0110, amendment for #645) is taken from the difference of the file and
its masked text, so it holds each masked default as before. On project-8, nine secret lines of three
services now show their variable (`password: ${RABBITMQ_PASSWORD:******}`).
