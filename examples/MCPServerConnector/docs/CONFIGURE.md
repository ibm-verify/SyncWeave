# Configuring the MCP Server Connector

## 1. Deploy

Copy the built jar into VDI's connector directory, then restart the server / Config Editor so it rescans `jars/connectors`:

```
cp target/mcp-server-connector.jar /path/to/ISVDI/jars/connectors/
```

## 2. Create the AssemblyLine

1. In the Config Editor, create a new AssemblyLine.
2. Under **Feed**, add a connector and pick **MCPServerConnector** from the connector list.
3. Set **Mode** to `Server`.

## 3. Connection tab settings

| Field | What to put | Notes |
| --- | --- | --- |
| TCP Port | e.g. `8443` | Required. Port the listener binds to. |
| Bind Address | `127.0.0.1` | Enforced post-accept: connections arriving on a different local interface are rejected. Empty or `0.0.0.0` = accept on any interface. See note below. |
| Endpoint Path | `/mcp` | Requests to any other path get `404`. Empty = accept any path. |
| Tool Catalog (JSON) | a JSON array of tool definitions, e.g.: `[{"name":"lookup_user","description":"Look up a user by id","inputSchema":{"type":"object","properties":{"id":{"type":"string"}},"required":["id"]}}]` | Returned verbatim by `tools/list`. Must be valid JSON or it's treated as an empty catalog. |
| Comment / Detailed Log | optional | |

Advanced section:

