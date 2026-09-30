# Build Spec — MCP Server Connector for IBM VDI 10

**Goal:** A custom IBM Verify Directory Integrator (VDI 10) **Server-mode Connector** that acts as the FEED of an AssemblyLine — modeled on the stock HTTP Server Connector — but speaks **MCP (Model Context Protocol, JSON-RPC 2.0 over Streamable HTTP)**. When the AL runs, it *is* an MCP server: an inbound `tools/call` becomes the AL's work Entry; the AL's reply Entry becomes the tool result.

**Target client:** Claude (Desktop / Code / Cowork) connecting to the AL's MCP endpoint.

**Reference install used for verification:** `/path/to/ISVDI` (VDI 10, Java 17 / OpenJ9 bundled JRE).

---

## 0. Locked decisions

| Decision | Value |
| --- | --- |
| Shape | **A** — SDI connector that *is* the MCP server; AL = endpoint |
| Model | **Subclass** of `com.ibm.di.connector.HTTPServerConnector` as feed of an AL |
| Transport | **Streamable HTTP**, JSON-RPC 2.0, single `/mcp` endpoint |
| MCP protocol version | **2025-11-25** (stable) |
| Response mode (v1) | **Stateless JSON** — return `application/json` per request; **no SSE, no session id** in v1 |
| AL runtime | **Persistent listener** (Server mode, always up) |
| Target VDI | **VDI 10** (Java 17) |
| Tool catalog | **Configurable multi-tool list** with an AL-side discriminator |
| Endpoint auth (v1) | **Bearer token**, with TLS + **optional mTLS** as config options |
| JSON library | **JSON4J** (`com.ibm.json.java.JSONObject`/`JSONArray`), platform-provided, compile-only |

---

## 1. Phase 0 verification — DONE

Findings from inspecting the local VDI 10 install (`docs/api`, `jars/`, `etc/global.properties`):

1. **Server-mode contract on `HTTPServerConnector`** (`docs/api/com/ibm/di/connector/HTTPServerConnector.html`):
   - `getNextClient()` blocks until a client connects; returns a per-connection `ConnectorInterface`. This *is* the concurrency unit — one inbound connection drives one AL cycle on that per-connection instance.
   - `getNextEntry()` parses the inbound HTTP request into an `Entry` (used in Iterator-feed mode).
   - `replyEntry(Entry)` writes the HTTP response back to that same client; `putEntry(Entry)` adds chunking support.
   - `isConnectionClosed()`, `isTerminating()`, `terminate()`/`terminateServer()` handle lifecycle/shutdown.
   - The connector already exposes `ATTR_NAME_HTTP_BODY`, `ATTR_NAME_HTTP_CONTENT_TYPE`, `ATTR_NAME_HTTP_AUTH_ENTRY`, etc. as Entry attributes.
   - **Decision:** subclass `HTTPServerConnector` rather than write a raw socket listener or wrapper. Let it handle socket/TLS/chunking/HTTP framing; our subclass reads `http.body` as a JSON-RPC envelope and writes the MCP response into the reply Entry's body before calling `replyEntry`.

2. **JSON library — resolved, not Jackson.** Originally assumed Jackson 1.9.13 (`org.codehaus.jackson`) would be needed. Verified present at `osgi/plugins/jackson-core-asl.jar` + `jackson-mapper-asl.jar` (both 1.9.13, full tree model available) — but these live in an **OSGi bundle directory**, and `etc/global.properties` references a distinct `com.ibm.di.loader.userjars` classloader for connector jars, making visibility from `jars/connectors` uncertain without a runtime probe.
   - **Better alternative found and adopted:** `jars/3rdparty/others/JSON4J.jar` (`com.ibm.json.java.JSONObject`/`JSONArray`) sits on the same flat, non-OSGi classpath tier as the platform's other third-party jars (log4j, axis, etc.) and as `HTTPServerConnector.jar` itself. VDI's own built-in JSON Parser component (`jars/parsers/JSONParser.jar`, `com.ibm.di.parser.JSONParser`) confirms platform-jar-based JSON handling is the native pattern here.
   - JSON4J's API (simple `Map`/`List`-like `JSONObject`/`JSONArray`, `get`/`put`, `toString()` / parse-from-string) is sufficient: we hand-map fixed JSON-RPC envelope shapes (§3), so we don't need Jackson's annotation-driven databind.
   - **Decision:** use JSON4J exclusively. No Jackson dependency, no shading/bundling question, no classloader risk. `JSON4J.jar` is **compile-only** (provided at runtime by the platform), exactly like `HTTPServerConnector.jar`, `miserver.jar`, `miconfig.jar`.

