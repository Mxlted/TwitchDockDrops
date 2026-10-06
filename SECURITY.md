# Security

## Default deployment

Compose publishes the UI only on `127.0.0.1` when no `.env` is present. This is the safest default.
The UI has control over the Twitch session and miner, so access to the port should be treated like
access to the local desktop application.

Copying `.env.example` to `.env` explicitly enables trusted-LAN access: Compose publishes on every
host IPv4 interface and the application accepts literal RFC 1918, IPv4 link-local, IPv6 unique-local,
and IPv6 link-local server addresses. LAN-mode mutations are accepted only over HTTP when the Origin
host and port exactly match the request Host, which preserves same-origin protection without knowing
the Docker host's address in advance. DNS and mDNS names are not inferred; list those explicitly in
`TWITCH_DROPS_TRUSTED_HOSTS` and `TWITCH_DROPS_TRUSTED_ORIGINS` when needed.

LAN mode does not authenticate clients. Every device able to reach the port can control the miner and
saved Twitch session. Use it only on a trusted private network, keep host firewall rules in place, and
never port-forward the service. Guest Wi-Fi, shared networks, public interfaces, and internet access
require an authenticated HTTPS reverse proxy. Configure its external Host plus loopback in
`TWITCH_DROPS_TRUSTED_HOSTS` and its exact HTTPS origin in `TWITCH_DROPS_TRUSTED_ORIGINS`.

The application validates Host on every route and Origin on every mutation. It deliberately ignores
`Forwarded`, `X-Forwarded-Host`, and related headers rather than letting an unauthenticated client
redefine the trust boundary. The application does not provide user accounts, password authentication,
or TLS termination.

Direct JVM execution binds to `127.0.0.1` unless `TWITCH_DROPS_LISTEN_HOST` is explicitly changed.
Compose uses `0.0.0.0` for the container-internal listener. Host publication remains loopback-only
without `.env`; the supplied example changes host publication to `0.0.0.0` for explicit LAN use.

## Twitch credentials

- Fresh login uses either the optional Docker browser or an isolated desktop helper. With the desktop
  helper, passwords are entered directly on Twitch and never pass through the JVM. With dashboard
  login, input and browser screenshots pass through the same-origin JVM relay to the Docker browser;
  they are not logged or persisted. Existing device-authorized sessions remain supported. The optional
  experimental Android TV flow sends users to Twitch activation without collecting their password.
- API responses never include OAuth access/refresh tokens or encryption keys.
- Session data is encrypted with AES-256-GCM before it is written to `/data/session.enc`.
- Replacement login keeps the old encrypted credential until the new session is validated and
  atomically saved. Mining and refresh cannot use it while authorization is active; explicit reset
  still removes it. An interrupted or failed replacement can therefore restore the previous session
  after restart.
- When Twitch rejects a stored token as invalid, the runtime cancels session work and deletes the
  encrypted credential before exposing the expired state, except an experimental TV session retains
  its encrypted refresh credential for same-account renewal. Explicit reset still deletes it.
- Only HTTP 401 from authoritative token validation expires a session. A validation 403, temporary
  failure, malformed identity, or client mismatch preserves the encrypted credential. Validation
  checks the matching Android or web client ID and a positive numeric user ID; changing a client ID cannot convert
  a token. A Twitch GraphQL 401/403 or pure HTTP-200 authentication/integrity error first
  re-validates the token and expires the session only when validation confirms it is invalid. A watch
  beacon or HTML/JavaScript watch-configuration rejection cannot; those results invalidate or retry
  watch configuration while preserving the stored OAuth session.
- Device OAuth errors and JSON parsing failures use fixed diagnostics, never raw upstream text.
  Only this repository's helper uses its pairing protocol; upstream helper credentials
  are incompatible. Never paste raw cookies, tokens, or captured headers into the dashboard.
- Browser context is strictly bounded and encrypted with the session; state, events, logs, and errors
  exclude it. The server checks OAuth identity and both private Drops queries before accepting it.
  Expired proof blocks authenticated requests while preserving encrypted credentials.
- When independently verified, dashboard login stores one scoped SDK cookie in the encrypted context:
  `KP_UIDz-ssn`, exact host `k.twitchcdn.net`, secure/HttpOnly, path `/`. Only its bounded value and expiry are accepted;
  callers cannot supply a host, URL, cookie name or cookie jar. It is credential material, never public
  state. This replaces dependence on a continuously running signed-in browser and permits renewal
  after JVM/companion restart while the seed is fresh. The browser still receives no miner volume,
  encryption key or environment secrets; the JVM transfers only the renewal context over loopback.