| Field | What to put | Notes |
| --- | --- | --- |
| Health Check Path | e.g. `/health` (or empty) | When set, an unauthenticated `GET` to this path returns `200 {"status":"ok",...}` for liveness probes. Empty = disabled. Distinct from the MCP endpoint's `GET` (which is `405`). |
| Max Request Bytes | e.g. `1048576` (or `0`) | Bodies larger than this get `413`. `0` = no limit. Best-effort — see [BACKLOG.md](BACKLOG.md) caveat (the body is already read by the time it's checked). |
| Trusted Actor Header | e.g. `X-Authenticated-User` (or empty) | Name of an HTTP header carrying the authenticated end-user identity, set by a trusted upstream proxy/gateway. Read into `$mcp.actor` when no mTLS client cert is present. **Only set if the upstream is trusted to set it** and clients can't reach the connector directly to forge it; otherwise leave empty. With mTLS terminated at the connector, leave this empty — the client cert populates `$mcp.actor` directly. |

Security section (Authentication Mode, Bearer Token, Allowed Origins, Use SSL, Require Client Certificate) is now enforced as of Phase 3:

- **`authMode: bearer`** requires `Authorization: Bearer <bearerToken>` on every request (constant-time compared); missing/wrong token → `401`. If `bearerToken` is left blank while `authMode=bearer`, every request is rejected (fail closed, not open).

  > 💡 **Generate Token button.** Next to the Bearer Token field in the Config Editor is a **Generate Token** button. It creates a strong random 256-bit token, fills the field, copies it to your clipboard, and shows it once in a dialog — paste it straight into your MCP client config. (The field re-masks after you close the dialog, so copy it while it's shown.)

  > ⚠️ **Enter only the token value in the Bearer Token field — not `bearerToken=...`.** The field holds the secret itself. If you paste `bearerToken=test123` (the whole `name=value` pair, e.g. copied from a properties file or this doc), the configured token becomes the literal 19-character string `bearerToken=test123`, while clients send just `test123` — so every request gets a `401` that looks like the code is broken when it isn't. This actually happened during testing; the giveaway is the configured value being longer than what you typed. The field is also a TDI password field that doesn't always select-all on focus, so editing can *append* to the old value rather than replace it — clear it completely (select-all, delete to empty) before typing a new token.

- **`authMode: mtls`** does nothing extra in the connector's own Java logic — mutual TLS is enforced entirely by the inherited `HTTPServerConnector` SSL layer. Set **`Use SSL = true`** and **`Require Client Certificate = true`**; client-certificate verification then happens during the TLS handshake, before any connector code runs. See [§3a. Configuring mTLS](#3a-configuring-mtls) below — the keystore/truststore come from the **SDI server**, not from connector fields.
- **`authMode: none`** performs no authentication check at all.
- **Allowed Origins**: origin checking is OFF when this is empty (out-of-box, curl, non-browser clients). **Once you set an allowlist, a _present_ `Origin` header is validated strictly** — a request whose `Origin` isn't on the list gets `403`. A **missing** `Origin` is **allowed through** (it then relies on bearer/mTLS as the auth boundary). This is deliberate and is what DNS-rebinding protection actually requires: the threat is a browser page, and browsers *always* attach `Origin` on cross-origin requests and cannot forge or omit it (`Origin` is a [forbidden header name](https://fetch.spec.whatwg.org/#forbidden-header-name)) — so a rebinding attacker's request carries a non-matching `Origin` and is rejected, while legitimate non-browser MCP clients (curl, SDKs, Claude Code's `fetch()` transport) send no `Origin` and must not be locked out. Set an allowlist for any browser-facing or networked deployment; pair it with `bindAddress`, bearer auth, and TLS.

## 3a. Configuring mTLS

Mutual TLS (client-certificate authentication) is handled by the stock `HTTPServerConnector` SSL machinery that this connector inherits, **not** by any connector-specific keystore field. Key consequence: the server's TLS identity and the set of trusted client CAs come from the **SDI server's own keystore/truststore configuration**, configured once at the server level — there are deliberately no per-connector keystore-path fields on the form.

To enable it:

1. **Server TLS identity (keystore).** Ensure the SDI server has a server keystore configured (e.g. `api.keystore` / the server SSL settings in `etc/global.properties` / `solution.properties`, such as the bundled `testserver.jks`). This provides the server certificate presented to clients.
2. **Trust the client (truststore).** Import the client certificate — or the CA that issued it — into the SDI server's **truststore** so the server will accept that client cert. (Use the server's configured truststore; with the default test setup that may be the same JKS as the keystore.)
3. **Turn it on at the connector.** On the Connection tab set:
   - `Use SSL = true`
   - `Require Client Certificate = true`
   - (`Authentication Mode = mtls` is fine as a label, but it's these two booleans that actually enforce it. You can combine with `bearer` if you want both a client cert *and* a token.)
4. **Restart** the AssemblyLine / server so the listener rebinds as a TLS socket.
5. **Connect with a client cert.** The endpoint is now `https://`, and a request with no/untrusted client cert fails the TLS handshake (the connection never reaches the MCP layer). Example:

   ```bash
   curl --cert client.pem --key client.key --cacert server-ca.pem \
     https://127.0.0.1:8443/mcp \
     -H 'Content-Type: application/json' \
     -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
   ```

Notes:
- A missing or untrusted client certificate is rejected at the TLS layer with a handshake failure, not a JSON `401`/`403` — the connector code never sees the request, so there's no JSON-RPC error body to read.
- This path is verified correct by disassembly of `HTTPServerConnector` (the listening `SSLServerSocket` has `setNeedClientAuth(true)` applied, and our `getNextClient()` override accepts on that same socket, so the client-cert check is not bypassed) but has **not** yet been exercised end-to-end with real certs.

## 4. Data Flow

Add whatever AL logic should run per `tools/call`. The connector hands the AL a work Entry with these attributes already set:

- `$mcp.tool` — the tool name from the JSON-RPC `params.name`
- `$mcp.requestId` — the JSON-RPC request id
- `$mcp.protocolVersion` — `2025-11-25`
- `$mcp.arguments` — the raw JSON string of `params.arguments`
- Any top-level **scalar** argument is also flattened directly onto the Entry under its own name (e.g. `arguments: {"text": "hello"}` also sets a plain `text` attribute) — convenient for simple tools that don't want to re-parse JSON. Nested objects/arrays are only available via `$mcp.arguments`.
- `$mcp.actor` / `$mcp.actorCn` — the **verified** caller identity, when available: the mTLS client certificate's subject DN/CN, or the value of a configured trusted `actorHeader` (DN form in `$mcp.actor`, CN in `$mcp.actorCn`). Absent when neither mTLS nor `actorHeader` is in play. Use it for per-user authorization and audit in write flows; it cannot be forged via client arguments (the connector refuses client-supplied `$mcp.*` keys).

As of Phase 2, `tools/call` validates the tool name against `toolCatalog` **before** invoking the AL: if the name isn't present in the catalog (or is missing), the connector responds directly with a tool-level error (`isError: true`, no AL cycle spent) rather than handing it to the Data Flow.

**This makes the Tool Catalog field load-bearing, not just descriptive.** The `tdi.xml` default is `[]` (empty array) — if you never explicitly paste your tool definitions into the Connection tab's **Tool Catalog (JSON)** field, *every* `tools/call` will fail with "Unknown tool", even for a tool your Data Flow correctly implements. Before debugging Data Flow logic, check this field actually has content.

Have the AL branch on `$mcp.tool`, do its work, and set on the entry it hands to the next stage (the one that ends up calling the connector's `replyEntry`):

- `$mcp.result` — text to return as the tool's text content block
- `$mcp.structured` — (optional) a JSON string returned as `structuredContent`
- `$mcp.isError` — (optional) `"true"` to mark the tool result as an MCP-level error

If none of `$mcp.result`/`$mcp.structured`/`$mcp.isError` are set, the connector returns a fixed generic text result ("Tool completed but returned no result."). It deliberately does **not** serialize the work Entry, which could leak sensitive attributes your Data Flow loaded — always set `$mcp.result` (and/or `$mcp.structured`) explicitly.

`initialize`, `notifications/initialized`, and `tools/list` are answered directly by the connector — they never reach the AL's Data Flow.

## 5. Run and test

Start the AL. From a terminal:

```bash
# initialize
curl -s -X POST http://127.0.0.1:8443/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}'

# tools/list
curl -s -X POST http://127.0.0.1:8443/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'

# tools/call
curl -s -X POST http://127.0.0.1:8443/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"lookup_user","arguments":{"id":"123"}}}'
```

With `authMode = bearer`, add the token header (note: just the value, matching exactly what's in the Bearer Token field — see the warning in §3):

```bash
curl -s -X POST http://127.0.0.1:8443/mcp \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer test123' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
```

Or point the [MCP Inspector](https://github.com/modelcontextprotocol/inspector) at `http://127.0.0.1:<tcpPort><endpointPath>`.

### Full revalidation matrix

To run the whole acceptance matrix (SPEC.md §9) in one shot — happy path plus every documented error branch (`401`/`400`/`403`/`404`/`405`/`202`, unknown-tool `isError`) — use [`scripts/revalidate.sh`](../scripts/revalidate.sh). It prints pass/fail per case and exits non-zero if any fail, so it doubles as a pre-deploy gate.

```bash
TOKEN=<bearer-token> ORIGIN=https://good.example ./scripts/revalidate.sh
```

Configurable via environment: `BASE`, `ENDPOINT`, `HEALTH`, `TOKEN`, `ORIGIN`, `PROTO`, `USERID`. When `allowedOrigins` is set, `ORIGIN` must be one of the allowed values — the happy-path cases send it, so a wrong value `403`s them (a *missing* Origin is allowed; see §4 and case 10b). Pass `--log` to tail the server log after the run (`LOGFILE` overrides the default path); `-h` prints usage.

### Troubleshooting

- **Every request 401s even with the right-looking token** → the Bearer Token field almost certainly contains the wrong value. Most common cause: it holds `bearerToken=test123` (the whole pair) instead of `test123` (see §3). Clear the field to empty and retype just the token. Remember a TDI password field can append rather than replace on edit.
- **`tools/call` always returns "Unknown tool"** → the Tool Catalog field is empty or doesn't list that tool name (see §4).

## 6. Connecting Claude as the MCP client

Once curl/MCP Inspector round-trips work, point a real MCP client at the same endpoint. The connector speaks Streamable HTTP at `http://<bindAddress>:<tcpPort><endpointPath>` (e.g. `http://127.0.0.1:8443/mcp`).

### Claude Code (CLI)

```bash
# no auth
claude mcp add --transport http vdi-mcp http://127.0.0.1:8443/mcp

# with bearer auth (authMode=bearer)
claude mcp add --transport http vdi-mcp http://127.0.0.1:8443/mcp \
  --header "Authorization: Bearer test123"
```

Then in a Claude Code session the configured tools (e.g. `echo`, `server_time`) appear and can be invoked. Use `/mcp` in Claude Code to see connection status.

### Claude Desktop

Add a custom connector pointing at the endpoint URL (Settings → Connectors → Add custom connector). Bearer-token/header support in the desktop custom-connector UI varies by version; if it can't attach an `Authorization` header, test with `authMode=none` first to confirm connectivity, then layer auth back on.

### Readiness checklist before the live test

- Server running and the AssemblyLine started in Server mode.
- `Tool Catalog` populated (else every tool call is "Unknown tool").
- If `authMode=bearer`, the client is configured with the exact token value (just the value — see §3).
- The URL path the client uses matches `endpointPath` exactly (else `404`).
- Reaching the host on a non-localhost interface? `bindAddress` must allow it, and you need TLS + auth (don't expose plaintext bearer off localhost).
- **`Allowed Origins`:** a *present* `Origin` not on the list gets `403`; a *missing* `Origin` is allowed through (§4). Claude Code's `fetch()` transport and other non-browser clients send **no** usable `Origin`, so an allowlist does **not** block them — leave it set or empty as your deployment needs. A real MCP client failing here is almost never `Origin` (it sends none); check the token and `endpointPath` first. If a browser-based client *is* getting `403`, read the AL log for the `Origin` it actually sent and add that exact value.

> Note: a `tools/call` from a real client needs `serverReply=true` on the connector config (set by default in this connector's `tdi.xml`). Without it, `initialize`/`tools/list` work but tool calls hang with no response — see [SPEC.md](SPEC.md) §1d. If you cloned/edited the connector config and tool calls hang, verify that parameter is present.

### Verify from Claude (end-to-end)

The curl matrix ([`scripts/revalidate.sh`](../scripts/revalidate.sh)) proves the transport and security gates, but the path that actually matters is an **AI client discovering and calling the tools**. Do this once against a running `MCPServer_LDAP` with your real catalog — it's the [§9 acceptance](SPEC.md#9-acceptance--validation) criterion.

1. **Server ready.** `curl -s http://127.0.0.1:8443/health` → `{"status":"ok"}`, and a `tools/list` (see §5) shows your tool names — not `[]`.
2. **Register it in Claude Code with the _current_ token.** The header token must equal the connector's `bearerToken` exactly; if you regenerated it, re-add:
   ```bash
   claude mcp remove vdi 2>/dev/null
   claude mcp add --transport http vdi http://127.0.0.1:8443/mcp \
     --header "Authorization: Bearer <current-token>"
   ```
   Do **not** add `--header "Origin: ..."` — `Origin` is a forbidden header for `fetch()` clients and is silently dropped. With this connector a missing `Origin` is allowed, so `allowedOrigins` can stay set.
3. **Start a fresh Claude Code session** — tools register at startup. `/mcp` should show `vdi` connected, and the tools (`lookup_user`, `get_user_groups`, …) should be listed.
4. **Ask in natural language**, e.g. *"Look up alice and list her groups."* Claude should call `lookup_user` and `get_user_groups` and answer from the returned `structuredContent`.
5. **Confirm in the AL log** the incoming `tools/call`, the branch taken, and the reply — proof the connector served it, not a model guess.

Troubleshooting:
- **`/mcp` shows an auth failure / 401** → the configured header token doesn't match the connector's `bearerToken` (common right after a regenerate). Re-add with the current value.
- **Tools don't appear** → the session wasn't restarted, or the catalog is empty (re-run the step-1 `tools/list`).
- **A tool call hangs or returns "no result"** → `serverReply` / the reply Output map — see the note above and [GOTCHAS.md](GOTCHAS.md) §3 and §9.

### Interop points to watch (v1 design choices, per [SPEC.md](SPEC.md) §2)

These are deliberate v1 limitations that a strict client *might* care about — worth checking against during the first real Claude connection:

- **No SSE / streaming.** `GET` on the endpoint returns `405`; the connector only does single `application/json` responses, never `text/event-stream`. A spec-compliant client must treat server→client streaming as optional and not require it.
- **Stateless — no `Mcp-Session-Id`.** The server never assigns a session id, so the client shouldn't send one back. Each request is independent.
- **`notifications/initialized` returns `202`** with no body, as required.

If Claude connects, lists tools, and successfully calls one end-to-end, that satisfies the §9 acceptance criteria — the real goal of the project.

## Protocol version negotiation

The connector supports protocol versions `2025-11-25` (preferred), `2025-06-18`, and `2025-03-26`. On `initialize`, if the client requests one of these it's echoed back; otherwise the connector advertises `2025-11-25`. On later requests the `MCP-Protocol-Version` header is accepted if it's any supported version (absent header is also allowed); anything else → `400`. This keeps real clients (e.g. Claude) working even if they negotiate an older supported version rather than only the newest.

## Bind address enforcement note

The inherited `HTTPServerConnector` always binds its listening socket to all interfaces (`0.0.0.0`) — it has no per-address bind option. `bindAddress` is therefore enforced *after* a connection is accepted: if the connection didn't arrive on the configured local address, it's closed and skipped. This effectively restricts which interface the service answers on, but the socket itself still listens broadly, so it's a guard rather than a true bind. For hard network isolation, also use OS/firewall rules.

## Known limitations (current state, end of Phase 3)

Enforced: `405` (non-`POST`), `404` (wrong `endpointPath`), `403` (disallowed `Origin`), `401` (bearer), `400` (unsupported `MCP-Protocol-Version`), and `bindAddress` (post-accept, see above). Deliberately **not** implemented, with rationale:
- **Request size limit** — the inherited HTTP parser reads the full request body before the connector sees the entry, so a pre-read byte cap would require overriding the parser itself; deferred.
- **Request/idle timeout** — partially covered by the inherited `idleConnectionTimeout` parameter.
- **Max concurrent requests** — governed by the AssemblyLine pool size, a deployment/AL-config concern rather than connector code.

Don't expose this beyond localhost without `useSSL`+`needClientAuth` (mTLS) or `authMode=bearer` configured.
