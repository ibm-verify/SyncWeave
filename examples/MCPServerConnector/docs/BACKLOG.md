# Backlog & v2 design notes

Status of work beyond the v1 acceptance (which is complete — see [SPEC.md](SPEC.md) §9). Items are grouped by effort/risk.

## Done in the v1 tail

- **Health endpoint** — `healthPath` config param; a `GET` on that path returns `200 {"status":"ok",...}` unauthenticated, distinct from the MCP endpoint's `405` on GET. Empty = disabled.
- **Request size limit** — `maxRequestBytes` config param; bodies over the limit get `413`. Best-effort (see caveat below). `0` = no limit.

### Caveat on the request size limit

The inherited `HTTPServerConnector` HTTP parser reads the **entire** request body into the entry *before* `getNextEntry()` runs. So `maxRequestBytes` guards *processing* (rejects the request, avoids spending an AL cycle, signals the client) but does **not** prevent the oversized body from being read into memory first. A true pre-read cap would require overriding the parser's body-read to enforce a byte ceiling mid-stream — deferred as not worth the invasiveness for v1. If you deploy off-localhost and need real ingress protection, put a reverse proxy in front with its own body-size limit.

## Deferred — small, do when needed

- **Request/idle timeout** — partially covered by the inherited `idleConnectionTimeout` param. A per-request processing timeout (cap how long the AL Data Flow may run before the connector returns a timeout error) isn't implemented; it would need a watchdog around the AL cycle, which the connector doesn't directly control.
- **Max concurrent requests** — governed by the AssemblyLine pool size (a deployment/AL-config setting), not connector code. If you need a hard per-connector cap, a semaphore in `getNextClient()` would do it, but the AL pool is the idiomatic lever.

## v2 — larger, architectural (not started; design notes only)

These conflict with v1's core model (one inbound connection → one AL cycle → one reply, fully stateless). Implement only when a concrete need appears; each is its own focused effort with its own live-testing pass.

### SSE / streaming (Streamable HTTP server→client stream)

**Goal:** let the server push multiple `text/event-stream` messages over one response — progress updates during a long tool call, or server-initiated requests. Also what a strict client expects when it opens the `GET` we currently answer with `405`.

**Why it's hard here:** v1's whole shape is request→one-JSON-response. SSE means holding the connection open and writing chunks *while* the AL runs, which fights VDI's "one connection drives one AL cycle" model (see [GOTCHAS.md](GOTCHAS.md) #1). The base `HTTPServerConnector` does support chunked output (`putEntry`/`setAutoChunking`/`HTTPChunkedWriter`), so the transport primitive exists — but wiring AL progress events into an event stream, and managing the open connection's lifecycle against the AL cycle, is the real work.

**Sketch:** answer `GET` on the endpoint by switching the connection to chunked `text/event-stream` and emitting keep-alives; for `tools/call`, optionally stream progress notifications before the final result. Would likely need the AL to publish progress via a side channel (system queue / a hook the Data Flow calls) that the connector drains into the stream. Treat as a design spike first.

### Sessions (`Mcp-Session-Id`)

**Goal:** per-client state across requests — auth context, multi-step workflows, pagination cursors.

**Why it's hard here:** v1 is deliberately stateless (each request independent), which is simpler and safer. Sessions need: assign an id on `initialize` (return it as the `Mcp-Session-Id` response header), require it on subsequent requests, a session store keyed by id, and lifecycle (creation/expiry/eviction). Because each connection is a fresh per-connection instance ([GOTCHAS.md](GOTCHAS.md) #1), the store must live on the shared listening connector or in external storage (VDI system store), not in per-connection fields.

**Sketch:** add a `sessionStore` (concurrent map id→state, or back it with the VDI system store for durability) owned by the listening instance and passed to per-connection instances in `getNextClient()`. Emit `Mcp-Session-Id` from `initialize`; validate it on later requests (404/400 on unknown/expired). Expose the session id to the AL as a `$mcp.session` attribute so Data Flow logic can scope state. Forward-compatible with where MCP is heading, but only worth it for genuinely stateful tools.

## Other open ideas

- **Vault / external secret sourcing** for `bearerToken` and keystore passwords (currently config params; auto-decrypt confirmed working — [GOTCHAS.md](GOTCHAS.md) #4). Natural follow-on given existing VDI patterns.
- **Per-tool AssemblyLine routing** — v1 uses one generic AL branching on `$mcp.tool`. An alternative is mapping each tool to a distinct AL/flow; documented as a future option in [SPEC.md](SPEC.md) §10.
- **`notifications/tools/list_changed`** and dynamic catalog reload — currently the tool catalog is static config read per request; a change-notification path would let clients refresh without reconnecting.
