/*
Copyright contributors to the SyncWeave project

SPDX-License-Identifier: Apache-2.0
*/
package com.vdi.di.connector;

import com.ibm.di.connector.ConnectorInterface;
import com.ibm.di.connector.HTTPServerConnector;
import com.ibm.di.entry.Entry;
import com.ibm.di.exceptions.RetryEntryException;
import com.ibm.json.java.JSONArray;
import com.ibm.json.java.JSONObject;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.NoSuchElementException;
import java.util.Set;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.net.ssl.SSLSocket;

/**
 * MCP (Model Context Protocol) server connector for VDI 10.
 *
 * Subclasses HTTPServerConnector to reuse its socket/TLS/chunking/HTTP
 * framing (see docs/SPEC.md §1); this class adds the JSON-RPC/MCP message
 * layer on top.
 *
 * HTTPServerConnector#getNextClient() hardcodes `new HTTPServerConnector()`
 * for the per-connection object it hands back (confirmed by disassembling
 * HTTPServerConnector.class — it is not `this.getClass().newInstance()` or
 * a clone). That means a plain subclass's getNextEntry()/replyEntry()
 * overrides are never invoked for real traffic: the framework always
 * processes the connection on a base-class instance. getNextClient() is
 * overridden below to replicate the exact same setup sequence (verified
 * step-by-step against the decompiled bytecode) but constructing
 * `new McpServerConnector()` instead, so our overrides actually run.
 *
 * Every step that sequence performs uses public inherited methods
 * (setServerConnector/setConfiguration/setRSInterface/setName/setLog/
 * initialize(Socket)) except obtaining the listening socket itself
 * (mServerSocket is private with no accessor), which is why reflection is
 * used for that one field only.
 *
 * One inbound connection still drives exactly one AL cycle on a fresh
 * per-connection instance, so the instance fields below (pending JSON-RPC
 * id) are safe: they never outlive a single request/response exchange.
 *
 * Protocol bookkeeping methods (initialize, notifications/initialized,
 * tools/list) are answered directly here without involving the AL, since
 * they don't carry business logic. Only tools/call is handed to the AL as
 * a work Entry; the AL's reply Entry is translated back into the MCP tool
 * result in replyEntry().
 */
public class McpServerConnector extends HTTPServerConnector {

    public static final String VERSION_INFO = "0.1.0-SNAPSHOT";

    /** Server's preferred protocol version, returned by initialize when the client doesn't request a supported one. */
    public static final String PROTOCOL_VERSION = "2025-11-25";

    /**
     * Versions we'll interoperate with. The JSON-RPC surface we implement
     * (initialize/tools.list/tools.call) is stable across these, so we accept
     * any of them on the MCP-Protocol-Version header and echo a client's
     * requested version back from initialize if it's one of these. Keeps us
     * compatible with real clients (e.g. Claude) that may negotiate an older
     * version rather than only our single preferred one.
     */
    private static final Set<String> SUPPORTED_PROTOCOL_VERSIONS = new HashSet<>(Arrays.asList(
            "2025-11-25", "2025-06-18", "2025-03-26"));

    public static final String ATTR_MCP_TOOL = "$mcp.tool";
    public static final String ATTR_MCP_REQUEST_ID = "$mcp.requestId";
    public static final String ATTR_MCP_PROTOCOL_VERSION = "$mcp.protocolVersion";
    public static final String ATTR_MCP_ARGUMENTS = "$mcp.arguments";

    public static final String ATTR_MCP_RESULT = "$mcp.result";
    public static final String ATTR_MCP_STRUCTURED = "$mcp.structured";
    public static final String ATTR_MCP_IS_ERROR = "$mcp.isError";

    /** Verified caller identity passed to the AL: subject DN (and CN) of the mTLS client cert, or a trusted actorHeader value. */
    public static final String ATTR_MCP_ACTOR = "$mcp.actor";
    public static final String ATTR_MCP_ACTOR_CN = "$mcp.actorCn";

    private static final String HTTP_OK = "200 OK";
    private static final String HTTP_ACCEPTED = "202 Accepted";
    private static final String CONTENT_TYPE_JSON = "application/json";

    /** JSON-RPC id of the in-flight tools/call, held between getNextEntry() and replyEntry(). */
    private Object pendingCallId;