3. **Compile-only platform jars identified:**
   - `jars/connectors/HTTPServerConnector.jar` (base class)
   - `jars/common/miserver.jar`, `jars/common/miconfig.jar` (Connector/Entry/AL runtime API)
   - `jars/3rdparty/others/JSON4J.jar` (JSON)

None of these are repackaged into our connector jar; all are `provided`/`system` scope in the build, present on the target VDI's runtime classpath by definition.

---

## 2. MCP protocol surface (2025-11-25)

Single MCP endpoint (configurable path, default `/mcp`) supporting POST and GET.

**Methods to implement (v1):**
- `initialize` — capability negotiation; advertise `tools` capability; echo negotiated `protocolVersion` (`2025-11-25`).
- `notifications/initialized` — accept; return **202 Accepted**, no body.
- `tools/list` — return the configured tool catalog (see §3.1).
- `tools/call` — map to work Entry, run AL, return reply (see §3.2–3.3).

**Transport rules (MUST):**
- **POST** carries one JSON-RPC request/notification/response. For a **request**, return `Content-Type: application/json` with one JSON object (v1 stateless mode — do **not** open SSE). For a **notification/response**, return **202 Accepted** with no body.
- Validate the **`Origin`** header on every connection; respond **403 Forbidden** if present and invalid (DNS-rebinding protection).
- Honor the **`MCP-Protocol-Version`** header on post-initialize requests; respond **400** if invalid/unsupported. If absent, assume `2025-03-26` per spec.
- **GET** to the endpoint: v1 does not offer a server→client SSE stream — return **405 Method Not Allowed**.
- When binding locally, bind to `127.0.0.1`; require auth on all connections (§6).

**Deferred to later phase:** `MCP-Session-Id` sessions, SSE streaming, resumability, `notifications/tools/list_changed`, resources, prompts, server-initiated requests.

---

## 3. The three contracts (the real design surface)

### 3.1 Tool catalog → `tools/list`
Connector config parameter holds a JSON array of tool definitions:

```json
[
  { "name": "lookup_user", "description": "...", "inputSchema": { "type": "object", "properties": { ... }, "required": [...] } }
]
```

`tools/list` returns this verbatim (validated). One AL can serve several tools; it branches on the tool name.

### 3.2 Request mapping — `tools/call` → work Entry
Build the inbound Entry from the call:
- Flatten top-level scalar `arguments` to Entry attributes.
- Put the **raw arguments JSON** in a reserved attribute `$mcp.arguments` so the AL can parse nested structure with JSON4J.
- Metadata attributes: `$mcp.tool` (tool name, the discriminator), `$mcp.requestId` (JSON-RPC id), `$mcp.protocolVersion`.
- Verified caller identity (when available): `$mcp.actor` (mTLS client-cert subject DN, or the value of a configured trusted `actorHeader`) and `$mcp.actorCn` (the CN). Used for per-user authorization/audit in write flows (see §6, UC2). Not forgeable via arguments — the scalar-flatten step refuses client-supplied `$mcp.*` keys.

### 3.3 Response contract — reply Entry → `tools/call` result
AL sets reserved attributes on the outgoing Entry; the connector maps them to the MCP result:
- `$mcp.result` → text content block.
- `$mcp.structured` → JSON used for `structuredContent`.
- `$mcp.isError` → `true` marks the tool result as an error (MCP tool-level error, not a JSON-RPC error).
- Absence of all three → return a generic success with a fixed placeholder text ("Tool completed but returned no result."). **Security:** it does NOT serialize the work Entry (that would leak whatever attributes the Data Flow loaded — user records, credentials — to the MCP client). Always set `$mcp.result` explicitly.

Map transport/dispatch failures (bad JSON, unknown method, auth failure) to **JSON-RPC error responses**; map AL business failures to **tool results with `isError: true`** with an actionable message.

---

## 4. Connector configuration (Config Editor form)

Parameters (use the `tdi.xml` form, password syntax for secrets, progressive disclosure per `<param>_changed()` convention):

