# Platform gotchas & non-obvious findings

A maintainer's reference to the IBM VDI 10 platform behaviors that shaped this connector — each one cost real debugging to find. If you're extending or porting this connector, read this first. Full narrative is in [SPEC.md](SPEC.md) §1b–§1d; this is the condensed index.

## 1. `getNextClient()` hardcodes the base class — you must override it

`HTTPServerConnector.getNextClient()` constructs `new HTTPServerConnector()` (literally, confirmed by `javap -c` — not `this.getClass().newInstance()`) for the per-connection object that actually runs `getNextEntry()`/`replyEntry()`. So a plain subclass's overrides **never run for real traffic** — the framework processes every connection on a base-class instance.

**What we do:** override `getNextClient()` to replicate the base accept/setup sequence but construct `new McpServerConnector()`. Everything in that sequence uses public inherited methods except grabbing the listening `mServerSocket` (private, no accessor), which needs one reflective field read. See `McpServerConnector.getNextClient()`.

**Symptom if broken:** your overrides silently don't execute; the AL sees the raw HTTP entry (`http.*`/`tcp.*`), none of your log lines fire.

## 2. `http.body` is a `byte[]`, not a String — use `http.bodyAsString`

`Entry.getString("http.body")` returns `Object.toString()` of a `byte[]` (e.g. `[B@65166f15`), not the request body. The HTTP parser exposes a decoded String copy under **`http.bodyAsString`**.

**Symptom if broken:** JSON parse error like `Expecting '{' ... obtained token: '['` (the `[` is from `[B@...`).

## 3. `serverReply=true` is required for the reply-at-end-of-cycle path

A Server-mode connector has two reply mechanisms:
- **Direct reply** inside `getNextEntry()` (call `super.replyEntry()`, return `null`) — always works. We use it for `initialize`/`tools/list`/errors.
- **End-of-cycle reply**: when `getNextEntry()` returns a work Entry, the AL only calls `replyEntry()` afterward if the connector config's `getReplyRequired()` is true — which reads the **`serverReply`** boolean param (default `false`, confirmed via `ConnectorConfigImpl` disassembly). We use this for `tools/call`.

Our `tdi.xml` sets `serverReply=true`, so an AL author never sets it (and can't — it isn't on the CE form). It won't appear in the solution XML either: the CE omits params that equal the inherited default, so `grep serverReply` on a saved config returns nothing even though it's in force. Absence = "matches default," not "off."

**Correction (cost hours to find):** the entry handed to `replyEntry()` is the connection's **`conn`** object, and it is **empty** unless the connection's **Output attribute map** populates it from `work`. There is no automatic passthrough. You must map `$mcp.result`, `$mcp.structured`, and `$mcp.isError` from `work` → `conn` in the MCPServerConnection's Output map — see §9.

