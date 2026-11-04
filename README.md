# GuardLMS for self-hosted Sakai

A standalone Maven WAR providing a native Sakai administrator tool and daily
software inventory reporting. Targets **Sakai 25.2, Java 17 and Tomcat 9**.
Deploy the Sakai receiver changes in [GuardLMS](https://github.com/LdesignMedia/guardlms) before connecting.
This repository is private; ask LdesignMedia for access before installation.

## Install

Build with Java 17 and Maven 3.9+:

```sh
git clone git@github.com:LdesignMedia/sakai-guardlms.git
cd sakai-guardlms
mvn package
```

The output is `target/guardlms.war`. The provided Sakai APIs are compiled against
the stable 23.3 API because the published 25.2 descriptors refer to an unpublished
parent POM. These APIs are **not bundled** in the WAR. Runtime compatibility is
tested against the actual Sakai 25.2 release; this does not claim Sakai 23 support.
The WAR bundles Gson and its Error Prone annotation dependency; Sakai APIs remain provided by the server.

Add to your existing `sakai.properties` (do not replace the file):

```properties
guardlms.siteUrl=https://sakai.example.edu
guardlms.encryptionKey=REPLACE_WITH_A_BASE64_32_BYTE_KEY
```

Generate the key once with `openssl rand -base64 32`. Keep it in your server's
secret-managed configuration, preserve it during upgrades and back it up together
with the state directory. A changed key cannot decrypt existing credentials;
restore the original key or reconnect. URLs must use HTTPS, with no credentials,
query strings or fragments. `siteUrl` must exactly match the GuardLMS website URL,
including any explicit port. The root-context deployment uses the fixed callback
`<siteUrl>/guardlms/callback`; do not set `siteUrl` to `/portal`.

Stop Tomcat, copy `target/guardlms.war` to `$CATALINA_BASE/webapps/guardlms.war`,
and start Tomcat. When upgrading an existing exploded deployment, remove its old
`webapps/guardlms` directory while Tomcat is stopped so the WAR is expanded again.
Log in to Sakai as a superuser, then open `https://sakai.example.edu/guardlms/`.
The registered `guardlms.admin` tool is also available in Sakai's administrator
tool category for an administrator to place in an admin site. Endpoint access
checks always require a superuser, independently of tool placement.

Click **Connect to GuardLMS**, sign in to GuardLMS and approve. The callback exchanges
a short-lived, single-use code for an expiring, website-bound `inventory:write`
credential and immediately sends the first inventory. The settings page shows the
accepted component count, last success, expiry, next attempt and any reporting
error. **Preview inventory payload**, **Push inventory now**, and **Disconnect**
are available only to Sakai superusers. Mutations require a session-bound CSRF
token. Callback state is session-bound, expires after ten minutes, and cannot be
replayed or restored by a callback arriving after disconnect/reconnect.

If ownership remains pending in GuardLMS, complete its DNS or public HTML
verification instructions. The plugin displays the public verification token but
does not modify the portal's HTML head automatically. You may add the standard
`guardlms-verification` meta tag through your existing Sakai theme or
`portal.include.extrahead` configuration. Preserve any existing extra-head markup.

## Reporting and privacy

The complete inventory contains:

- Sakai's configured `version.sakai` (null when unavailable).
- Native `ToolManager` registry IDs, including stealthed installed tools; this is
  server registration, not the tools enabled in any particular course. `enabled`
  means registered/available in the runtime. Individual tool versions are null
  unless known; this connector reports its own version as `0.1.0`.
- Java version, Tomcat product/version, OS family/distribution/version and architecture.
- Database product/version from JDBC metadata, when available. No database URL,
  credentials, variables, queries or content are collected.

No learner, course, user, grade, site membership or tool configuration records are
read or transmitted. Plugin lists are never silently truncated; an incomplete or
oversized registry fails reporting so it cannot prune valid previous inventory.
Update availability stays **unknown**; registered tool IDs are not Maven dependency
coordinates, third-party plugin advisory identifiers, or proof that tools are current.
The connector does not inject the browser SDK, capture server errors, inspect JAR
contents or run configuration/security audits. Install the SDK separately if needed.

Reporting runs once per day while Sakai is running. A failed report retries after
one minute with exponential backoff capped at one hour. The native executor checks
every minute; no external cron is needed. Persistent state lives in
`<sakai.home>/guardlms` by default. Override it with:

```properties
guardlms.stateDirectory=/persistent/private/guardlms
# Optional, server-controlled endpoints:
guardlms.apiUrl=https://api.guardlms.com
guardlms.dashboardUrl=https://dashboard.guardlms.com
```

The directory must be writable by the Tomcat user on a POSIX filesystem supporting
atomic rename and advisory file locks. Credentials are AES-256-GCM encrypted;
files are owner-only. For multiple Sakai nodes, share **the same state directory
and encryption key**, with working cross-node locks. A separate lock file spans
configuration changes and network operations to prevent overlapping reports and
late writes after disconnect. Node-local state directories would report independently.
HTTP requests have a 30-second total deadline and a 256 KiB response limit;
redirects are refused. Normal JVM HTTPS trust and hostname validation stay enabled.

Disconnect erases the local credential and disables subsequent reports. It does
not delete the GuardLMS website or remotely revoke the old credential; remove it
in GuardLMS if necessary. Reconnect issues a fresh key and revokes old website keys.

## Tests and packaging

`mvn package` runs encryption, tamper detection, state-serialization and endpoint
validation tests before building the WAR. The real Sakai Docker/browser harness,
including receiver and consent tests, is maintained in the
[GuardLMS integration harness](https://github.com/LdesignMedia/guardlms/tree/main/integrations/sakai/docker).
Set `SAKAI_PLUGIN_ROOT` to this checkout when running that harness.
No GuardLMS application source or test secrets are stored in this repository.

To uninstall, stop Tomcat, remove the `guardlms.war` and exploded `guardlms`
webapp, remove any `guardlms.admin` placements and restart. Archive or remove its
private state directory and revoke its inventory key in GuardLMS.
