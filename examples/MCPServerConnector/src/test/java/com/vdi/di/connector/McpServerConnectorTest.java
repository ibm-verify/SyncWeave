/*
Copyright contributors to the SyncWeave project

SPDX-License-Identifier: Apache-2.0
*/
package com.vdi.di.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.di.entry.Entry;
import com.ibm.json.java.JSONArray;
import com.ibm.json.java.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Socket-free tests of the MCP message layer. The connector's config lookup
 * and reply writer are replaced by in-memory fakes; everything else is the
 * real production code path (processRequest / replyEntry).
 */
class McpServerConnectorTest {

    private static final String TOOLS =
            "[{\"name\":\"echo\",\"description\":\"Echo text\","
            + "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}}]";

    /** Connector with params served from a map and replies captured in a list. */
    private static final class TestConnector extends McpServerConnector {
        final Map<String, String> params = new HashMap<>();
        final List<Entry> replies = new ArrayList<>();

        @Override
        public String getParam(String name) {
            return params.get(name);
        }

        @Override
        public void logmsg(String msg) {
            // swallow: no VDI log in unit tests
        }

        @Override
        void sendJson(Entry reply) {
            replies.add(reply);
        }
    }

    private TestConnector conn;

    @BeforeEach
    void setUp() {
        conn = new TestConnector();
        conn.params.put("toolCatalog", TOOLS);
    }

    // ---- helpers -------------------------------------------------------

    private static Entry request(String method, String body) {
        Entry e = new Entry();
        e.setAttribute("http.method", method);
        e.setAttribute("http.base", "/mcp");
        e.setAttribute("http.bodyAsString", body);
        return e;
    }

    private static Entry post(String body) {
        return request("POST", body);
    }

