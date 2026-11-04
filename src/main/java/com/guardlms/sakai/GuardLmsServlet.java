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
                    case "/push": store.locked(p -> { if (!connected(p)) throw new IllegalStateException("Disconnected"); push(p); return null; }); break;
                    case "/disconnect": store.locked(p -> { p.clear(); p.setProperty("generation", random()); return null; }); break;
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
                    p.setProperty("verificationToken", data.get("verification_token").getAsString());
                    if (data.has("expires_at") && !data.get("expires_at").isJsonNull()) p.setProperty("expiresAt", data.get("expires_at").getAsString());
                    p.setProperty("connectedAt", Instant.now().toString());
                    push(p);
                    return null;
                });
                res.sendRedirect(req.getContextPath() + "/"); return;
            }
            if (path.equals("/preview")) {
                res.setContentType("application/json"); res.getWriter().write(JSON.toJson(inventory())); return;
            }
            if (!path.equals("/")) { res.sendError(404); return; }
            Properties status = store.locked(p -> { Properties copy = new Properties(); copy.putAll(p); copy.setProperty("connected", Boolean.toString(connected(p))); copy.remove("credential"); return copy; });
            res.setContentType("text/html");
            res.getWriter().write(page(csrf, status, req.getContextPath()));
        } catch (Exception e) {
            if (e instanceof SecurityException) { res.sendError(403); return; }
            getServletContext().log("GuardLMS request failed (" + e.getClass().getSimpleName() + ")");
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
            state.setProperty("lastSuccess", Instant.now().toString());
            state.setProperty("components", result.get("components_upserted").getAsString());
            state.remove("lastError"); state.remove("failures");
            state.setProperty("nextAttempt", Long.toString(now() + 86400));
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            long failures = Math.min(seconds(state, "failures") + 1, 10);
            state.setProperty("failures", Long.toString(failures));
            state.setProperty("lastError", e instanceof IOException && e.getMessage() != null && e.getMessage().matches("GuardLMS HTTP [0-9]{3}")
                ? e.getMessage() : "Connection failed; check HTTPS trust, credentials and site URL");
            state.setProperty("nextAttempt", Long.toString(now() + Math.min(3600, 60L << (failures - 1))));
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

    private String page(String csrf, Properties state, String context) {
        StringBuilder html = new StringBuilder("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"><title>GuardLMS for Sakai</title><style>body{font:16px system-ui;margin:2rem;max-width:70rem;color:#172b4d}button,a{margin:.5rem .5rem .5rem 0}dt{font-weight:bold}dd{margin:0 0 1rem}button{padding:.6rem 1rem}form{display:inline}</style></head><body><h1>GuardLMS for Sakai</h1>");
        html.append("<p>Reports Sakai, registered tools, Java, Tomcat and operating system metadata daily. Course and learner records are never collected. Tool versions and update availability stay unknown unless explicitly known.</p><dl><dt>Site URL</dt><dd>").append(escape(siteUrl)).append("</dd><dt>Connection</dt><dd>").append(Boolean.parseBoolean(state.getProperty("connected")) ? "Connected" : "Disconnected").append("</dd>");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("components", "Components reported");
        fields.put("lastSuccess", "Last accepted inventory");
        fields.put("nextAttempt", "Next reporting attempt");
        fields.put("lastError", "Last error");
        fields.put("expiresAt", "Key expires");
        fields.put("verificationToken", "Ownership verification token");
        var dateFormat = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(java.time.ZoneOffset.UTC);
        for (var field : fields.entrySet()) {
            String value = state.getProperty(field.getKey(), "—");
            if (!value.equals("—")) {
                try {
                    if (field.getKey().equals("nextAttempt")) value = dateFormat.format(Instant.ofEpochSecond(Long.parseLong(value)));
                    else if (Set.of("lastSuccess", "expiresAt").contains(field.getKey())) value = dateFormat.format(Instant.parse(value));
                } catch (java.time.DateTimeException | NumberFormatException ignored) { value = "Unavailable"; }
            }
            html.append("<dt>").append(field.getValue()).append("</dt><dd>").append(escape(value)).append("</dd>");
        }
        html.append("</dl><p>If ownership verification remains pending in GuardLMS, use its DNS verification instructions. The token above can also be added to the public portal's HTML head using your Sakai theme.</p>");
        for (var action : Map.of("connect", "Connect to GuardLMS", "push", "Push inventory now", "disconnect", "Disconnect").entrySet()) {
            html.append("<form method=\"post\" action=\"").append(context).append('/').append(action.getKey()).append("\"><input type=\"hidden\" name=\"csrf\" value=\"").append(escape(csrf)).append("\"><button type=\"submit\">").append(action.getValue()).append("</button></form>");
        }
        return html.append("<p><a href=\"").append(context).append("/preview\">Preview inventory payload</a></p></body></html>").toString();
    }

    static String escape(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"); }
    static long now() { return Instant.now().getEpochSecond(); }
    private static long seconds(Properties state, String key) { return Long.parseLong(state.getProperty(key, "0")); }
    private static String random() { byte[] value = new byte[32]; new SecureRandom().nextBytes(value); return HexFormat.of().formatHex(value); }
    private static boolean equal(String a, String b) { return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)); }
    private static String query(Map<String, String> params) { return params.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&")); }
}