- Each SDK renewal uses an owned temporary profile, a fixed empty Twitch-origin document and the fixed
  Twitch SDK URL. OAuth headers go only to Twitch's fixed integrity endpoint. Uncached network proof,
  token/expiry rotation, same-account OAuth validation and both Drops queries are required before
  atomic replacement. Transient failures retain the old encrypted seed; reset/replacement/shutdown
  invalidate late work. The private `/renew` route has a 40 KiB body limit and the existing internal
  Host/header/Origin boundary. Attempt IDs bind status and cleanup to the owned browser.
- Pairing uses a one-use 72-bit code, ten-minute deadline, and five-guess limit. The resulting 256-bit
  ticket stays only in helper/server memory and binds to the first accepted account. Successful renewal
  extends its lease by 24 hours; new pairing, reset, and server restart revoke it. A helper exit stops
  renewal but the ticket remains valid until expiry or revocation. Anyone with dashboard access can
  start pairing, so this does not add user authentication to trusted-LAN mode.
- The helper uses only its own temporary browser profile. Interactive login has no debugging port;
  regular-browser capture binds the browser control port to loopback with an allocated nonzero port.
  Other local processes under the same user can access that profile/control channel. Normal exit deletes the
  profile; crashes or forced termination may leave it in the OS temporary directory. Keep the helper
  computer trusted. HTTP uploads are restricted to private/loopback addresses; otherwise use verified
  HTTPS. TLS verification and redirect protection remain enabled.
- By default, a random key is stored alongside the encrypted session in the private named volume.
  This protects accidental disclosure of the session file alone, but not theft of the complete
  volume by a host administrator.
- For stronger separation, set `TWITCH_DROPS_SESSION_KEY` to a base64-encoded 32-byte key through a
  secret-management mechanism. Do not commit that value to `.env`.
- Corrupt encrypted sessions are quarantined with owner-only permissions when possible. A session
  that fails authentication under an explicitly configured key is preserved as a key mismatch so an
  operator can restore the correct key instead of losing the credential. Corrupt settings are also
  quarantined before defaults are used.

## Outbound token boundary

Experimental Android TV device login uses its own fixed client identity and TV Origin/user agent.
It never reuses tokens under another client. Refresh tokens are form fields to the fixed Twitch
OAuth token endpoint, never URLs, logs, or public state. Redirects and implicit transport retries
are disabled; ambiguous token rotation requires reconnecting instead of blindly replaying it.
Acceptance/rotation requires identity and both direct Drops queries before atomic encrypted save.
Browser login and its trust boundary remain available and unchanged. No SunkwiBOT catalog request
or transfer of account information to that service is implemented.

The server also sends an access token in LISTEN messages only to
`wss://pubsub-edge.twitch.tv/v1`. There is no user-configurable subscription URL or arbitrary topic
API; constructor-only loopback injection supports tests. Parsed notifications must match subscribed
topics. Messages exceeding 64 KiB of decoded text terminate the connection; this is an application
parser limit after OkHttp frame assembly, not a transport-level incoming allocation limit. Message
bodies and tokens are never logged or forwarded to the dashboard. Events only request authoritative
refresh; polling and guarded claims retain control.

Claim-history files contain bounded reward metadata and account IDs, not credentials or claim
instance secrets. They use owner-only atomic writes and are private local data. Corrupt files are
preserved rather than replaced with empty state, because lost pending intent could cause a replay.
History API serialization allowlists fields and enforces the existing trusted Host boundary.

OAuth credentials are sent only to fixed, trusted Twitch OAuth and GraphQL hosts plus narrowly
allowlisted Twitch watch-configuration and event destinations. Channel HTML, Twitch static
configuration, and Spade watch-event requests carry the authenticated session headers required to
attribute progress. A derived event URL is accepted only at `https://beacon.twitch.tv/track` or on the
legacy HTTPS `spade.twitch.tv` host. Static configuration is limited to hashed `/config/settings.*.js`
assets on `assets.twitch.tv` or legacy `static.twitchcdn.net`. Loopback URL injection exists only
through constructor parameters used by local MockWebServer tests; an arbitrary HTTPS URL is not
accepted. The Spade body also contains the numeric Twitch user ID required by the private event format;
it is sent only upstream and is never exposed through the browser API.

## Browser protections

Public category search uses an anonymous query to the fixed `https://gql.twitch.tv/gql` endpoint.
It sends only the search text and public client identifier, never saved OAuth credentials or cookies,
and does not affect authentication or mining state. Redirects are disabled. The Host-validated GET
route accepts one bounded query and an optional bounded opaque cursor for queries of four or more
characters. Short queries return up to 12 results; longer queries return at most 50 per requested page.
Concurrency, timeout, response size, page size, and the 32-page memory cache are capped. Pages are never
automatically crawled. Duplicate/unknown parameters and invalid or repeated cursors are rejected.
Upstream errors are replaced with fixed messages rather than exposing response bodies.