    /** Per-connection client socket, captured in getNextClient() for lazy mTLS client-cert extraction. */
    private Socket clientSocket;
    private boolean certExtracted;
    private String clientCertDn;
    private String clientCertCn;

    @Override
    public String getVersion() {
        return VERSION_INFO;
    }

    /**
     * Replicates HTTPServerConnector#getNextClient()'s accept/setup sequence
     * (verified against the decompiled bytecode), substituting our own
     * subclass for the per-connection object so getNextEntry()/replyEntry()
     * overrides below actually run for real traffic. See class doc.
     */
    @Override
    public ConnectorInterface getNextClient() throws Exception {
        ServerSocket serverSocket = getListeningSocket();
        if (serverSocket == null) {
            throw new Exception("McpServerConnector.getNextClient() called on a non-listening (per-connection) instance");
        }

        if (isTerminating()) {
            logmsg("McpServerConnector: terminated by external request before accept().");
            return null;
        }

        Socket socket = serverSocket.accept();

        if (isTerminating()) {
            logmsg("McpServerConnector: terminated by external request after accept().");
            socket.close();
            return null;
        }

        // bindAddress enforcement. The inherited HTTPServerConnector binds the
        // listening socket to all interfaces (0.0.0.0) — it has no per-address
        // bind option. So we enforce bindAddress post-accept: reject any
        // connection that didn't arrive on the configured local address, then
        // RetryEntryException to go accept the next one (NOT return null, which
        // would terminate the listener). This restricts which interface the
        // service is effectively reachable on even though the socket listens
        // broadly. See docs/CONFIGURE.md.
        if (!isLocalAddressAllowed(socket)) {
            String got = socket.getLocalAddress() == null ? "?" : socket.getLocalAddress().getHostAddress();
            socket.close();
            throw new RetryEntryException("McpServerConnector: rejected connection received on " + got
                    + " (bindAddress=" + getParam("bindAddress") + ")");
        }

        McpServerConnector client = new McpServerConnector();
        client.setServerConnector(this);
        client.setConfiguration(this.getConfiguration());
        client.setRSInterface(this.getRSInterface());
        client.setName(this.getName());
        client.setLog(this.getLog());
        try {
            client.initialize(socket);
        } catch (IOException | NoSuchElementException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // best-effort close on a socket we're already abandoning
            }
            throw new RetryEntryException("McpServerConnector: failed to initialize connection: " + e.getMessage());
        }
        // Keep the socket so the per-connection instance can read the verified
        // mTLS client-cert identity later (lazily, once the handshake is done).
        client.clientSocket = socket;
        return client;
    }

    private ServerSocket getListeningSocket() throws Exception {
        Field field = HTTPServerConnector.class.getDeclaredField("mServerSocket");
        field.setAccessible(true);
        return (ServerSocket) field.get(this);
    }

    /**
     * Read the verified mTLS client-certificate identity (subject DN + CN) from
     * this connection's socket, once. Done lazily (called from buildToolCallEntry,
     * after the request was read, so the TLS handshake is complete). No SSL / no
     * client cert presented => leaves the identity null (no actor).
     */
    private void extractClientCertIfNeeded() {
        if (certExtracted) {
            return;
        }
        certExtracted = true;
        if (!(clientSocket instanceof SSLSocket)) {
            return;
        }
        try {
            Certificate[] chain = ((SSLSocket) clientSocket).getSession().getPeerCertificates();
            if (chain != null && chain.length > 0 && chain[0] instanceof X509Certificate) {
                clientCertDn = ((X509Certificate) chain[0]).getSubjectX500Principal().getName();
                clientCertCn = extractCn(clientCertDn);
            }
        } catch (Exception e) {
            // no verified peer certificate available; actor stays null
        }
    }

    /** Pull the CN component out of an X.500 DN, or null if none. */
    private static String extractCn(String dn) {
        try {
            for (Rdn rdn : new LdapName(dn).getRdns()) {
                if ("CN".equalsIgnoreCase(rdn.getType())) {
                    return rdn.getValue().toString();
                }
            }
        } catch (Exception e) {
            // malformed DN — fall through
        }
        return null;
    }

    /** True if the connection arrived on the configured bindAddress (or no/0.0.0.0 bindAddress configured = any). */
    private boolean isLocalAddressAllowed(Socket socket) {
        String bindAddr = getParam("bindAddress");
        if (bindAddr == null || bindAddr.trim().isEmpty() || "0.0.0.0".equals(bindAddr.trim())) {
            return true;
        }
        InetAddress local = socket.getLocalAddress();
        if (local == null) {
            return false;
        }
        bindAddr = bindAddr.trim();
        // Fast path: exact textual match (preserves prior behavior).
        if (bindAddr.equals(local.getHostAddress())) {
            return true;
        }
        // Address-aware match. A loopback bindAddress (e.g. 127.0.0.1) must accept
        // ANY loopback peer — IPv4 127.0.0.1, IPv6 ::1, and IPv4-mapped
        // ::ffff:127.0.0.1 are all "localhost". Modern clients resolve `localhost`
        // to ::1 first (macOS/Node), so a strict 127.0.0.1 string match would
        // reset every IPv6 loopback connection (ECONNRESET) — which locks out real
        // MCP clients while curl-to-127.0.0.1 keeps working. Non-loopback binds
        // still require an exact resolved-address match, preserving NIC isolation.
        try {
            InetAddress want = InetAddress.getByName(bindAddr);
            if (want.equals(local)) {
                return true;
            }
            if (want.isLoopbackAddress() && local.isLoopbackAddress()) {
                return true;
            }
        } catch (Exception e) {
            // Unresolvable bindAddress → fall through to reject.
        }
        return false;
    }

    /**
     * §2: endpointPath enforcement. When endpointPath is configured non-empty,
     * a request to any other path gets 404. http.base is the request path
     * without query string (confirmed via the CE's Entry dumps); fall back to
     * http.url. Empty endpointPath = accept any path (permissive).
     */
    private boolean isEndpointPathAllowed(Entry httpEntry) {
        String configured = getParam("endpointPath");
        if (configured == null || configured.trim().isEmpty()) {
            return true;
        }
        return configured.trim().equals(requestPath(httpEntry));
    }

    /** Request path without query string: http.base, falling back to http.url. */
    private String requestPath(Entry httpEntry) {
        String path = httpEntry.getString("http.base");
        return path != null ? path : httpEntry.getString("http.url");
    }

    /** GET on the configured healthPath (when set non-empty). */
    private boolean isHealthRequest(Entry httpEntry) {
        String healthPath = getParam("healthPath");
        if (healthPath == null || healthPath.trim().isEmpty()) {
            return false;
        }
        String method = httpEntry.getString("http.method");
        if (method != null && !"GET".equalsIgnoreCase(method)) {
            return false;
        }
        return healthPath.trim().equals(requestPath(httpEntry));
    }

    private void sendHealth(Entry httpEntry) throws Exception {
        JSONObject body = new JSONObject();
        body.put("status", "ok");
        body.put("server", "mcp-server-connector");
        body.put("version", VERSION_INFO);
        sendJson(buildHttpReplyEntry(body, HTTP_OK));
    }

    /**
     * §6/Advanced: reject over-large requests with 413 when maxRequestBytes > 0.
     * Best-effort: the inherited HTTP parser has already buffered the body by the
     * time we see the entry, so this guards processing (and signals the client),
     * not the read itself — a true pre-read cap would need overriding the parser.
     */
    private boolean isRequestTooLarge(Entry httpEntry) {
        int max = parseIntParam("maxRequestBytes", 0);
        if (max <= 0) {
            return false;
        }
        String contentLength = getHeader(httpEntry, "Content-Length");
        if (contentLength != null) {
            try {
                if (Integer.parseInt(contentLength.trim()) > max) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
                // fall through to the actual-body check
            }
        }
        String body = httpEntry.getString("http.bodyAsString");
        return body != null && body.getBytes(StandardCharsets.UTF_8).length > max;
    }

    private int parseIntParam(String name, int dflt) {
        String v = getParam(name);
        if (v == null || v.trim().isEmpty()) {
            return dflt;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    /**
     * HTTP header names land as http.<HeaderName> attributes preserving the
     * exact case the client sent (confirmed via the CE's Entry dumps: e.g.
     * http.Content-Type, http.User-Agent). Headers are case-insensitive per
     * RFC 7230, so look up by name case-insensitively rather than assuming
     * any particular casing.
     */
    private String getHeader(Entry httpEntry, String headerName) {
        String target = "http." + headerName;
        for (String attrName : httpEntry.getAttributeNames()) {
            if (attrName.equalsIgnoreCase(target)) {
                return httpEntry.getString(attrName);
            }
        }
        return null;
    }

    /**
     * §2/§6: validate Origin against allowedOrigins.
     *
     * If allowedOrigins is empty, Origin checking is OFF (out-of-box / curl /
     * non-browser clients).
     *
     * Once an allowlist IS configured: a PRESENT Origin header is validated
     * strictly against it (reject on any mismatch) — this is what actually
     * defends against DNS-rebinding, since a browser (including a rebound one)
     * always attaches Origin on cross-origin requests and cannot forge it to
     * an arbitrary value. A MISSING Origin header is allowed through: per the
     * Fetch spec, "Origin" is a forbidden header name, so browser-based
     * fetch()/XHR clients can never omit it, but non-browser HTTP clients
     * (curl, MCP SDKs, Claude Code's fetch()-based transport) send no Origin
     * at all in normal operation and structurally cannot be made to send one
     * that would pass an allowlist. Rejecting missing-Origin requests would
     * make the allowlist impossible for exactly the legitimate MCP clients
     * this server exists to serve, while doing nothing extra against browser
     * attackers (who can't omit the header anyway). The bearer-token check
     * remains the real auth boundary for these no-Origin callers.
     *
     * Deliberately NOT based on peer/remote address: a DNS-rebinding attack
     * against a loopback-bound server arrives via a loopback peer connection
     * too (the victim's own browser, on the same host), so peer IP cannot
     * distinguish an attacker from a legitimate local client.
     */
    private boolean isOriginAllowed(Entry httpEntry) {
        String allowed = getParam("allowedOrigins");
        if (allowed == null || allowed.trim().isEmpty()) {
            return true;
        }
        String origin = getHeader(httpEntry, "Origin");
        if (origin == null) {
            return true;
        }
        for (String candidate : allowed.split(",")) {
            if (candidate.trim().equalsIgnoreCase(origin.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * §6: bearer token check, only enforced when authMode=bearer. authMode=mtls
     * relies entirely on the TLS layer (useSSL + needClientAuth, both inherited
     * unmodified from HTTPServerConnector — the per-connection Socket returned
     * by getNextClient()'s accept() is already an SSLSocket if the listener was
     * configured for SSL, so client-cert verification happens before our code
     * ever runs). authMode=none performs no check here.
     */
    private boolean isBearerAuthorized(Entry httpEntry) {
        String mode = getParam("authMode");
        if (mode == null || !"bearer".equalsIgnoreCase(mode)) {
            return true;
        }
        String expected = getParam("bearerToken");
        if (expected == null || expected.isEmpty()) {
            logmsg("McpServerConnector: authMode=bearer but no bearerToken configured; rejecting all requests.");
            return false;
        }
        String header = getHeader(httpEntry, "Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return false;
        }
        String provided = header.substring("Bearer ".length());
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    /** §2: absent header is allowed (spec default is 2025-03-26); present-and-unsupported is rejected. */
    private boolean isProtocolVersionAcceptable(Entry httpEntry) {
        String header = getHeader(httpEntry, "MCP-Protocol-Version");
        return header == null || SUPPORTED_PROTOCOL_VERSIONS.contains(header);
    }

    private void sendTransportError(Entry httpEntry, String httpStatus, String message) throws Exception {
        JSONObject body = new JSONObject();
        body.put("error", message);
        sendJson(buildHttpReplyEntry(body, httpStatus));
    }

    @Override
    public Entry getNextEntry() throws Exception {
        Entry httpEntry = super.getNextEntry();
        if (httpEntry == null) {
            return null;
        }
        return processRequest(httpEntry);
    }

    /**
     * Applies the transport gates and JSON-RPC dispatch to an already-read HTTP
     * request Entry. Returns the work Entry for tools/call, or null when the
     * request was answered directly. Split out of getNextEntry() so it can be
     * unit-tested without a socket.
     */
    Entry processRequest(Entry httpEntry) throws Exception {
        // Health probe (GET on the configured healthPath) is answered before any
        // other check — liveness probes are unauthenticated and narrow (specific
        // path + GET only).
        if (isHealthRequest(httpEntry)) {
            sendHealth(httpEntry);
            return null;
        }

        // Security gates first (Origin, then auth) so unauthenticated/cross-origin
        // callers can't probe endpoint path/method/size existence. Origin is the
        // DNS-rebinding defense; bearer is identity. Intended for networked (not
        // just localhost) deployments.
        if (!isOriginAllowed(httpEntry)) {
            sendTransportError(httpEntry, "403 Forbidden", "Origin not allowed.");
            return null;
        }

        if (!isBearerAuthorized(httpEntry)) {
            sendTransportError(httpEntry, "401 Unauthorized", "Missing or invalid bearer token.");
            return null;
        }

        if (!isEndpointPathAllowed(httpEntry)) {
            sendTransportError(httpEntry, "404 Not Found", "No MCP endpoint at this path.");
            return null;
        }

        String httpMethod = httpEntry.getString("http.method");
        if (httpMethod != null && !"POST".equalsIgnoreCase(httpMethod)) {
            sendTransportError(httpEntry, "405 Method Not Allowed", "Only POST is supported on this endpoint.");
            return null;
        }

        if (isRequestTooLarge(httpEntry)) {
            sendTransportError(httpEntry, "413 Payload Too Large", "Request body exceeds the configured maximum.");
            return null;
        }

        // http.body is the raw byte[]; HTTPServerConnector also exposes a decoded
        // String copy under http.bodyAsString (confirmed via the CE's Entry dump),
        // which is what we actually want for JSON parsing.
        String body = httpEntry.getString("http.bodyAsString");
        JSONObject rpc;
        // Catch Throwable, not just Exception: JSON4J's parser is recursive, so a
        // deeply nested payload throws StackOverflowError (an Error, not an
        // Exception). Without this it would propagate and kill the AL cycle
        // instead of returning a clean JSON-RPC parse error.
        try {
            rpc = JSONObject.parse(body == null ? "" : body);
        } catch (Throwable t) {
            sendError(httpEntry, null, -32700, "Parse error: " + t.getClass().getSimpleName());
            return null;
        }

        Object id = rpc.get("id");
        Object methodObj = rpc.get("method");
        if (!(methodObj instanceof String)) {
            sendError(httpEntry, id, -32600, "Invalid Request: missing method");
            return null;
        }
        String method = (String) methodObj;

        // MCP-Protocol-Version is unknown to the client until initialize() responds,
        // so it's only enforced on requests after that handshake.
        if (!"initialize".equals(method) && !isProtocolVersionAcceptable(httpEntry)) {
            sendTransportError(httpEntry, "400 Bad Request", "Unsupported or invalid MCP-Protocol-Version header.");
            return null;
        }

        switch (method) {
            case "initialize":
                sendInitializeResult(httpEntry, id, rpc);
                return null;
            case "notifications/initialized":
                sendAccepted(httpEntry);
                return null;
            case "tools/list":
                sendToolsList(httpEntry, id);
                return null;
            case "tools/call":
                return buildToolCallEntry(httpEntry, id, rpc);
            default:
                sendError(httpEntry, id, -32601, "Method not found: " + method);
                return null;
        }
    }

    @Override
    public void replyEntry(Entry aEntry) throws Exception {
        JSONObject response = new JSONObject();
        response.put("jsonrpc", "2.0");
        response.put("id", pendingCallId);

        boolean isError = "true".equalsIgnoreCase(aEntry.getString(ATTR_MCP_IS_ERROR));
        String resultText = aEntry.getString(ATTR_MCP_RESULT);
        String structuredJson = aEntry.getString(ATTR_MCP_STRUCTURED);

        JSONObject result = new JSONObject();
        JSONArray content = new JSONArray();
        JSONObject textBlock = new JSONObject();
        textBlock.put("type", "text");
        // SECURITY: when the AL set no $mcp.result, return a fixed generic message
        // rather than serializing the whole work Entry — the entry may carry
        // sensitive attributes the Data Flow loaded (user records, credentials),
        // and dumping it would leak them to the MCP client.
        textBlock.put("text", resultText != null ? resultText : "Tool completed but returned no result.");
        content.add(textBlock);
        result.put("content", content);
        result.put("isError", isError);
        if (structuredJson != null) {
            try {
                result.put("structuredContent", JSONObject.parse(structuredJson));
            } catch (Exception e) {
                logmsg("McpServerConnector: ignoring unparsable $mcp.structured: " + e.getMessage());
            }
        }
        response.put("result", result);

        sendJson(buildHttpReplyEntry(response, HTTP_OK));
    }

    private Entry buildToolCallEntry(Entry httpEntry, Object id, JSONObject rpc) throws Exception {
        Object params = rpc.get("params");
        JSONObject paramsObj = params instanceof JSONObject ? (JSONObject) params : new JSONObject();
        String toolName = (String) paramsObj.get("name");
        Object arguments = paramsObj.get("arguments");
        JSONObject argumentsObj = arguments instanceof JSONObject ? (JSONObject) arguments : new JSONObject();

        if (toolName == null || !getToolNames().contains(toolName)) {
            sendToolError(id, "Unknown tool: " + toolName);
            return null;
        }

        pendingCallId = id;

        // Reflect the protocol version the client is actually using on this
        // request (the MCP-Protocol-Version header, which a compliant client
        // sets to whatever initialize negotiated), falling back to our default.
        String reqVersion = getHeader(httpEntry, "MCP-Protocol-Version");
        if (reqVersion == null) {
            reqVersion = PROTOCOL_VERSION;
        }

        Entry work = new Entry();
        work.setAttribute(ATTR_MCP_TOOL, toolName);
        work.setAttribute(ATTR_MCP_REQUEST_ID, id != null ? id.toString() : "");
        work.setAttribute(ATTR_MCP_PROTOCOL_VERSION, reqVersion);
        work.setAttribute(ATTR_MCP_ARGUMENTS, argumentsObj.serialize());

        // Verified caller identity for AL-side authorization/audit. Trustworthy
        // because it comes from the mTLS client cert (or a trusted-proxy header),
        // NOT from client-supplied arguments — and the scalar-flatten loop below
        // refuses $mcp.* keys, so a caller cannot forge $mcp.actor.
        extractClientCertIfNeeded();
        String actor = clientCertDn;
        String actorCn = clientCertCn;
        if (actor == null) {
            String hdrName = getParam("actorHeader");
            if (hdrName != null && !hdrName.trim().isEmpty()) {
                actor = getHeader(httpEntry, hdrName.trim()); // trusts the upstream that sets it
            }
        }
        if (actor != null) {
            work.setAttribute(ATTR_MCP_ACTOR, actor);
        }
        if (actorCn != null) {
            work.setAttribute(ATTR_MCP_ACTOR_CN, actorCn);
        }

        // §3.2: flatten top-level scalar arguments directly onto the Entry too,
        // so the AL's Data Flow can read e.g. work.text instead of always having
        // to re-parse $mcp.arguments for simple cases. Nested objects/arrays are
        // only available via $mcp.arguments.
        //
        // SECURITY: never let a client-supplied argument named "$mcp.*" overwrite
        // a reserved attribute. Without this, a call to a whitelisted tool could
        // smuggle {"$mcp.tool":"<other tool>"} in its arguments and clobber the
        // validated tool name set above — bypassing the toolCatalog allowlist that
        // the AL branches on. Reserved keys are skipped; they remain only as the
        // values the connector itself set.
        for (Object key : argumentsObj.keySet()) {
            String name = key.toString();
            if (name.startsWith("$mcp.")) {
                continue;
            }
            Object value = argumentsObj.get(key);
            if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                work.setAttribute(name, value.toString());
            }
        }

        return work;
    }

    /** Parses the configured toolCatalog (falling back to an empty array on bad JSON). */
    private JSONArray getToolCatalog() {
        try {
            JSONArray tools = JSONArray.parse(getParam("toolCatalog"));
            return tools != null ? tools : new JSONArray();
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private Set<String> getToolNames() {
        Set<String> names = new HashSet<>();
        for (Object entry : getToolCatalog()) {
            if (entry instanceof JSONObject) {
                Object name = ((JSONObject) entry).get("name");
                if (name instanceof String) {
                    names.add((String) name);
                }
            }
        }
        return names;
    }

    /** A well-formed tools/call whose tool name isn't in the catalog: a tool-level error, not a JSON-RPC protocol error. */
    private void sendToolError(Object id, String message) throws Exception {
        JSONObject textBlock = new JSONObject();
        textBlock.put("type", "text");
        textBlock.put("text", message);
        JSONArray content = new JSONArray();
        content.add(textBlock);

        JSONObject result = new JSONObject();
        result.put("content", content);
        result.put("isError", true);

        JSONObject response = new JSONObject();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);

        sendJson(buildHttpReplyEntry(response, HTTP_OK));
    }

    /** Echo the client's requested protocolVersion if we support it; otherwise advertise our preferred one. */
    private String negotiateProtocolVersion(JSONObject rpc) {
        Object params = rpc.get("params");
        if (params instanceof JSONObject) {
            Object requested = ((JSONObject) params).get("protocolVersion");
            if (requested instanceof String && SUPPORTED_PROTOCOL_VERSIONS.contains(requested)) {
                return (String) requested;
            }
        }
        return PROTOCOL_VERSION;
    }

    private void sendInitializeResult(Entry httpEntry, Object id, JSONObject rpc) throws Exception {
        JSONObject result = new JSONObject();
        result.put("protocolVersion", negotiateProtocolVersion(rpc));
        JSONObject capabilities = new JSONObject();
        capabilities.put("tools", new JSONObject());
        result.put("capabilities", capabilities);
        JSONObject serverInfo = new JSONObject();
        serverInfo.put("name", "mcp-server-connector");
        serverInfo.put("version", VERSION_INFO);
        result.put("serverInfo", serverInfo);

        JSONObject response = new JSONObject();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);

        sendJson(buildHttpReplyEntry(response, HTTP_OK));
    }

    private void sendToolsList(Entry httpEntry, Object id) throws Exception {
        JSONObject result = new JSONObject();
        result.put("tools", getToolCatalog());

        JSONObject response = new JSONObject();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);

        sendJson(buildHttpReplyEntry(response, HTTP_OK));
    }

    private void sendAccepted(Entry httpEntry) throws Exception {
        Entry reply = new Entry();
        reply.setAttribute(ATTR_NAME_HTTP_BODY, "");
        reply.setAttribute(ATTR_NAME_HTTP_CONTENT_TYPE, CONTENT_TYPE_JSON);
        reply.setAttribute("http.status", HTTP_ACCEPTED);
        reply.setAttribute("http.Connection", "close");   // one-request-per-connection; see buildHttpReplyEntry
        sendJson(reply);
    }

    private void sendError(Entry httpEntry, Object id, int code, String message) throws Exception {
        JSONObject error = new JSONObject();
        error.put("code", code);
        error.put("message", message);

        JSONObject response = new JSONObject();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", error);

        sendJson(buildHttpReplyEntry(response, HTTP_OK));
    }

    private Entry buildHttpReplyEntry(JSONObject body, String status) throws Exception {
        Entry reply = new Entry();
        reply.setAttribute(ATTR_NAME_HTTP_BODY, body.serialize());
        reply.setAttribute(ATTR_NAME_HTTP_CONTENT_TYPE, CONTENT_TYPE_JSON);
        reply.setAttribute("http.status", status);
        // This connector serves exactly one request per TCP connection and closes the
        // socket after replying. Without "Connection: close" the HTTP/1.1 default is
        // keep-alive, so a client (e.g. the MCP SDK's undici/fetch transport) reuses
        // the connection for its next message (initialize -> notifications/initialized
        // -> tools/list) and that write hits the already-closed socket -> ECONNRESET,
        // failing the handshake. curl masks it by silently retrying on a fresh socket.
        // Advertising close makes the client open a new connection per message.
        reply.setAttribute("http.Connection", "close");
        return reply;
    }

    /** Writes the HTTP reply. Package-private so tests can capture replies instead of writing to a socket. */
    void sendJson(Entry reply) throws Exception {
        super.replyEntry(reply);
    }
}