- **General:** `endpointPath` (default `/mcp`), `bindAddress`, `port`, `toolCatalog` (JSON).
- **Security:** `authMode` (none | bearer | mtls), `bearerToken` (password), `allowedOrigins` (list), `useSSL` (boolean), `needClientAuth` (boolean, mTLS). **Correction vs. original plan:** there are deliberately *no* per-connector keystore/truststore path/password fields. The inherited `HTTPServerConnector` SSL layer sources its keystore + truststore from the **SDI server's** global TLS config (via `getRSInterface().getServerSocketFactory(true)`), not from connector parameters — so mTLS is configured at the server level plus the two booleans here. See §6 and docs/CONFIGURE.md §3a.
- **Advanced:** `healthPath` (unauthenticated GET liveness probe), `maxRequestBytes` (413 cap), `actorHeader` (trusted upstream header → `$mcp.actor` for the reverse-proxy actor-identity model). (request timeout, max concurrent requests, verbose logging remain deferred — see [BACKLOG.md](BACKLOG.md).)

Include a form even though some fields are advanced, so the Config Editor reports no missing form.

---

## 5. Packaging (`tdi.xml`)

- `tdi.xml` at jar root, `Folder name="Connectors"` with `<parameter name="connectorType">fully.qualified.McpServerConnector</parameter>` and supported modes including `Server`.
- Matching `Folder name="Forms"` form definition for the params in §4.
- Deploy jar to `VDI_install_dir/jars/connectors`.

---

## 6. Security (v1)

**Check ordering (post security review).** `getNextEntry()` runs gates in this order: health probe (unauthenticated, narrow GET) → `Origin` (403) → bearer auth (401) → `endpointPath` (404) → method (405) → size (413) → parse/dispatch. Security gates (Origin, auth) come *before* resource checks so unauthenticated/cross-origin callers can't probe path/method existence — chosen for networked (not just localhost) deployments.


- **Bearer:** require `Authorization: Bearer <token>`; constant-time compare (`MessageDigest.isEqual`); 401 on mismatch. The `bearerToken` is a `PASSWORD`-syntax param, stored `encrypted="true"` and **auto-decrypted at runtime** by the engine — `getParam("bearerToken")` returns plaintext, no manual decryption needed (confirmed live, §8/Phase 3).
- **TLS / mTLS:** handled by the inherited `HTTPServerConnector` SSL layer via `useSSL` + `needClientAuth`, using the **SDI server's** keystore/truststore (not connector params — see §4). `needClientAuth` is applied to the listening `SSLServerSocket` (`setNeedClientAuth(true)`), so our `getNextClient()` override — which accepts on that same socket — does not bypass client-cert verification (confirmed by disassembly). An untrusted/missing client cert fails at the TLS handshake, before the MCP layer runs. Never log secrets.
- Mask any secret-bearing config in logs/errors. Validate `Origin` (§2). Bind localhost unless explicitly opened.

---

## 7. Dependency / classpath strategy (revised — JSON4J, not Jackson)

- **JSON library: JSON4J** (`com.ibm.json.java.JSONObject`/`JSONArray`), shipped at `jars/3rdparty/others/JSON4J.jar`. Flat classpath tier, same as `HTTPServerConnector.jar` — no OSGi visibility question. **Compile-only / provided**, not repackaged.
- **Do not use Jackson** (neither the bundled 1.9.13 ASL jars in `osgi/plugins`, nor any 2.x jar). The OSGi jars are reserved for OSGi-loaded platform features (e.g. ActiveMQ-backed JMS/REST); relying on them from a `jars/connectors` component is an unverified assumption we don't need to make.
- Keep all platform runtime jars (`jars/common/miserver.jar`, `jars/common/miconfig.jar`, `jars/connectors/HTTPServerConnector.jar`, `jars/3rdparty/others/JSON4J.jar`) **compile-only**; never repackage them into the connector jar.
- No new third-party runtime dependencies introduced by this connector at all — everything it needs is already on the target VDI's classpath.

---

## 1b. Phase 1 correction — getNextClient() subclassing trap

Live testing in the Config Editor showed `$mcp.*` attributes never reached the AL's Data Flow, and none of the connector's own log lines fired — meaning `getNextEntry()`/`replyEntry()` overrides were never executing on real traffic, even after redeploying and restarting the server. Disassembling `HTTPServerConnector.class` (`javap -c`) found the cause:

```
public com.ibm.di.connector.ConnectorInterface getNextClient() throws java.lang.Exception;
  ...
  92: new   #19   // class com/ibm/di/connector/HTTPServerConnector
```

`getNextClient()` hardcodes `new HTTPServerConnector()` for the per-connection object it returns — not `this.getClass().newInstance()`, not a clone. The framework always processes the actual request/response on a base-class instance, so a plain subclass's overrides are silently bypassed. This is the concrete answer to the open question from §1 ("the threading/concurrency model... determines whether v1 must single-thread requests") — it turned out to also determine whether subclassing works at all.

