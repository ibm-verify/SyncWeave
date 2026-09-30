# MCP Server Connector for IBM VDI 10

A custom IBM Verify Directory Integrator (VDI 10) **Server-mode connector** that turns an AssemblyLine into an **MCP (Model Context Protocol) server** over Streamable HTTP. When the AL runs, it *is* an MCP server: an inbound `tools/call` becomes the AL's work Entry, and the AL's reply Entry becomes the tool result.

**Validation status:** proven three ways — a curl transport/security matrix (14/14, [`scripts/revalidate.sh`](scripts/revalidate.sh)), a **live Claude Code MCP client** answering natural-language questions end-to-end, and a **clean-clone run** of the bundled example (`git clone` → `docker compose up` → set two secrets → `ibmdisrv`), which reproduced the full working demo — all five tools returning correct directory data plus the security matrix. See the runnable example below.

## Runnable example — UC1 Identity Service Desk

A complete, reproducible demo ships in the repo: a VDI solution ([`examples/`](examples)) exposing five read-only identity tools (`lookup_user`, `get_user_groups`, `list_group_members`, `search_users`, `get_user_overview`), backed by a containerized OpenLDAP ([`docker/`](docker)) seeded with users and groups. The example is committed **without secrets** — you set `ldapAdminPwd` and a generated `bearerToken` locally. Full walkthrough in [docs/USE-CASES.md §3](docs/USE-CASES.md); the design rationale and enterprise use cases are in that doc's other sections. **Clean-clone verified:** a fresh checkout plus the two secret fill-ins runs green end-to-end.

## How it works

- Subclasses the stock `HTTPServerConnector` to reuse its socket/TLS/HTTP framing, adding a JSON-RPC 2.0 / MCP message layer on top.
- `initialize`, `notifications/initialized`, and `tools/list` are answered directly by the connector. `tools/call` is mapped to a work Entry (`$mcp.tool`, `$mcp.arguments`, flattened scalar args, …); the AL's Data Flow does the work and sets `$mcp.result`/`$mcp.structured`/`$mcp.isError`, which the connector translates back into the MCP tool result.
- Stateless JSON responses (no SSE/sessions in v1). Transport security: bearer token, optional mTLS, `Origin` allow-list, method/path/protocol-version enforcement.

## Documentation

- **[docs/SPEC.md](docs/SPEC.md)** — full design, locked decisions, phased plan, and the narrative of every phase including the bugs found and fixed live.
- **[docs/CONFIGURE.md](docs/CONFIGURE.md)** — step-by-step setup: connector config, the three attribute contracts, security, connecting Claude, troubleshooting.
- **[docs/GOTCHAS.md](docs/GOTCHAS.md)** — condensed index of the non-obvious VDI platform behaviors that shaped this connector. **Read this first if you're extending or porting it.**
- **[docs/BACKLOG.md](docs/BACKLOG.md)** — what's done in the v1 tail, what's deferred, and design notes for v2 (SSE, sessions).

## Build

Requires a local VDI 10 install (its `jars/common`, `jars/connectors`, and `jars/3rdparty` jars are used compile-only — nothing is repackaged).

```
mvn -Dvdi.home=/path/to/ISVDI package
```

## Unit tests

```
mvn -Dvdi.home=/path/to/ISVDI test
```

JUnit 5 tests cover the JSON-RPC/MCP layer and transport gates (initialize/version negotiation, tools/list, tools/call work-Entry mapping, reserved `$mcp.*` argument protection, actor header, reply mapping and no-leak behavior, bearer/Origin/path/method/size/protocol-version checks, health probe). They run without a socket or running VDI; the platform jars are needed on the test classpath only (see `pom.xml`).

## Deploy

Copy the built jar (`target/mcp-server-connector.jar`) to `VDI_install_dir/jars/connectors/`, restart the Config Editor / server, and the `MCPServerConnector` connector appears under Connectors. See [docs/CONFIGURE.md](docs/CONFIGURE.md) to build the AssemblyLine and connect a client.

## Manual testing

Test with `curl` before pointing any MCP client at it — it isolates connector behavior from client-specific transport quirks.

```bash
# tools/list (no auth)
curl -s -X POST http://127.0.0.1:8443/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'

# tools/call
curl -s -X POST http://127.0.0.1:8443/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"<tool>","arguments":{}}}'
```

If `authMode=bearer`, add `-H 'Authorization: Bearer <token>'` (the raw token value only, not `bearerToken=<token>`).

**Origin handling** (with `allowedOrigins` set): a **present** `Origin` not on the list gets `403 {"error":"Origin not allowed."}`; a **missing** `Origin` — which curl and non-browser MCP clients send by default — is **allowed through** (see [docs/GOTCHAS.md §7](docs/GOTCHAS.md)). So the commands above still work even with an allowlist configured. This is deliberate: the DNS-rebinding threat is a browser, which *always* attaches `Origin` and can't omit it, so a strict present-Origin check defends against it without locking out the SDK/curl clients that send none. To exercise the reject path, send a **disallowed** `Origin`:

```bash
curl -s -X POST http://127.0.0.1:8443/mcp \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer <token>' \
  -H 'Origin: https://evil.example' \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/list"}'     # -> 403
```

For local/dev, leave `allowedOrigins` empty (check off entirely). A real MCP client hitting `403` almost never means the client is broken over `Origin` — its `fetch()` transport sends none, which passes — so check the token and `endpointPath` first.

Full curl walkthrough (`initialize`, troubleshooting table) is in [docs/CONFIGURE.md §5](docs/CONFIGURE.md). [MCP Inspector](https://github.com/modelcontextprotocol/inspector) works too — point it at the same URL.

## Quick connect (Claude Code)

```
claude mcp add --transport http vdi-mcp http://127.0.0.1:8443/mcp \
  --header "Authorization: Bearer <your-token>"
```

Start a fresh Claude Code session (tools register at session start), then ask it to use one of your configured tools. Note: `--header "Origin: ..."` does **not** work here — `Origin` is a forbidden header name for `fetch()`-based clients and gets silently dropped; if `allowedOrigins` is set, it must match whatever `Origin` Claude Code actually sends on its own, not a value you inject via `--header`.

## License

[Apache-2.0](../../LICENSE) — provided "as is", without warranty of any kind. IBM Security Directory Integrator and IBM Verify Identity Governance are trademarks of IBM; this is an independent connector, not an IBM product.
