package com.guardlms.sakai;

import com.google.gson.*;
import org.sakaiproject.component.cover.ComponentManager;
import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.authz.api.SecurityService;
import org.sakaiproject.db.api.SqlService;
import org.sakaiproject.tool.api.*;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Native admin tool. Learner, course, user and tool configuration data are never collected. */
public final class GuardLmsServlet extends HttpServlet {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private ServerConfigurationService config;
    private SecurityService security;
    private SessionManager sessions;
    private ToolManager tools;
    private StateStore store;
    private GuardLmsClient client;
    private ScheduledExecutorService scheduler;
    private String siteUrl;
    private String dashboardUrl;

    @Override public void init() throws ServletException {
        config = ComponentManager.get(ServerConfigurationService.class);
        security = ComponentManager.get(SecurityService.class);
        sessions = ComponentManager.get(SessionManager.class);
        tools = ComponentManager.get(ToolManager.class);
        try {
            siteUrl = GuardLmsClient.httpsUrl(config.getString("guardlms.siteUrl", config.getServerUrl())).toString();
            dashboardUrl = GuardLmsClient.httpsUrl(config.getString("guardlms.dashboardUrl", "https://dashboard.guardlms.com")).toString();
            client = new GuardLmsClient(config.getString("guardlms.apiUrl", "https://api.guardlms.com"));
            store = new StateStore(Path.of(config.getString("guardlms.stateDirectory", config.getSakaiHomePath() + "/guardlms")),
                config.getString("guardlms.encryptionKey", ""));
            scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "guardlms-inventory"); thread.setDaemon(true); return thread;
            });
            scheduler.scheduleWithFixedDelay(this::scheduledPush, 60, 60, TimeUnit.SECONDS);
        } catch (Exception e) { throw new ServletException("GuardLMS configuration invalid; check canonical HTTPS URLs, state directory and encryption key", e); }
    }

    @Override public void destroy() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    private void scheduledPush() {
        try {
            store.locked(state -> {
                if (connected(state) && seconds(state, "nextAttempt") <= now()) push(state);
                return null;
            });
        } catch (Exception e) {
            // Never put HTTP response bodies, credentials, query strings or database details in logs.
            getServletContext().log("GuardLMS scheduled inventory failed (" + e.getClass().getSimpleName() + ")");
        }
    }

    @Override protected void service(HttpServletRequest req, HttpServletResponse res) throws IOException {
        req.setCharacterEncoding("UTF-8");
        res.setCharacterEncoding("UTF-8");
        res.setHeader("Cache-Control", "no-store");
        res.setHeader("Referrer-Policy", "no-referrer");
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'self'; base-uri 'none'");
        if (!security.isSuperUser()) { res.sendError(403); return; }
        if (!Set.of("GET", "POST").contains(req.getMethod())) { res.sendError(405); return; }
        try {
            Session session = sessions.getCurrentSession();
            String csrf;
            synchronized (session) {
                csrf = (String) session.getAttribute("guardlms.csrf");
                if (csrf == null) { csrf = random(); session.setAttribute("guardlms.csrf", csrf); }
            }
            String path = req.getPathInfo() == null ? "/" : req.getPathInfo();
            if (req.getMethod().equals("POST")) {
                if (!equal(csrf, req.getParameter("csrf"))) { res.sendError(403); return; }
                switch (path) {
                    case "/connect":
                        String state = random();
                        synchronized (session) {
                            session.setAttribute("guardlms.pending", state);
                            session.setAttribute("guardlms.pendingGeneration", store.locked(p -> {
                                if (!p.containsKey("generation")) p.setProperty("generation", random());
                                return p.getProperty("generation");
                            }));
                            session.setAttribute("guardlms.pendingTime", now());
                        }
                        res.sendRedirect(dashboardUrl + "/connect/sakai?" + query(Map.of(
                            "siteurl", siteUrl, "callback", siteUrl + "/guardlms/callback", "state", state)));
                        return;
                    case "/push":
                        String pushNotice = store.locked(p -> {
                            if (!connected(p)) throw new IllegalStateException("Disconnected");
                            push(p);
                            return p.containsKey("lastError") ? "The inventory could not be sent. Check the connection status below; Sakai will retry automatically." : "Inventory received by GuardLMS.";
                        });
                        session.setAttribute("guardlms.notice", pushNotice);
                        break;
                    case "/disconnect":
                        store.locked(p -> { p.clear(); p.setProperty("generation", random()); return null; });
                        session.setAttribute("guardlms.notice", "Disconnected from GuardLMS. Reporting has stopped.");
                        break;
                    default: res.sendError(404); return;
                }
                res.sendRedirect(req.getContextPath() + "/"); return;
            }
            if (path.equals("/callback")) {
                String state = req.getParameter("state");
                String generation;
                synchronized (session) {
                    String pending = (String) session.getAttribute("guardlms.pending");
                    Long pendingTime = (Long) session.getAttribute("guardlms.pendingTime");
                    if (!equal(pending, state) || pendingTime == null || pendingTime + 600 < now()
                        || req.getParameter("code") == null || !req.getParameter("code").matches("[A-Za-z0-9]{64}")) {
                        res.sendError(403); return;
                    }
                    generation = (String) session.getAttribute("guardlms.pendingGeneration");
                    session.removeAttribute("guardlms.pendingGeneration");
                    session.removeAttribute("guardlms.pending");
                    session.removeAttribute("guardlms.pendingTime");
                }
                store.locked(p -> {
                    if (!equal(generation, p.getProperty("generation"))) throw new SecurityException("Connection changed; restart connect");
                    JsonObject data = client.post("/api/integrations/exchange", Map.of(
                        "siteurl", siteUrl, "state", state, "code", req.getParameter("code")), null).getAsJsonObject("data");
                    if (!siteUrl.equals(data.get("siteurl").getAsString())
                        || !"/api/externalpush/sakai".equals(data.get("pushpath").getAsString())) throw new IOException("Unexpected exchange binding");
                    p.clear();
                    p.setProperty("generation", random());
                    p.setProperty("credential", store.encrypt(data.get("token").getAsString()));
                    p.setProperty("siteUrl", siteUrl);
                    if (data.has("website_id") && !data.get("website_id").isJsonNull()) p.setProperty("websiteId", data.get("website_id").getAsString());
                    p.setProperty("verificationToken", data.get("verification_token").getAsString());
                    if (data.has("expires_at") && !data.get("expires_at").isJsonNull()) p.setProperty("expiresAt", data.get("expires_at").getAsString());
                    p.setProperty("connectedAt", Instant.now().toString());
                    push(p);
                    session.setAttribute("guardlms.notice", p.containsKey("lastError")
                        ? "Connected to GuardLMS. Your first inventory could not be sent; Sakai will retry automatically."
                        : "Connected to GuardLMS. Your first inventory has been received.");
                    return null;
                });
                res.sendRedirect(req.getContextPath() + "/"); return;
            }
            if (path.equals("/preview")) {
                res.setContentType("application/json"); res.getWriter().write(JSON.toJson(inventory())); return;
            }
            if (!path.equals("/")) { res.sendError(404); return; }
            Properties status = store.locked(p -> { Properties copy = new Properties(); copy.putAll(p); copy.setProperty("connected", Boolean.toString(connected(p))); copy.remove("credential"); return copy; });
            String notice;
            synchronized (session) {
                notice = (String) session.getAttribute("guardlms.notice");
                session.removeAttribute("guardlms.notice");
            }
            res.setContentType("text/html");
            res.getWriter().write(page(csrf, status, req.getContextPath(), notice));
        } catch (Exception e) {
            if (e instanceof SecurityException) { res.sendError(403); return; }
            getServletContext().log("GuardLMS request failed (" + e.getClass().getSimpleName() + ")");
            if ("/callback".equals(req.getPathInfo())) {
                sessions.getCurrentSession().setAttribute("guardlms.notice", "Connection could not be completed. Try connecting again. If this continues, check the GuardLMS service and your server's HTTPS configuration.");
                res.sendRedirect(req.getContextPath() + "/");
                return;
            }
            res.setStatus(502); res.setContentType("text/plain");
            res.getWriter().write("GuardLMS request failed. Check the connection status and server configuration, then retry.");
        }
    }

    /** Must run under the state lock, including network I/O, so disconnect cannot resurrect a credential. */
    private void push(Properties state) {
        try {
            JsonObject response = client.post("/api/externalpush/sakai", inventory(), store.decrypt(state.getProperty("credential")));
            JsonObject result = response.getAsJsonObject("data");
            if (result == null || result.get("stale").getAsBoolean()) throw new IOException("Inventory not accepted");
            ConnectionStatus.accepted(state, result.get("components_upserted").getAsInt(), Instant.now());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            ConnectionStatus.failed(state, e, Instant.now());
        }
    }

    private boolean connected(Properties state) {
        return state.containsKey("credential") && siteUrl.equals(state.getProperty("siteUrl"));
    }

    private Map<String, Object> inventory() {
        // includeStealthed is deliberate: inventory is installed registry entries, not course placements.
        List<Map<String, Object>> registered = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Tool tool : tools.findTools(null, null, true)) {
            String id = tool.getId();
            if (id == null || id.isBlank() || id.length() > 120 || !ids.add(id.toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException("Tool registry cannot be reported completely");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id); row.put("version", "guardlms.admin".equals(id) ? "0.1.0" : null); row.put("enabled", true);
            registered.add(row);
        }
        if (registered.isEmpty() || registered.size() > 2000) throw new IllegalStateException("Tool registry unavailable or too large");
        registered.sort(Comparator.comparing(row -> row.get("id").toString()));
        Map<String, Object> sakai = new LinkedHashMap<>();
        String version = config.getString("version.sakai", "");
        sakai.put("version", version.isBlank() || version.length() > 100 ? null : version);
        sakai.put("plugins", registered);
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("os_family", System.getProperty("os.name"));
        server.put("arch", System.getProperty("os.arch"));
        // ServletContext's product string is "Apache Tomcat/x.y.z"; normalize its product name.
        String webserver = getServletContext().getServerInfo().replace("Apache Tomcat/", "Tomcat/");
        server.put("webserver", webserver);
        try {
            Properties os = new Properties();
            try (Reader reader = Files.newBufferedReader(Path.of("/etc/os-release"))) { os.load(reader); }
            for (var entry : Map.of("NAME", "os_name", "ID", "os_id", "VERSION_ID", "os_version", "PRETTY_NAME", "os_pretty").entrySet()) {
                String value = os.getProperty(entry.getKey());
                if (value != null) server.put(entry.getValue(), value.replaceAll("^\"|\"$", ""));
            }
        } catch (IOException ignored) { /* Best effort, never run shell commands or enumerate environment variables. */ }
        Map<String, Object> payload = new LinkedHashMap<>(Map.of("siteurl", siteUrl, "generatedtime", now(), "sakai", sakai,
            "java", Map.of("version", System.getProperty("java.version")), "server", server));
        SqlService sql = ComponentManager.get(SqlService.class);
        java.sql.Connection connection = null;
        try {
            connection = sql.borrowConnection();
            var metadata = connection.getMetaData();
            String product = metadata.getDatabaseProductName();
            String databaseVersion = metadata.getDatabaseProductVersion();
            if (Set.of("MySQL", "MariaDB", "PostgreSQL", "Oracle", "HSQLDB").contains(product)
                && databaseVersion != null && !databaseVersion.isBlank() && databaseVersion.length() <= 100) {
                payload.put("database", Map.of("name", product, "version", databaseVersion));
            }
        } catch (java.sql.SQLException ignored) { /* Version metadata is best effort. Never report JDBC URLs or credentials. */ }
        finally { if (connection != null) sql.returnConnection(connection); }
        return payload;
    }

    private String page(String csrf, Properties state, String context, String notice) {
        boolean hasConnection = Boolean.parseBoolean(state.getProperty("connected"));
        boolean reconnect = hasConnection && ConnectionStatus.requiresReconnect(state, Instant.now());
        String label = ConnectionStatus.label(state, hasConnection, Instant.now());
        String badge = !hasConnection ? "disconnected" : reconnect ? "rejected" : "connected";
        StringBuilder html = new StringBuilder("""
            <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <title>GuardLMS for Sakai</title><style>
            *{box-sizing:border-box}body{font:16px/1.55 system-ui,sans-serif;margin:0;background:#f6f7f9;color:#172b4d}
            main{max-width:960px;margin:3rem auto;padding:0 1.25rem}h1{margin-bottom:.25rem}h2{font-size:1.25rem;margin-top:0}
            p{margin:.7rem 0}section,details{background:white;border:1px solid #dce2e9;border-radius:10px;padding:1.5rem;margin:1.25rem 0}
            .badge{display:inline-block;padding:.15rem .7rem;border-radius:20px;font-weight:650}.connected{background:#d7f5df;color:#0a6b31}
            .disconnected{background:#fcdada;color:#a02020}.rejected{background:#fcf0d3;color:#805000}.notice{padding:1rem;background:#eef4ff;border-left:4px solid #3156a6}
            .warning{padding:1rem;background:#fff6df;border-left:4px solid #9c6500}.actions{display:flex;gap:.6rem;flex-wrap:wrap;margin:1rem 0}
            button,.link-button{display:inline-block;font:inherit;padding:.6rem 1rem;border:1px solid #aab4c3;border-radius:6px;background:white;color:#172b4d;cursor:pointer;text-decoration:none}
            .primary{background:#fa6e10;border-color:#fa6e10;color:#172b4d;font-weight:650}.primary:hover{background:#ee6307}
            .danger{color:#b32d2e;border-color:#b32d2e}a{color:#1d4f9c}button:focus-visible,a:focus-visible,summary:focus-visible{outline:3px solid #3156a6;outline-offset:3px}
            dl{display:grid;grid-template-columns:minmax(140px,1fr) 2fr;gap:.6rem 1rem;margin-bottom:0}dt{font-weight:650}dd{margin:0;overflow-wrap:anywhere}
            summary{font-weight:650;cursor:pointer}.muted{color:#4e6079}code{overflow-wrap:anywhere}@media(max-width:600px){main{margin:1.5rem auto}dl{grid-template-columns:1fr;gap:.2rem}dd{margin-bottom:.6rem}}
            </style></head><body><main><header><h1>GuardLMS for Sakai</h1><p class="muted">Daily software inventory, connected to your GuardLMS account.</p></header>
            """);
        if (notice != null) html.append("<p class=\"notice\" role=\"alert\">").append(escape(notice)).append("</p>");
        html.append("<section aria-labelledby=\"connection-heading\"><h2 id=\"connection-heading\">Connection</h2><p>Status: <span role=\"status\" class=\"badge ").append(badge).append("\">").append(label).append("</span></p>");
        if (!hasConnection) {
            html.append("<p>Connect this Sakai server to GuardLMS to start daily inventory reporting. Sign in or create an account, review this site, and approve the connection.</p><p>No API key to copy. GuardLMS installs a key for this site and receives the first inventory automatically.</p>");
        } else if (reconnect) {
            html.append("<div class=\"warning\"><p>GuardLMS has refused this site's key, or the key has expired. Reporting needs your attention. Click <strong>Reconnect</strong> to install a new key.</p>");
            if (state.containsKey("authRejectedAt")) html.append("<p>First refused push: ").append(escape(date(state.getProperty("authRejectedAt"), false))).append("</p>");
            html.append("<p>Your existing key is kept so reporting can recover if a temporary service issue clears.</p></div>");
        } else if (state.containsKey("lastError")) {
            html.append("<div class=\"warning\"><p>The site is connected, but the last inventory was not accepted. Sakai will retry automatically. You can also try <strong>Push inventory now</strong>.</p></div>");
        } else {
            html.append("<p>Your site is connected. Sakai sends its software inventory daily while the server is running.</p>");
        }
        html.append("<p><strong>Site:</strong> ").append(escape(siteUrl)).append("</p><div class=\"actions\">");
        action(html, context, csrf, "connect", hasConnection ? "Reconnect" : "Connect to GuardLMS", "primary");
        if (hasConnection) {
            action(html, context, csrf, "push", "Push inventory now", "");
            action(html, context, csrf, "disconnect", "Disconnect", "danger");
            String websiteId = state.getProperty("websiteId", "");
            if (websiteId.matches("[1-9][0-9]*")) html.append("<a class=\"link-button\" href=\"").append(escape(dashboardUrl + "/websites/" + websiteId)).append("\" target=\"_top\">Open in GuardLMS</a>");
        }
        html.append("</div>");
        if (hasConnection) {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("connectedAt", "Connected on");
            fields.put("lastSuccess", "Last accepted inventory");
            fields.put("components", "Components reported");
            fields.put("nextAttempt", "Next reporting attempt");
            fields.put("expiresAt", "Key expires");
            html.append("<dl>");
            for (var field : fields.entrySet()) {
                String value = state.getProperty(field.getKey(), "—");
                if (Set.of("connectedAt", "lastSuccess", "expiresAt").contains(field.getKey())) value = date(value, false);
                if (field.getKey().equals("nextAttempt")) value = date(value, true);
                html.append("<dt>").append(field.getValue()).append("</dt><dd>").append(escape(value)).append("</dd>");
            }
            html.append("</dl>");
        }
        html.append("</section><section aria-labelledby=\"reporting-heading\"><h2 id=\"reporting-heading\">What this connector reports</h2><p>Sakai, registered tools, Java, Tomcat, operating system and database versions. Course and learner records are never collected. Tool versions and update availability stay unknown unless explicitly known.</p></section>");
        html.append("<details><summary>Advanced connection details</summary><p><a href=\"").append(escape(context)).append("/preview\">Preview inventory payload</a></p><dl><dt>Last reporting error</dt><dd>").append(escape(state.getProperty("lastError", "None"))).append("</dd><dt>Ownership verification token</dt><dd>").append(escape(state.getProperty("verificationToken", "Not connected"))).append("</dd></dl><p>If ownership remains pending in GuardLMS, use its DNS verification instructions. You can also add the public verification token to the portal's HTML head through your Sakai theme.</p></details>");
        return html.append("</main></body></html>").toString();
    }

    private static void action(StringBuilder html, String context, String csrf, String path, String label, String style) {
        html.append("<form method=\"post\" action=\"").append(escape(context)).append('/').append(path).append("\" target=\"_top\"><input type=\"hidden\" name=\"csrf\" value=\"").append(escape(csrf)).append("\"><button type=\"submit\" class=\"").append(style).append("\">").append(label).append("</button></form>");
    }

    private static String date(String value, boolean unixTime) {
        if (value.equals("—")) return value;
        try {
            Instant instant = unixTime ? Instant.ofEpochSecond(Long.parseLong(value)) : Instant.parse(value);
            return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(java.time.ZoneOffset.UTC).format(instant);
        } catch (java.time.DateTimeException | NumberFormatException ignored) { return "Unavailable"; }
    }

    static String escape(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"); }
    static long now() { return Instant.now().getEpochSecond(); }
    private static long seconds(Properties state, String key) { return Long.parseLong(state.getProperty(key, "0")); }
    private static String random() { byte[] value = new byte[32]; new SecureRandom().nextBytes(value); return HexFormat.of().formatHex(value); }
    private static boolean equal(String a, String b) { return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)); }
    private static String query(Map<String, String> params) { return params.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&")); }
}