**Fix:** `McpServerConnector` now overrides `getNextClient()` itself, replicating the exact same setup sequence (verified step-by-step against the decompiled bytecode — accept the socket, `setServerConnector`/`setConfiguration`/`setRSInterface`/`setName`/`setLog`, then `initialize(socket)`) but constructing `new McpServerConnector()` instead. Every step in that sequence uses public inherited methods except obtaining the listening `ServerSocket` itself (the `mServerSocket` field is private with no accessor), so reflection (`Field.setAccessible`) is used for that one field only. `ConnectorInterface` (confirmed via its own decompiled method list) declares `getNextEntry`, `replyEntry`, `putEntry`, and `terminateServer` directly — i.e. the AssemblyLine engine interacts with the per-connection object purely through that interface, which is what makes returning our own subclass from `getNextClient()` sufficient; no proxy/delegation layer is needed.

Risk to flag: this reaches into one private field of a vendor class via reflection. If a future VDI patch renames or restructures `HTTPServerConnector`'s internals, this breaks. Acceptable trade-off for keeping all MCP protocol logic in compiled Java inside the connector, as decided, rather than moving it into AL-level Hook scripts.

## 8. Phased plan

- **Phase 0 — Verify & scaffold.** ✅ Verifications done (§1). Scaffolding (this commit): Maven project, `tdi.xml` skeleton, package skeleton, compiled against VDI jars.
- **Phase 1 — Echo path.** ✅ **Done, verified live end-to-end** in the Config Editor against a real `MCP_Smoke_Test` AssemblyLine (`echo` and `server_time` test tools, §1b/§1c). `initialize`, `notifications/initialized`, `tools/list`, and `tools/call` all confirmed working: `$mcp.tool`/`$mcp.arguments`/`$mcp.requestId`/`$mcp.protocolVersion` correctly populate the AL's work Entry, the Data Flow branches on `$mcp.tool` and sets `$mcp.result`/`$mcp.structured`, and `replyEntry()` correctly translates that back into a JSON-RPC tool result delivered to the HTTP client. Getting here required two real bugs to be found and fixed live (§1b, §1c) beyond the original static classloading check.

## 1c. Phase 1 bug — wrong body attribute

