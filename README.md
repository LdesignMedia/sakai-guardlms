# GuardLMS for Sakai

[![CI](https://github.com/LdesignMedia/sakai-guardlms/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/LdesignMedia/sakai-guardlms/actions/workflows/ci.yml)

Connect your self-hosted Sakai server to [GuardLMS](https://guardlms.com) and keep
its software inventory up to date automatically.

The connector provides a native Sakai administrator tool with the same
**Connect to GuardLMS** option as the [Moodle plugin](https://github.com/LdesignMedia/moodle-local_guardlms):
click Connect, sign in or create an account, approve, and return to Sakai.
GuardLMS registers the site, installs a site-bound inventory key and receives the
first report. **There is no API key to create or copy.**

This is a private repository. Ask LdesignMedia for access before installing.

## What the connector does

- **One-click connection.** Connect, reconnect and disconnect from Sakai without
  managing a GuardLMS API key yourself.
- **Daily reporting.** Reports Sakai, installed tool registrations, Java, Tomcat,
  operating system and database versions while the server is running.
- **Clear connection health.** Shows the last accepted inventory, next attempt,
  component count and key expiry. A refused or expired key displays
  **Reconnect required**, including the date of the first refused push.
- **On-demand controls.** Send an inventory immediately, preview the exact payload,
  or open the connected website in GuardLMS.
- **Private by design.** Inventory collection excludes learner records, courses,
  grades, memberships and tool configuration. Stored inventory keys are encrypted.

## Requirements

| Requirement | Supported / tested |
| --- | --- |
| Sakai | Self-hosted **25.2** |
| Java | **17** |
| Servlet container | **Tomcat 9** |
| Build tool | Maven **3.9+**, when building from source |
| Server access | Required to configure Sakai and deploy a WAR |
| Site address | The canonical public HTTPS origin, such as `https://sakai.example.edu` |
| Persistent storage | A private, writable POSIX directory with file locking and atomic rename |

Sakai runs at its normal root deployment, with the portal at `/portal` and this
tool at `/guardlms/`. Put the site's origin in the configuration, not its `/portal`
URL. Custom Sakai context-path deployments have not been validated.

The Sakai receiver and consent support must be deployed in GuardLMS before the
connector can connect. See [GuardLMS PR #2878](https://github.com/LdesignMedia/guardlms/pull/2878)
for that integration.

## Install

### 1. Obtain the WAR

Download **`guardlms.war`** from the
[latest release](https://github.com/LdesignMedia/sakai-guardlms/releases/latest).
No Java or Maven installation is needed on your workstation. Repository access
is required to download releases from this private repository.

Every successful CI build after a merge or push to `main` publishes a release
tagged `build-<run number>`, with the tested WAR and `guardlms.war.sha256`.
Release notes identify the exact source commit and CI run. Download both files
to the same directory to verify the checksum:

```sh
sha256sum --check guardlms.war.sha256
```

On macOS, use `shasum -a 256 --check guardlms.war.sha256` instead.

Alternatively, build from source with Java 17 and Maven 3.9+:

```sh
git clone git@github.com:LdesignMedia/sakai-guardlms.git
cd sakai-guardlms
mvn verify
```

The locally built file is **`target/guardlms.war`**. A successful
[CI run](https://github.com/LdesignMedia/sakai-guardlms/actions/workflows/ci.yml)
also provides a `guardlms-sakai-war` artifact containing the WAR and checksum for
14 days, including pull request builds. Check which branch and commit the
artifact was built from; pull request builds do not publish releases.

### 2. Configure Sakai once

Generate a server encryption key:

```sh
openssl rand -base64 32
```

Add these settings to your existing `sakai.properties`:

```properties
guardlms.siteUrl=https://sakai.example.edu
guardlms.encryptionKey=PASTE_THE_GENERATED_BASE64_KEY_HERE
```

The encryption key protects the local inventory credential. It is a **server
secret**, not a GuardLMS API key or a key obtained from the dashboard. Keep it in
your secret-managed Sakai configuration, preserve it during upgrades, and back it
up with the state directory. Do not commit it to Git.

Use the same canonical HTTPS origin that will be registered in GuardLMS,
including an explicit port if your site uses one. Do not include credentials,
query strings, fragments, `/portal`, or `/guardlms` in `guardlms.siteUrl`.

### 3. Deploy to Tomcat

Stop Tomcat using your usual service-management procedure, then copy the WAR:

```sh
cp guardlms.war "$CATALINA_BASE/webapps/guardlms.war"
```

When building from source, use `target/guardlms.war` as the source path instead.

Start Tomcat again. Log in to Sakai as a superuser and open:

```text
https://sakai.example.edu/guardlms/
```

You can also place the registered `guardlms.admin` tool in an administrator site
using Sakai's administrator tool category. Every endpoint requires a superuser,
regardless of where the tool is placed.

## Connect to GuardLMS

1. Open **GuardLMS for Sakai** and click **Connect to GuardLMS**.
2. Sign in to GuardLMS, or create an account, review the site and approve.
3. You return to Sakai with a connection confirmation and the first inventory
   result. Sakai sends subsequent inventories daily; no external cron is needed.

The connection uses the same consent and server-to-server code exchange as
`local_guardlms`. The browser never receives the inventory key. After connecting,
the primary action becomes **Reconnect**, alongside **Push inventory now**,
**Disconnect** and **Open in GuardLMS**. Payload preview and verification details
are available under **Advanced connection details**.

### Connection status

| Status | Meaning / action |
| --- | --- |
| **Not connected** | No connection is installed for this site. Click **Connect to GuardLMS**. |
| **Connected** | A connection is installed. Check the last accepted inventory and any reporting warning for its current delivery status. |
| **Reconnect required** | GuardLMS refused the key with HTTP 401/403, or the key has expired. Click **Reconnect** to install a fresh key. |

A refused key is retained so a temporary service problem can recover on the next
accepted push. The first-refusal date stays visible until a push succeeds or the
site is reconnected. A network error or HTTP 5xx shows a reporting warning and
schedules a retry without discarding the connection.

A failed first inventory does not undo a successful connection. Sakai shows the
failure, keeps the installed key and retries automatically. If the connection
exchange itself fails, you return to the settings page with a message and can
start a fresh attempt.

**Disconnect** removes the local credential and stops reporting. It does not
delete the website from GuardLMS or revoke the old inventory key remotely. Revoke
it in GuardLMS if needed; reconnecting automatically replaces previous inventory
keys for this website.

### Site ownership

Sakai uses GuardLMS's normal server-based ownership check during connection. If
ownership remains pending, complete DNS or public HTML verification in GuardLMS.
Unlike the Moodle plugin, this connector does not automatically add a verification
meta tag to Sakai's portal.

The public ownership token is available under **Advanced connection details**.
It can be added to your portal's HTML head through your existing Sakai theme or
`portal.include.extrahead` setting. Preserve any markup already configured there.

## What is reported

| Inventory | Contents |
| --- | --- |
| Sakai | Configured `version.sakai`; unknown if unavailable |
| Registered tools | Complete native `ToolManager` registry, including stealthed tools; registry ID and known version |
| Runtime | Java version and Tomcat product/version |
| Server | Operating system family/distribution/version and architecture |
| Database | Product/version from JDBC metadata, when available |

A registered tool is a server installation, not evidence that it is enabled in
any particular course. In the payload, `enabled` means registered in the runtime.
Individual tool versions remain unknown unless explicitly known; the connector
reports its own version as `0.1.0`. Update availability also remains unknown.
Registry IDs are not Maven package coordinates or proof that a tool is current.

Inventory collection does not read or transmit learner, course, user, grade or
membership records, database URLs, credentials, database variables, tool settings
or other Sakai configuration. An incomplete or oversized registry fails reporting
rather than silently removing previously reported tools.

This release reports inventory. Browser SDK injection, real-time error capture,
JAR dependency analysis and configuration auditing are separate capabilities and
are not automatically enabled by connecting this plugin.

## Advanced server configuration

```properties
# Optional: defaults to <sakai.home>/guardlms.
guardlms.stateDirectory=/persistent/private/guardlms

# Optional: use the standard GuardLMS endpoints unless told otherwise.
guardlms.apiUrl=https://api.guardlms.com
guardlms.dashboardUrl=https://dashboard.guardlms.com
```

The state directory is private to the Tomcat user; credentials use AES-256-GCM
encryption and files are owner-only. For a Sakai cluster, all nodes must share the
**same state directory and encryption key**, with working cross-node advisory
locks and atomic rename. Node-local directories would report independently.

The native executor checks every minute. Accepted reports schedule the next push
one day later. Failed reports retry after one minute with exponential backoff
capped at one hour. A shared lock prevents overlapping reports and late callbacks
from restoring a connection after disconnect/reconnect.

Requests use normal JVM certificate and hostname validation, a 30-second deadline
and a 256 KiB response limit. Credential-bearing redirects are refused. Connection
state is bound to the Sakai admin session and expires after ten minutes. Connect,
manual push and disconnect require a session CSRF token.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| `/guardlms/` is unavailable | Confirm the WAR was deployed and inspect Tomcat startup logs. Check the HTTPS site URL, encryption key and directory permissions. |
| Access denied | Log in to Sakai as a superuser. Learner and ordinary user accounts cannot manage this connector. |
| Connection could not be completed | Start again from Sakai. Keep the same logged-in session and finish within ten minutes. Check that the GuardLMS Sakai receiver is deployed and both servers can reach the configured HTTPS endpoints. |
| Reconnect required | Reconnect after key expiry, key revocation or deletion of the GuardLMS website. The first refused push is shown on the settings page. |
| HTTP 422 while reporting | Compare `guardlms.siteUrl` with the website URL registered in GuardLMS. Use the canonical origin consistently. Reconnect a clone or staging environment to its own website. |
| HTTPS connection failure | Check the certificate chain, hostname and JVM truststore. Install your organization's CA if needed; keep certificate verification enabled. |
| No reports after restart | Preserve the state directory and original encryption key, keep Sakai running, and check the next attempt and last reporting error. |

## Upgrade and uninstall

For an upgrade, stop Tomcat, replace `webapps/guardlms.war` and remove the old
exploded `webapps/guardlms` directory before restarting. Preserve the state
directory, encryption key and site URL.

To uninstall, disconnect first, stop Tomcat, remove the WAR and exploded webapp,
remove any `guardlms.admin` placements and restart. Archive or remove its private
state directory and revoke its inventory key in GuardLMS.

## Development and verification

`mvn package` runs the Java tests and builds the WAR. CI also runs `mvn verify`.
The provided Sakai APIs compile against the stable 23.3 descriptors because the
published 25.2 descriptors reference an unpublished parent POM. Those APIs are
not bundled in the WAR. Runtime compatibility is validated on Sakai **25.2**;
this does not claim Sakai 23 support. Gson and its annotation dependency are bundled.

The [GuardLMS E2E harness](https://github.com/LdesignMedia/guardlms/tree/main/integrations/sakai/docker)
runs this WAR inside the official Sakai distribution against an isolated Laravel
receiver. Set `SAKAI_PLUGIN_ROOT` to this checkout when using that harness.
It covers the real browser connection lifecycle, administrator access controls,
105 accepted components, encrypted storage, rejected-key recovery, scheduled
reporting, disconnect/reconnect and untrusted-certificate refusal.