**Symptom if broken:** `tools/call` returns `200` with `content:"Tool completed but returned no result."` and no `structuredContent`, even though the AL log shows `$mcp.result` set on `work`. (If `serverReply` were genuinely false, `tools/call` would instead hang with no response — but that's unreachable from the CE form.)

## 4. `PASSWORD`-syntax params auto-decrypt at runtime

A form field with `syntax=PASSWORD` is stored `encrypted="true"` in the config XML (a ~340-char ciphertext blob). At runtime the engine decrypts it transparently — `getParam("bearerToken")` returns **plaintext**. No `SecurityCrypto`/`CryptoFactory` call needed.

**Operational note (not a code bug):** entering `name=value` (e.g. `bearerToken=test123`) into a password field stores the whole 19-char string as the secret. The tell is the configured value being longer than what you typed. TDI password fields can also *append* rather than replace on edit — clear to empty first.

## 5. The listener binds `0.0.0.0` — `bindAddress` is enforced post-accept

`HTTPServerConnector` creates its `ServerSocket` with `createServerSocket(port, backlog)` — bound to all interfaces, no per-address option. We enforce `bindAddress` by checking `socket.getLocalAddress()` after `accept()` and rejecting (close + `RetryEntryException` to keep listening) connections that didn't arrive on the configured address. It's a guard, not a true bind — pair with firewall rules for hard isolation.

**Match loopback by address, not string — or real MCP clients get `ECONNRESET`.** A naive `bindAddress.equals(local.getHostAddress())` compares `"127.0.0.1"` against the peer's local address *as text*. But `localhost` resolves to **IPv6 `::1` first** on macOS and under Node/undici (Happy Eyeballs), so a real client arrives on `::1` (or IPv4-mapped `::ffff:127.0.0.1`), the string match fails, and the guard **closes the socket before any request is read** → the client sees `ECONNRESET` and never connects. curl to the literal `127.0.0.1` keeps working, so it masks the bug completely — the classic "curl passes, real client can't connect." The guard therefore normalizes: a **loopback** `bindAddress` accepts **any** loopback peer (all of `127.0.0.1`/`::1`/`::ffff:127.0.0.1` are localhost); a non-loopback `bindAddress` still requires an exact resolved-address match so NIC isolation holds. See `isLocalAddressAllowed()`.

**Symptom if broken:** `claude mcp` (or any Node/browser client) shows `ECONNRESET` / "socket closed unexpectedly"; the AL log shows the connection accepted and closed with **no** `http.method` parsed; `curl http://127.0.0.1:<port>` works but `curl http://[::1]:<port>` / `http://localhost:<port>` returns `000` (curl exit 56).

## 6. mTLS uses the *server's* keystore, not connector params

`useSSL`/`needClientAuth` drive TLS via the inherited SSL layer, whose socket factory (`getRSInterface().getServerSocketFactory(true)`) sources keystore + truststore from the **SDI server's** global config — there are deliberately no per-connector keystore fields. `setNeedClientAuth(true)` is applied to the listening `SSLServerSocket`, so our `getNextClient()` accept doesn't bypass client-cert verification. A bad/missing client cert fails at the TLS handshake, before any connector code runs (so no JSON error body — just a handshake failure).

## 8. The Config Editor is Eclipse/SWT — form scripts must use SWT, not AWT/Swing

The CE (`ce/eclipsece`, an Eclipse RCP app — `org.eclipse.swt.cocoa.macosx` on macOS) runs **SWT**, not Swing/AWT. This bites form-script (`<parameter name="formscript">`) code two ways:
- A raw AWT modal dialog (`javax.swing.JOptionPane.showInputDialog` with no parent) **freezes the CE on macOS** — AWT modals don't mix with the SWT/Cocoa main thread. Use `form.alert(...)` (the CE's own parented SWT dialog) for popups.
- The AWT clipboard (`java.awt.Toolkit.getSystemClipboard()`) doesn't reliably reach the OS pasteboard from SWT. Use the **SWT clipboard**: `new org.eclipse.swt.dnd.Clipboard(Display.getCurrent())` + `setContents([text], [TextTransfer.getInstance()])`, then `dispose()`. Non-modal, so it can't hang the CE.