    private static String rpc(String method, String id, String params) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\""
                + (params == null ? "" : ",\"params\":" + params) + "}";
    }

    private Entry onlyReply() {
        assertEquals(1, conn.replies.size(), "expected exactly one reply");
        return conn.replies.get(0);
    }

    private static JSONObject bodyOf(Entry reply) throws Exception {
        return JSONObject.parse(reply.getString("http.body"));
    }

    private static JSONObject obj(Object o) {
        return (JSONObject) o;
    }

    private void assertStatus(String status) {
        assertEquals(status, onlyReply().getString("http.status"));
    }

    // ---- protocol methods ---------------------------------------------

    @Test
    void initializeEchoesSupportedProtocolVersion() throws Exception {
        assertNull(conn.processRequest(post(rpc("initialize", "1", "{\"protocolVersion\":\"2025-03-26\"}"))));
        assertStatus("200 OK");
        JSONObject result = obj(bodyOf(onlyReply()).get("result"));
        assertEquals("2025-03-26", result.get("protocolVersion"));
        assertEquals("mcp-server-connector", obj(result.get("serverInfo")).get("name"));
        assertNotNull(obj(result.get("capabilities")).get("tools"));
    }

    @Test
    void initializeFallsBackToPreferredVersionWhenUnsupported() throws Exception {
        conn.processRequest(post(rpc("initialize", "1", "{\"protocolVersion\":\"1999-01-01\"}")));
        assertEquals(McpServerConnector.PROTOCOL_VERSION,
                obj(bodyOf(onlyReply()).get("result")).get("protocolVersion"));
    }

    @Test
    void initializedNotificationGets202() throws Exception {
        assertNull(conn.processRequest(post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")));
        assertStatus("202 Accepted");
    }

    @Test
    void toolsListReturnsConfiguredCatalog() throws Exception {
        conn.processRequest(post(rpc("tools/list", "2", null)));
        JSONArray tools = (JSONArray) obj(bodyOf(onlyReply()).get("result")).get("tools");
        assertEquals(1, tools.size());
        assertEquals("echo", obj(tools.get(0)).get("name"));
    }

    @Test
    void toolsListIsEmptyWhenCatalogMalformed() throws Exception {
        conn.params.put("toolCatalog", "not json");
        conn.processRequest(post(rpc("tools/list", "2", null)));
        assertEquals(0, ((JSONArray) obj(bodyOf(onlyReply()).get("result")).get("tools")).size());
    }

    @Test
    void unknownMethodIsJsonRpcMethodNotFound() throws Exception {
        conn.processRequest(post(rpc("resources/list", "3", null)));
        assertEquals(-32601L, ((Number) obj(bodyOf(onlyReply()).get("error")).get("code")).longValue());
    }

    @Test
    void malformedJsonIsParseError() throws Exception {
        conn.processRequest(post("{not json"));
        assertEquals(-32700L, ((Number) obj(bodyOf(onlyReply()).get("error")).get("code")).longValue());
    }

    @Test
    void missingMethodIsInvalidRequest() throws Exception {
        conn.processRequest(post("{\"jsonrpc\":\"2.0\",\"id\":1}"));
        assertEquals(-32600L, ((Number) obj(bodyOf(onlyReply()).get("error")).get("code")).longValue());
    }

    // ---- tools/call ----------------------------------------------------

    @Test
    void toolsCallBuildsWorkEntry() throws Exception {
        Entry work = conn.processRequest(post(rpc("tools/call", "7",
                "{\"name\":\"echo\",\"arguments\":{\"text\":\"hi\",\"n\":3,\"ok\":true,\"nested\":{\"a\":1}}}")));
        assertNotNull(work);
        assertTrue(conn.replies.isEmpty(), "tools/call must not reply until the AL finishes");
        assertEquals("echo", work.getString(McpServerConnector.ATTR_MCP_TOOL));
        assertEquals("7", work.getString(McpServerConnector.ATTR_MCP_REQUEST_ID));
        assertEquals(McpServerConnector.PROTOCOL_VERSION, work.getString(McpServerConnector.ATTR_MCP_PROTOCOL_VERSION));
        assertEquals("hi", work.getString("text"));
        assertEquals("3", work.getString("n"));
        assertEquals("true", work.getString("ok"));
        assertNull(work.getString("nested"), "nested values are only available via $mcp.arguments");
        JSONObject args = JSONObject.parse(work.getString(McpServerConnector.ATTR_MCP_ARGUMENTS));
        assertEquals(1L, ((Number) obj(args.get("nested")).get("a")).longValue());
    }

    @Test
    void toolsCallUnknownToolIsToolLevelError() throws Exception {
        assertNull(conn.processRequest(post(rpc("tools/call", "8", "{\"name\":\"nope\",\"arguments\":{}}"))));
        assertStatus("200 OK");
        JSONObject result = obj(bodyOf(onlyReply()).get("result"));
        assertEquals(Boolean.TRUE, result.get("isError"));
    }

    @Test
    void reservedArgumentKeysCannotOverwriteConnectorAttributes() throws Exception {
        Entry work = conn.processRequest(post(rpc("tools/call", "9",
                "{\"name\":\"echo\",\"arguments\":{\"$mcp.tool\":\"admin\",\"$mcp.actor\":\"root\"}}")));
        assertEquals("echo", work.getString(McpServerConnector.ATTR_MCP_TOOL));
        assertNull(work.getString(McpServerConnector.ATTR_MCP_ACTOR));
    }

    @Test
    void actorHeaderPopulatesActorWhenConfigured() throws Exception {
        conn.params.put("actorHeader", "X-Forwarded-User");
        Entry req = post(rpc("tools/call", "10", "{\"name\":\"echo\",\"arguments\":{}}"));
        req.setAttribute("http.X-Forwarded-User", "alice");
        Entry work = conn.processRequest(req);
        assertEquals("alice", work.getString(McpServerConnector.ATTR_MCP_ACTOR));
    }

    @Test
    void actorIsAbsentWithoutActorHeaderConfig() throws Exception {
        Entry req = post(rpc("tools/call", "10", "{\"name\":\"echo\",\"arguments\":{}}"));
        req.setAttribute("http.X-Forwarded-User", "alice");
        assertNull(conn.processRequest(req).getString(McpServerConnector.ATTR_MCP_ACTOR));
    }

    // ---- replyEntry ----------------------------------------------------

    private JSONObject replyFor(Entry alResult) throws Exception {
        conn.processRequest(post(rpc("tools/call", "42", "{\"name\":\"echo\",\"arguments\":{}}")));
        conn.replyEntry(alResult);
        return bodyOf(onlyReply());
    }

    @Test
    void replyEntryMapsResultAndPreservesRequestId() throws Exception {
        Entry al = new Entry();
        al.setAttribute(McpServerConnector.ATTR_MCP_RESULT, "done");
        al.setAttribute(McpServerConnector.ATTR_MCP_STRUCTURED, "{\"count\":2}");
        JSONObject resp = replyFor(al);
        assertEquals(42L, ((Number) resp.get("id")).longValue());
        JSONObject result = obj(resp.get("result"));
        assertEquals(Boolean.FALSE, result.get("isError"));
        assertEquals("done", obj(((JSONArray) result.get("content")).get(0)).get("text"));
        assertEquals(2L, ((Number) obj(result.get("structuredContent")).get("count")).longValue());
    }

    @Test
    void replyEntryHonoursIsError() throws Exception {
        Entry al = new Entry();
        al.setAttribute(McpServerConnector.ATTR_MCP_RESULT, "boom");
        al.setAttribute(McpServerConnector.ATTR_MCP_IS_ERROR, "true");
        assertEquals(Boolean.TRUE, obj(replyFor(al).get("result")).get("isError"));
    }

    @Test
    void replyEntryNeverLeaksEntryAttributesWhenNoResultSet() throws Exception {
        Entry al = new Entry();
        al.setAttribute("password", "s3cret");
        String raw = replyFor(al).serialize();
        assertFalse(raw.contains("s3cret"), "work Entry attributes must not be serialized to the client");
        assertTrue(raw.contains("Tool completed but returned no result."));
    }

    @Test
    void replyEntryIgnoresUnparsableStructured() throws Exception {
        Entry al = new Entry();
        al.setAttribute(McpServerConnector.ATTR_MCP_RESULT, "ok");
        al.setAttribute(McpServerConnector.ATTR_MCP_STRUCTURED, "{broken");
        assertNull(obj(replyFor(al).get("result")).get("structuredContent"));
    }

    // ---- transport gates ----------------------------------------------

    @Test
    void healthCheckAnsweredUnauthenticated() throws Exception {
        conn.params.put("healthPath", "/healthz");
        conn.params.put("authMode", "bearer");
        conn.params.put("bearerToken", "tok");
        Entry req = request("GET", "");
        req.setAttribute("http.base", "/healthz");
        assertNull(conn.processRequest(req));
        assertStatus("200 OK");
        assertEquals("ok", bodyOf(onlyReply()).get("status"));
    }

    @Test
    void bearerAuthRejectsMissingOrWrongToken() throws Exception {
        conn.params.put("authMode", "bearer");
        conn.params.put("bearerToken", "tok");
        conn.processRequest(post(rpc("tools/list", "1", null)));
        assertStatus("401 Unauthorized");

        conn.replies.clear();
        Entry wrong = post(rpc("tools/list", "1", null));
        wrong.setAttribute("http.Authorization", "Bearer nope");
        conn.processRequest(wrong);
        assertStatus("401 Unauthorized");
    }

    @Test
    void bearerAuthAcceptsCorrectTokenCaseInsensitiveHeaderName() throws Exception {
        conn.params.put("authMode", "bearer");
        conn.params.put("bearerToken", "tok");
        Entry ok = post(rpc("tools/list", "1", null));
        ok.setAttribute("http.authorization", "Bearer tok");
        conn.processRequest(ok);
        assertStatus("200 OK");
        assertNotNull(bodyOf(onlyReply()).get("result"));
    }

    @Test
    void bearerModeWithNoConfiguredTokenRejectsEverything() throws Exception {
        conn.params.put("authMode", "bearer");
        Entry req = post(rpc("tools/list", "1", null));
        req.setAttribute("http.Authorization", "Bearer ");
        conn.processRequest(req);
        assertStatus("401 Unauthorized");
    }

    @Test
    void originAllowlistValidatesPresentOriginAndAllowsMissing() throws Exception {
        conn.params.put("allowedOrigins", "https://a.example, https://b.example");
        // Missing Origin passes: non-browser clients (curl, SDKs) send none; bearer auth is the boundary.
        conn.processRequest(post(rpc("tools/list", "1", null)));
        assertStatus("200 OK");

        conn.replies.clear();
        Entry bad = post(rpc("tools/list", "1", null));
        bad.setAttribute("http.Origin", "https://evil.example");
        conn.processRequest(bad);
        assertStatus("403 Forbidden");

        conn.replies.clear();
        Entry good = post(rpc("tools/list", "1", null));
        good.setAttribute("http.Origin", "HTTPS://B.example");
        conn.processRequest(good);
        assertStatus("200 OK");
    }

    @Test
    void securityGatesRunBeforePathAndMethodChecks() throws Exception {
        conn.params.put("authMode", "bearer");
        conn.params.put("bearerToken", "tok");
        conn.params.put("endpointPath", "/mcp");
        Entry req = request("DELETE", "");
        req.setAttribute("http.base", "/elsewhere");
        conn.processRequest(req);
        assertStatus("401 Unauthorized"); // not 404/405: unauthenticated callers can't probe
    }

    @Test
    void wrongEndpointPathIs404() throws Exception {
        conn.params.put("endpointPath", "/mcp");
        Entry req = post(rpc("tools/list", "1", null));
        req.setAttribute("http.base", "/other");
        conn.processRequest(req);
        assertStatus("404 Not Found");
    }

    @Test
    void nonPostMethodIs405() throws Exception {
        conn.processRequest(request("GET", ""));
        assertStatus("405 Method Not Allowed");
    }

    @Test
    void oversizedBodyIs413() throws Exception {
        conn.params.put("maxRequestBytes", "20");
        conn.processRequest(post(rpc("tools/list", "1", null)));
        assertStatus("413 Payload Too Large");
    }

    @Test
    void unsupportedProtocolVersionHeaderIs400ExceptOnInitialize() throws Exception {
        Entry list = post(rpc("tools/list", "1", null));
        list.setAttribute("http.MCP-Protocol-Version", "1999-01-01");
        conn.processRequest(list);
        assertStatus("400 Bad Request");

        conn.replies.clear();
        Entry init = post(rpc("initialize", "1", "{}"));
        init.setAttribute("http.MCP-Protocol-Version", "1999-01-01");
        conn.processRequest(init);
        assertStatus("200 OK");
    }

    @Test
    void protocolVersionHeaderIsReflectedToTheAssemblyLine() throws Exception {
        Entry req = post(rpc("tools/call", "1", "{\"name\":\"echo\",\"arguments\":{}}"));
        req.setAttribute("http.MCP-Protocol-Version", "2025-06-18");
        assertEquals("2025-06-18",
                conn.processRequest(req).getString(McpServerConnector.ATTR_MCP_PROTOCOL_VERSION));
    }
}