Once §1b's `getNextClient()` fix made the dispatch path actually run, the first live request returned a real JSON-RPC error response — progress — but with a parse failure: `"Expecting '{' ... obtained token: 'Token: ['"`. The leading `[` was the tell: `http.body` holds the **raw `byte[]`**, not a String, so `Entry.getString("http.body")` was falling back to `Object.toString()` (something like `[B@65166f15`). `HTTPServerConnector` also exposes a separately-decoded String copy under `http.bodyAsString` (visible in the CE's own Entry dumps); switched to reading that instead. Fixed in one line, confirmed working immediately after.
- **Phase 2 — Contracts.** ✅ Done. `toolCatalog` now has real teeth: `tools/call` validates the tool name against the configured catalog *before* invoking the AL — an unknown/missing name short-circuits with a tool-level error (`isError: true`) without spending an AL cycle. Full request mapping (§3.2) implemented: top-level scalar `arguments` are flattened directly onto the work Entry (e.g. `text`) in addition to the raw `$mcp.arguments` JSON, for AL logic that doesn't want to re-parse JSON for simple cases. Response contract (§3.3) was already correct as of Phase 1.
- **Phase 3 — Security & robustness.** ✅ Core security gate done and **fully validated live** against the real install (every branch curl-tested: `405` non-`POST`, bearer `401` for missing/wrong token and `200` for correct, `400` bad `MCP-Protocol-Version`, `403` non-matching `Origin` with an allow-list configured). Implemented: HTTP method enforcement (`405` on non-`POST`), `Origin` validation (`403`, only enforced when `allowedOrigins` is non-empty — absent header always passes, since non-browser clients like curl/MCP Inspector don't send one), `MCP-Protocol-Version` validation (`400`, only enforced on post-`initialize` requests per spec), and bearer token auth (`401`, constant-time compare via `MessageDigest.isEqual`, fails closed if `authMode=bearer` but no token configured). `authMode=mtls` needed no new code at all — it's `useSSL`+`needClientAuth`, both inherited unmodified from `HTTPServerConnector`; the per-connection `Socket` from `getNextClient()`'s `accept()` is already an `SSLSocket` if the listener was configured for SSL, so client-cert verification happens at the TLS handshake before any connector code runs.
  - **Done in the Phase 3 tail:** `endpointPath` enforcement (`404` on mismatch) and `bindAddress` enforcement (post-accept local-address check — the inherited connector only binds `0.0.0.0`, so a rejected connection is closed and skipped via `RetryEntryException` rather than terminating the listener). Plus protocol-version negotiation (support set `{2025-11-25, 2025-06-18, 2025-03-26}`, echo the client's requested version from `initialize` if supported) so real MCP clients aren't forced onto a single version — a Phase 4 interoperability concern pulled forward.
  - **Added in the v1 tail (post-acceptance):** health endpoint (`healthPath` param — unauthenticated `GET` → `200`), and request size limit (`maxRequestBytes` → `413`, best-effort since the parser reads the body first). See [BACKLOG.md](BACKLOG.md).
  - **Deliberately deferred (with rationale):** request/idle timeout (partly covered by the inherited `idleConnectionTimeout`), max concurrent requests (governed by AssemblyLine pool size, a deployment concern), and the architectural v2 items SSE/streaming and sessions (`Mcp-Session-Id`) — design notes in [BACKLOG.md](BACKLOG.md). JSON-RPC vs tool-error mapping was already correct as of Phase 1/2.
  - **Confirmed during Phase 3 testing:** the `PASSWORD`-syntax `bearerToken` is stored `encrypted="true"` in the config XML (~340-char ciphertext) but the engine **auto-decrypts it at runtime** — `getParam("bearerToken")` returns the plaintext directly, no manual `SecurityCrypto`/`CryptoFactory` call needed. This retires the secret-sourcing concern from §10 for the config-param case. (Verified live via a temporary masked diagnostic, since removed; the only "bug" found was operator error — pasting `bearerToken=test123` instead of `test123` into the field.)
- **Phase 4 — Package & validate.** ✅ **Done — §9 acceptance met.** Packaging verified (`tdi.xml` at jar root, full form loads in the CE, connector appears as `MCPServerConnector`). **Claude Code connected as a live MCP client and successfully called `server_time` end-to-end** (full `tools/call` round-trip: client → AL Data Flow → reply), and the same call via curl returns a correct JSON-RPC result with both `content` and `structuredContent`. Required one critical fix found only under a real client (§1d).

## 1d. Phase 4 bug — serverReply: the tools/call success path never replied

Under curl we'd been reading the AL log and seeing `$mcp.result` set correctly, but never actually confirmed the HTTP *response* went back for the `tools/call` **success** path — and it didn't. Claude (which waits for the response) exposed it by hanging.

Root cause: the direct-reply methods (`initialize`/`tools/list`/errors) call `super.replyEntry()` *inside* `getNextEntry()` and return `null`, so they always worked. But `tools/call` returns a work Entry; delivering its response depends on the AssemblyLine calling `replyEntry()` at **end of cycle**. That only happens when the connector config's `getReplyRequired()` is true — which (confirmed by disassembling `ConnectorConfigImpl`) reads the **`serverReply`** boolean parameter, default `false`. The stock `HTTPServerConnector` sets `serverReply=true` in its `tdi.xml`; ours didn't, so the AL never added the reply channel and the success path silently never responded.

Fix: add `<parameter name="serverReply">true</parameter>` to the connector's `Configuration` in `tdi.xml`. Confirmed live: the entry the reply channel hands to `replyEntry()` carries `$mcp.result`/`$mcp.structured` intact (the default output mapping passes work attributes through — no manual Output Map required), so the §3.3 response contract works as designed once the reply channel is enabled.

**Lesson:** all the "working" responses through Phase 3 were the direct-reply paths; the AL-mediated reply path was untested until a real client that blocks on the response (Claude) surfaced it. curl-from-a-script masked it because we were inspecting logs rather than the curl response body.

---

## 9. Acceptance / validation

- `GET` health-style probe and MCP Inspector `initialize` succeed.
- `tools/list` returns the configured catalog.
- A `tools/call` round-trips: arguments → Entry → AL logic → reply Entry → tool result (text + `structuredContent`).
- Invalid `Origin` → 403; bad/missing `MCP-Protocol-Version` → 400; missing/invalid bearer → 401; client-supplied `notification` → 202.
- Connector loads cleanly with **no classpath errors** in the VDI logs.
- Component appears under the expected system namespace in the Config Editor.

---

## 10. Open items to settle during build

- Whether one generic AL handles all tools via `$mcp.tool` (current lean: yes, for v1 — simpler packaging, matches "configurable multi-tool list" decision), or each tool maps to a distinct AL/flow (documented future option).
- Whether to expose a minimal `GET` health endpoint distinct from the MCP GET (which returns 405).
- Token/keystore secret sourcing — config param now; Vault integration is a natural follow-on given existing patterns.