Mutation endpoints require a trusted Origin, strict typed JSON, a 64 KiB maximum body, and reject
unknown fields or wrong methods. Responses include a Content
Security Policy, clickjacking protection, MIME sniffing protection, and a restrictive referrer
policy. The static client escapes all remote Twitch text before rendering it.

State event streams are capped; excess tabs receive a structured 503 and fall back to sparse polling.
Mutable HTML, JavaScript, and CSS are revalidated rather than cached for an hour, preventing an old
client from being paired with a newer state schema after an upgrade.

These controls are defense in depth; they do not replace authentication when the service is exposed
to other machines.

## Container protections

The service runs as a dedicated non-root user, drops all Linux capabilities, enables
`no-new-privileges`, uses a read-only root filesystem, and keeps only `/data` and an in-memory `/tmp`
writable.

The optional `compose.browser.yaml` service has its own non-root UID, read-only root, dropped
capabilities, and `no-new-privileges`. It receives no miner environment secrets, data volume, Docker
socket, or host directories. It shares the app's network namespace so its control server binds only
to `127.0.0.1:8091`, with no new published ports. The browser control protocol and CDP remain internal.
The companion permits only an exact loopback Host, an internal request header, no Origin header, and
no CORS, including on read routes. A page cannot issue these requests without a rejected preflight.
Other processes in that namespace are trusted; these controls are not authentication against native
code already running there.

Chromium alone uses `--no-sandbox` inside this isolated companion because the retained
`no-new-privileges`/capability restrictions prevent its setuid sandbox from starting on common Docker
hosts. This is an explicit browser-only tradeoff: container isolation replaces the inner Chromium
sandbox, while the JVM hardening is unchanged. A compromised browser still has that container's
network reach and its own temporary login material; it does not gain filesystem access to the miner's
encrypted session or key. Keep the optional image current and use the desktop fallback if this tradeoff
does not fit the deployment. Do not grant privileged mode or mount the miner volume to fix startup.

The temporary profile is on a 512 MiB tmpfs and is removed on normal cancellation/exit; container removal
also discards it. Memory, shared memory, and process counts are bounded. Downloads are denied, arbitrary
CDP/navigation commands are not relayed, and credentials are not included in browser process arguments.
After Finish sign-in, the same headed browser completes capture. If a scoped SDK cookie is available,
a separate temporary headless browser tests independent issuance without closing the login browser
first. Missing seed material or failed issuance falls back to retaining the authenticated browser
inside the same isolated container. This avoids making SDK-cookie acquisition mandatory without
granting privileges or weakening account/Drops verification. All accepted contexts still require JVM
OAuth identity, Inventory and Campaigns validation; subsequent proof must differ and advance expiry.

The retained browser stays on tmpfs, with no durable profile, exported login cookie jar, or new port.
Its random private `browser_lease` is mutually exclusive with `sdk_cookie` in the encrypted context;
neither is public. Lease renewal is bound to the original OAuth/client/device and validated account.
Only the runtime schedules it. Fixed private `/release` and `/revoke` routes separate transfer cleanup
from disposal: revocation requires the matching lease, and stale transfer IDs cannot close a newer
browser. Reset, replacement, authoritative expiry and orderly shutdown revoke it and remove the owned
profile; Stop keeps renewal available. Browser restart requires login again in this mode. Transient
capture failure preserves credentials and the browser for retry. SDK renewal still creates/removes
an empty profile each time. Viewer/input routes are disabled outside interactive login. The network
observer retains bounded evidence only, and fixed errors never expose raw browser diagnostics.
The public viewer returns only fixed status fields and bounded JPEG frames with `no-store`; its
mutation routes retain normal Host/Origin, JSON, and size checks. Passwords entered into this viewer
traverse the dashboard connection, so trusted-LAN HTTP has the same local-network exposure as other
unencrypted traffic; use the documented authenticated HTTPS proxy on shared networks. Do not enable
request-body logging at a proxy. The viewer has access to the same Twitch account as the dashboard.

## Reporting and logs

Local logs may contain Twitch user IDs, campaign names, channel names, and bounded error summaries.
Every entry normalizes CR/LF, is length-limited, and passes credential-label redaction before the
bounded file is appended; bounds are restored with an atomic compacting rewrite. Redaction recognizes
both plain diagnostic labels and quoted JSON-style credential labels. Logs must not contain access tokens, device-code secrets,
encryption keys, raw session content, full upstream bodies, or filesystem paths. Review logs before
sharing them publicly.

Do not paste OAuth tokens, session files, encryption keys, or full volume backups into issues or AI
prompts.