The available form-script API (the `form` object) includes `getConfigValue`/`setConfig`/`updateControl`, `alert`, `translate`, `chooseFromList`, `setWaitCursor`/`setNormalCursor`. Java is reachable via `Packages.<fqcn>`; `new Packages...()` constructors work. See the "Generate Token" button (`generateBearerToken` in `tdi.xml`'s formscript) for a worked example.

## 7. Real MCP clients send an `Origin` header

Browsers always attach `Origin` on cross-origin requests and **cannot forge or omit it** (`Origin` is a [forbidden header name](https://fetch.spec.whatwg.org/#forbidden-header-name)). Non-browser MCP clients — curl, MCP Inspector, the MCP SDKs, and Claude Code's `fetch()`-based transport — send **no** usable `Origin` (and can't be made to via `--header`, which is silently dropped for forbidden headers).

The connector uses this asymmetry: with `allowedOrigins` non-empty, a **present** `Origin` not on the list gets `403` (this is the DNS-rebinding defense — the attacker is a browser page whose real `Origin` won't match), while a **missing** `Origin` is **allowed through** (bearer/mTLS is the boundary for those callers). See [CONFIGURE.md §4](CONFIGURE.md). Consequences:

- Requiring `Origin` would lock out exactly the legitimate non-browser clients while adding nothing against the browser attacker — so don't.
- A `403` from a real client means its `Origin` isn't on your list, **not** that the client is broken. Read the AL log for the actual `Origin` value it sent and add that; don't reach for a code change.
- For local/dev testing, leave `allowedOrigins` empty (check OFF).

---

# Building an AssemblyLine with this connector

The items above are connector-internals for maintainers. These are **AL-authoring** gotchas — hit while building the read-only "Identity Service Desk" AL (see [USE-CASES.md](USE-CASES.md) §3) against OpenLDAP, all in the Config Editor. Each cost real debugging.

## 9. The reply entry is `conn`, built by the connection's Output map — populate it

The Data Flow only ever writes `work`. The connector replies from **`conn`** (§3). Nothing bridges the two automatically. On the **MCPServerConnection**'s **Output** attribute map, add items copying the reply attributes from `work`:

| conn attribute | Advanced mapping |
|---|---|
| `$mcp.result` | `ret.value = work.getString("$mcp.result");` |
| `$mcp.structured` | `ret.value = work.getString("$mcp.structured");` |
| `$mcp.isError` | `ret.value = work.getString("$mcp.isError");` |

**Symptom if broken:** every `tools/call` → `200` "Tool completed but returned no result", no `structuredContent`. The AL log's dump shows `The 'conn' object` empty and `The 'work' object` holding your values.

## 10. Branch routing: empty condition matches everything; ELSE-IF order matters; names are case-sensitive

- A branch with an **empty condition** is always-true. As an **ELSE-IF**, it **swallows every branch below it** — those are never evaluated. Give each tool branch an explicit `$mcp.tool equals <toolName>`.
- Connector-flattened argument attributes are the **exact JSON key**, case-sensitive: a `userId` property is `$userId`, never `$userid`. A wrong-case Link Criteria value silently matches nothing.

**Symptom if broken:** a tool runs the *wrong* branch's logic (e.g. `list_group_members` executing the groups Iterator), or a Lookup matches nothing because `$userid` resolved empty.

## 11. Tool Catalog (JSON) field: invalid JSON is silently treated as empty, and the field appends on edit

`tools/call` validates the tool name against the catalog **before** the AL; an empty/`[]` catalog makes *every* call "Unknown tool". The catalog textarea is a TDI field that **appends rather than replaces** on paste — pasting a second time yields `] [` (two arrays concatenated) which is invalid JSON → parsed as empty. Always **select-all → delete to empty → paste once**, and confirm `tools/list` returns your names.

**Symptom if broken:** `tools/list` returns `[]`; every `tools/call` → `isError` "Unknown tool: …".

## 12. `$attr` is *not* substituted in static connector params (e.g. `ldapSearchFilter`) — set it in a hook

TDI substitutes `$attr` in a connector parameter at **connector init from the work entry available then** — which for a feed/loop connector is effectively empty, so `$userId` reaches LDAP **literally** and matches nothing. **Link Criteria** *does* substitute (evaluated at lookup time), which is why the single-entry Lookups worked. For an **Iterator** whose filter needs a per-request value, set it in a **Before Initialize** hook:

```javascript
if (work != null) {                                   // see the two caveats below
  var uid = work.getString("userId");
  thisConnector.connector.setParam("ldapSearchFilter",
    "(&(objectClass=groupOfNames)(member=uid=" + uid + ",ou=People,dc=example,dc=com))");
}
```

Three things that each silently break this:

1. **Use `thisConnector.connector.setParam(name, val)`.** `thisConnector.getConfiguration().setParameter(...)` writes a config object the search path does **not** read — the hook runs, no error, and the search uses the old filter. `.connector.setParam` writes the value `selectEntries` actually consults.
2. **The connector needs a *local* `ldapSearchFilter`** for the setter to overwrite. If the field is empty / `InheritFrom [parent]`, set a placeholder (e.g. `(objectClass=inetOrgPerson)`) in the Connection tab so a local param exists — otherwise the set target and the read target differ and you get a match-all (or the parent's) filter.
3. **Guard with `if (work != null)`.** A Before-Initialize hook can fire during **AL-startup `InitConnectors`** (eager init), where there is no work entry yet — `work.getString(...)` then throws `'work' is null`, which fails `InitConnectors` and **takes the whole AL down** (listener stays up, every request hangs). The guard makes startup a no-op and the per-request call real.

Also **balance the filter string** — a stray quote/paren gives `InvalidSearchFilterException: Unbalanced parenthesis` (search fails, cycle aborts, `HTTP 000`) or a `Lexical error … <EOF>` that fails init. Build it as `"(&(objectClass=…)(member=uid=" + uid + "," + base + "))"` and count the parens.

**This is also what makes connector pooling safe.** With `serverConnector` + `LDAPConnector` pooling on, a pooled connector is reused across requests and its init is skipped (`CTGDIS1914I Not initializing a re-used Connector`), but the **Before-Initialize hook still fires each request** and `.connector.setParam` re-writes the filter on the live instance — so pooling keeps the per-request substitution (verified: `get_user_groups` for alice/bob/erin returns distinct groups under pooling). The **old** `getConfiguration().setParameter` is the trap here: it no-ops, so the first request's filter sticks and every later call returns the **first** caller's data — the reason reuse had to be disabled before the setter was fixed. Pool freely **once you're on `.connector.setParam`**; never with the `getConfiguration` form.

**Symptom if broken:** AL log shows `search filter '(...member=uid=$userId,...)'` verbatim, or the hook-set filter never appears at all (no `CTGDJQ017I` line); `Loop Cycles:0`; or (under pooling with the wrong setter) a second call returns the first call's results.

## 13. "List" tools: nothing auto-collects — accumulate per cycle, and scope the Input map

An Iterator hands you **one** entry per cycle. To return a list, append each cycle's value to a multi-valued work attribute yourself:

```javascript
work.addAttributeValue("groups", work.getString("cn"));   // NOT setAttribute — that overwrites
```

Restrict the Iterator's **Input map to only the attribute you accumulate** (e.g. `cn`), not `*`. Otherwise each cycle's other attributes (`objectClass`, `member`, the entry `$dn`) linger on `work` and the reply reflects the **last** group's — leaking unrelated data. Multi-valued work attrs serialize to JSON arrays automatically; a single value stays scalar (special-case if you need always-array).

## 14. LDAP Lookup with no match throws and fails the AL — add an On No Match hook

A Lookup that finds nothing raises an error that kills the cycle — the client gets a dropped connection (curl exit 52 / `HTTP 000`), not a clean answer. On the Lookup connector's **On No Match** hook, set a normal result:

```javascript
work.setAttribute("$mcp.result", "No match for '" + work.getString("userId") + "'.");
work.setAttribute("$mcp.structured", "{\"found\":false}");
```

## 15. IBM JScript (`ibmjs`) can't read `.length` on Java arrays or `StringBuilder`

The CE/engine script engine is IBM JScript, not Rhino/Nashorn. `someJavaArray.length` and `stringBuilder.length()` both throw `Java Bean property 'length' does not have a read method` — the interpreter resolves `.length` as a bean property before any call. Use `java.lang.reflect.Array.getLength(arr)` for array size and build strings with plain **JS string concatenation**, not `StringBuilder`. Ordinary method calls (`entry.getAttributeNames()`, `attr.size()`, `attr.getValue(i)`) are fine — only `length` collides.

**Symptom if broken:** `Script interpreter error, line=N: Java Bean property 'length' does not have a read method`.

## 16. Composite tools: a loop clobbers profile attributes that share a name

A tool that does a profile Lookup **and** a groups Iterator in one branch (e.g. `get_user_overview`) shares one `work` entry across both. If both map the same attribute name — the user's `cn` from the Lookup and each group's `cn` from the Iterator — the loop **overwrites** the profile value every cycle, and the profile's is lost.

Fix: give the loop its **own scratch names**, never the profile's. Map the group's `cn` to `gcn`, accumulate from `gcn`, and remove `gcn` after the loop:

```javascript
// Iterator Input map:  Name gcn  <-  Simple cn        (group cn lands on work.gcn, not work.cn)
work.addAttributeValue("groups", work.getString("gcn"));   // accumulate reads the scratch...
// after the loop:
work.removeAttribute("gcn");                                // ...and clean the scratch, NOT cn
```

Two ways this bites while wiring it: the accumulate still reading the old name (`cn`) so `groups` never builds, and the cleanup deleting the **wrong** attribute (`cn` instead of `gcn`) so the user's name vanishes while the scratch leaks. Rule of thumb: the loop touches only names nothing else uses, and cleans up exactly those.

**Symptom if broken:** an attribute the profile set is missing from the result, or shows the *last* iterated value; a scratch attribute (`gcn`) leaks into `structuredContent`.

## 17. One request per connection — send `Connection: close` or real clients `ECONNRESET`

The inherited `HTTPServerConnector` server model is **one request per TCP connection**: `getNextClient()` accepts a socket, a per-connection instance runs a single `getNextEntry()`/`replyEntry()` cycle, and the socket is closed (`CTGDJP314I Client connection closed`). But the response is `HTTP/1.1 200 OK` with a `Content-Length` and — by default — **no `Connection` header**, which under HTTP/1.1 means **keep-alive**. So the client is told the connection is reusable, then the server closes it anyway.

A client that honors keep-alive and pipelines its messages on one connection — which the **MCP SDK's `undici`/`fetch` transport does** for the `initialize` → `notifications/initialized` → `tools/list` handshake — sends its second message onto the just-closed socket → **`ECONNRESET`**, and the handshake fails. **curl hides this**: on connection reuse it silently retries the failed request on a fresh socket, so single-request curl and even `curl --next` "work", while the real AI client cannot connect. (Same family of trap as §3 and §5 — curl-green, client-broken.)

Fix: set **`Connection: close`** on every response (`reply.setAttribute("http.Connection", "close")` in the reply builders — the HTTP parser emits `http.<Name>` attributes as response headers). The client then opens a fresh connection per message, which is exactly what MCP's stateless Streamable HTTP transport is designed for. Supporting true keep-alive instead would mean overriding the connection loop to read multiple requests off one socket and manage HTTP/1.1 persistence yourself — large, against the framework, and unnecessary for MCP's message rate.

**Symptom if broken:** `claude mcp` / any `undici`/`fetch` client reports `ECONNRESET` "socket closed unexpectedly" during connect; `curl` single requests pass but `curl -v --next … --next …` shows `Re-using existing connection` then `Recv failure: Connection reset by peer` on the second request.
