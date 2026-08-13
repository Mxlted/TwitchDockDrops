# Security

## Default deployment

Compose publishes the UI only on `127.0.0.1`. This is the supported safe default. The UI has control
over the Twitch session and miner, so access to the port should be treated like access to the local
desktop application.

If you change `TWITCH_DROPS_BIND` to `0.0.0.0` or a LAN address, put an authenticated HTTPS reverse
proxy in front of the service and use host firewall rules. Also configure
`TWITCH_DROPS_TRUSTED_HOSTS` with the external Host plus loopback for the container health check, and
`TWITCH_DROPS_TRUSTED_ORIGINS` with the exact external HTTPS origin. The application validates Host
on every route and Origin on every mutation. It deliberately ignores `Forwarded`,
`X-Forwarded-Host`, and related headers rather than letting an unauthenticated client redefine the
trust boundary. The application does not provide user accounts, password authentication, or TLS
termination.

Direct JVM execution binds to `127.0.0.1` unless `TWITCH_DROPS_LISTEN_HOST` is explicitly changed.
Compose uses `0.0.0.0` only for the container-internal listener and continues to publish the host port
on `127.0.0.1` by default.

## Twitch credentials

- The app uses Twitch device authorization and never receives a Twitch password.
- API responses never include the OAuth access token or encryption key.
- Session data is encrypted with AES-256-GCM before it is written to `/data/session.enc`.
- When Twitch rejects a stored token as invalid, the runtime cancels session work and deletes the
  encrypted credential before exposing the expired state to the browser.
- A 401/403 from the authoritative token-validation or Twitch GraphQL API can expire a session. A
  watch beacon or HTML/JavaScript watch-configuration rejection cannot; those results invalidate or
  retry watch configuration while preserving the stored OAuth session.
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

## Reporting and logs

Local logs may contain Twitch user IDs, campaign names, channel names, and bounded error summaries.
Every entry normalizes CR/LF, is length-limited, and passes credential-label redaction before the
bounded file is atomically rewritten. Redaction recognizes both plain diagnostic labels and quoted
JSON-style credential labels. Logs must not contain access tokens, device-code secrets,
encryption keys, raw session content, full upstream bodies, or filesystem paths. Review logs before
sharing them publicly.

Do not paste OAuth tokens, session files, encryption keys, or full volume backups into issues or AI
prompts.
