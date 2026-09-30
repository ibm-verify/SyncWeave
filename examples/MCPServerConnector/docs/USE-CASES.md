# MCP Server Connector — Enterprise Use Cases

How the IBM Security Directory Integrator (SDI) **MCP Server Connector** is applied in a real enterprise identity environment. This connector turns an SDI AssemblyLine (AL) into a Model Context Protocol (MCP) server, so an AI client such as Claude can call governed "tools" that are implemented as SDI integration logic.

> **Audience.** This document serves two readers:
> - **Enterprise / solution architects** evaluating where the connector fits — read the *Scenario*, *Actors & flow*, and *Value* of each use case, plus *Cross-cutting considerations*.
> - **IAM engineers** who will build the AssemblyLines — read the *AssemblyLine design*, *Tool catalog*, and *Request → Entry → reply mapping* of each use case.
>
> Throughout, **IBM Verify Identity Governance (IVIG)** is used as the generic identity-governance platform; substitute your own where relevant. See [SPEC.md](SPEC.md) for the connector design and [CONFIGURE.md](CONFIGURE.md) for setup.

---

## 1. Overview

SDI is already the integration backbone in many enterprises — it federates Active Directory / LDAP, HR systems, databases, mainframe security (RACF), SAP, and governance platforms like IVIG through AssemblyLines and a large library of connectors. The MCP Server Connector exposes that existing integration logic to AI clients **without** giving the AI (or its operators) direct credentials to the underlying systems.

The pattern in every use case below is the same:

1. An MCP client (Claude) calls a **tool** by name.
2. The connector validates the tool against a configured **catalog**, maps the call to an SDI **work Entry**, and runs **one AssemblyLine cycle**.
3. The AL does the real work using SDI connectors, then sets a small set of reserved attributes that the connector turns back into the MCP tool result.

SDI stays the policy, transformation, and audit point. The connector is a thin, secured, AI-facing facade.

**Terms used in this doc:** *SDI* = IBM Security Directory Integrator; *AL* = AssemblyLine; *MCP* = Model Context Protocol; *IGA* = identity governance & administration; *IVIG* = IBM Verify Identity Governance (the IGA platform, used here as the generic placeholder); *GRC* = governance, risk & compliance; *SoD* = segregation of duties; *RACF/zSecure* = z/OS mainframe security; *break-glass* = emergency high-privilege accounts; *RAG* = retrieval-augmented generation (AI grounded on retrieved data); *confused deputy* = tricking an authorized component into misusing its authority on an attacker's behalf; *mTLS* = mutual TLS (client-certificate authentication).

---

## 2. How it works (primer)

**One AssemblyLine = one MCP server** (you can run several, each its own endpoint — e.g. separate read and write servers). The connector runs as the AL's feed in **Server** mode. `initialize`, `tools/list`, and `notifications/initialized` are answered by the connector itself; a `tools/call` becomes a work Entry and drives one AL cycle.

**The tool catalog** (a connector config parameter) is a JSON array of tool definitions returned verbatim to `tools/list`. It is also an **allowlist**: a `tools/call` for a tool name not in the catalog is rejected before the AL runs. This is the primary control over *what the AI can do*.

**Request → Entry mapping** (`tools/call` → work Entry). The AL's Data Flow reads:

| Attribute | Meaning |
| --- | --- |
| `$mcp.tool` | the tool name (your Data Flow branches on this) |
| `$mcp.arguments` | the raw arguments JSON (parse for nested data) |
| `$mcp.requestId` | JSON-RPC id (use for audit correlation) |
| `$mcp.protocolVersion` | negotiated MCP version |
| *each top-level scalar argument* | also set as its own attribute (e.g. `userId`); nested objects/arrays are **not** flattened — read them from `$mcp.arguments` |
| `$mcp.actor` / `$mcp.actorCn` | verified caller identity (mTLS cert DN/CN, or trusted `actorHeader`), when configured — see UC2 |

**Reply Entry → result mapping.** Your AL sets:

| Attribute | Becomes |
| --- | --- |
| `$mcp.result` | the human-readable text result |
| `$mcp.structured` | JSON string → MCP `structuredContent` (machine-readable) |
| `$mcp.isError` | `"true"` marks a tool-level (business) error |

**Security model (v1).** Bearer token or mutual TLS; strict `Origin` allowlist; auth runs before any resource disclosure; the tool allowlist cannot be bypassed via injected arguments; no internal Entry state is leaked on an empty result; stateless (no session, no SSE). Full detail in [CONFIGURE.md](CONFIGURE.md) and [GOTCHAS.md](GOTCHAS.md).

---

## 3. Use Case 1 — AI-Assisted Identity Service Desk (read-only lookups)

### Scenario & business driver
Tier-1/2 service-desk staff spend significant time answering routine identity questions and navigating multiple consoles (AD, HR, IVIG) to do it. Giving every agent direct read access to those systems is over-provisioning and an audit liability. Instead, agents ask Claude in natural language — *"what groups is jdoe in?"*, *"is this account locked and why?"*, *"who is their manager?"*, *"when does this contractor's access expire?"* — and Claude calls read-only MCP tools backed by SDI.

### Actors & flow
Service-desk agent → Claude → MCP endpoint (SDI AL, Server mode) → AD/LDAP, HR database (JDBC), IVIG (REST). All read-only.

### AssemblyLine design
One generic AL branches on `$mcp.tool`:
- **LDAP Connector** (Lookup mode) against AD for account/group data.
- **JDBC Connector** against the HR system for employment status, manager, contract dates.
- **IVIG REST** for entitlements/role assignments.
- A Script/Attribute Map normalizes results and, importantly, **whitelists which attributes are returned** — the AL is where you enforce that, say, `homePhone` or `nationalId` is never exposed.

### Tool catalog
```json
[
  {
    "name": "lookup_user",
    "description": "Look up a user's core profile by corporate ID.",
    "inputSchema": {
      "type": "object",
      "properties": { "userId": { "type": "string", "description": "Corporate user ID (sAMAccountName)" } },
      "required": ["userId"]
    }
  },
  {
    "name": "get_user_groups",
    "description": "List the AD/LDAP groups a user belongs to.",
    "inputSchema": {
      "type": "object",
      "properties": { "userId": { "type": "string" } },
      "required": ["userId"]
    }
  },
  {
    "name": "check_account_status",
    "description": "Report whether an account is enabled, locked, or expired, and why.",
    "inputSchema": {
      "type": "object",
      "properties": { "userId": { "type": "string" } },
      "required": ["userId"]
    }
  }
]
```

### Request → Entry → reply mapping
Client call:
```json
{"jsonrpc":"2.0","id":42,"method":"tools/call",
 "params":{"name":"check_account_status","arguments":{"userId":"jdoe"}}}
```
On the work Entry the AL sees `$mcp.tool=check_account_status`, `userId=jdoe`, `$mcp.requestId=42`. The Data Flow branches, does an LDAP lookup, and sets:
- `$mcp.result` = `"jdoe: account is LOCKED (badPwdCount exceeded), last lockout 2026-06-20 09:14 UTC."`
- `$mcp.structured` = `{"userId":"jdoe","enabled":true,"locked":true,"lockoutTime":"2026-06-20T09:14:00Z","reason":"badPwdCount"}`

Reply to the client:
```json
{"jsonrpc":"2.0","id":42,"result":{
  "content":[{"type":"text","text":"jdoe: account is LOCKED (badPwdCount exceeded), last lockout 2026-06-20 09:14 UTC."}],
  "structuredContent":{"userId":"jdoe","enabled":true,"locked":true,"lockoutTime":"2026-06-20T09:14:00Z","reason":"badPwdCount"},
  "isError":false}}
```

### Security & governance controls
- **Read-only by configuration:** the catalog and AL contain *only* lookup logic, so there is no write tool for the AI to call. (This is a discipline you maintain, not a hard guarantee — keep write tools out of read endpoints.)
- **Attribute minimization** enforced in the AL, not the client.
- Bearer token per service-desk integration; `Origin` allowlist; TLS for any non-localhost reach.

### Value / outcomes
Faster mean-time-to-resolution; agents need no standing read access to source systems; a single audited egress; consistent, attribute-governed answers regardless of which back-end holds the data.

### Running the demo (Docker OpenLDAP)

A self-contained, reproducible instance of this use case ships in the repo, backed by a containerized OpenLDAP rather than AD/IVIG. The solution config and its externalized properties are in [`examples/`](../examples) (`MCP_Server_Example.xml`, `MCP_Server_Example.properties`). The tool catalog above is the conceptual AD version; the runnable demo is adapted to OpenLDAP's schema (`inetOrgPerson` + `groupOfNames`, keyed on `uid`) and exposes five read-only tools: `lookup_user`, `get_user_groups`, `list_group_members`, `search_users`, and `get_user_overview` (a composite that returns profile **and** memberships in one call).

**1. Start the directory.** From [`docker/`](../docker), bring up OpenLDAP (`osixia/openldap`, domain `example.com`). [`seed.ldif`](../docker/seed.ldif) loads on first init — 5 users and 3 groups with overlapping membership and a `manager` hierarchy. The LDAP admin password is set by `LDAP_ADMIN_PASSWORD` in [`docker-compose.yml`](../docker/docker-compose.yml) — note the value you use; step 2 must match it.

```bash
cd docker && docker compose up -d
```

The custom LDIF loads only into a fresh data volume. To re-seed after editing it: `docker compose down -v && docker compose up -d`.

**2. Set the secrets (shipped blank).** The example is committed **without** credentials, so two things must be set before it will serve:

- **LDAP bind password** — set `ldapAdminPwd` in [`examples/MCP_Server_Example.properties`](../examples/MCP_Server_Example.properties) to the **same value** as `LDAP_ADMIN_PASSWORD` in `docker-compose.yml`. (The connector's `ldapPassword` resolves from this property.) `ldapAdminDN` defaults to the OpenLDAP admin DN, e.g. `cn=admin,dc=example,dc=com`.
- **Bearer token** — `bearerToken` in the `.properties` is blank, so `authMode=bearer` **fails closed** (every call `401`) until you set one. First **create a token** — a strong, random, high-entropy value:

  ```bash
  openssl rand -hex 32     # 64-char hex; this exact string is the shared secret
  ```

  (Or, in the Config Editor, click **Generate Token** on the `MCPServerConnection` **Connection tab** and copy the value it produces.) Then set it as `bearerToken=<value>` in [`examples/MCP_Server_Example.properties`](../examples/MCP_Server_Example.properties), and give the **same** value to every client as its `Authorization: Bearer <value>` header. Treat it like a password: don't commit a real one (the shipped file keeps it blank), and rotate by regenerating and updating both the property and each client. Note it lives in the properties file in **plaintext** — fine for a localhost demo; for a networked deployment prefer the connector's encrypted `bearerToken` field (`PASSWORD` syntax, auto-decrypted at runtime) or a vault.

**3. Run the AssemblyLine as an MCP server.** From your VDI solution directory (or point `-c` at the repo copy), start the UC1 AssemblyLine with the VDI server runtime:

```bash
ibmdisrv -c examples/MCP_Server_Example.xml -r MCPServer_LDAP
```

`-c` loads the solution config (which also contains the `MCP_Smoke_Test` AL for a bare echo/`server_time` check); `-r` runs the `MCPServer_LDAP` AssemblyLine, which *is* the MCP server. It listens (per its connector config) at `http://127.0.0.1:8443/mcp`, with an unauthenticated health probe at `/health`.

**4. Call a tool.** With `authMode=bearer` and an `Origin` allow-list configured, a lookup looks like (use the token from step 2):

```bash
curl -s -X POST http://127.0.0.1:8443/mcp \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer <token>' \
  -H 'Origin: https://good.example' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"lookup_user","arguments":{"userId":"alice"}}}'
```

Point a real MCP client (e.g. Claude Code) at the same endpoint for the natural-language experience. See [CONFIGURE.md](CONFIGURE.md) for connector setup and the client-connection steps, and [`scripts/revalidate.sh`](../scripts/revalidate.sh) for the transport/security acceptance matrix. Gotchas hit while building this AL in the Config Editor are catalogued in [GOTCHAS.md](GOTCHAS.md) §9–§16.

---

## 4. Use Case 2 — Governed Account Actions / Self-Service Automation (controlled writes)

> ⚠️ **Highest-risk use case.** A write tool lets an AI change identity state, and the AI decides *what* to call and *with which arguments* from natural language that may carry untrusted, injected content. Treat the model as an untrusted caller and the AssemblyLine — never the model — as the enforcement point. Implement the controls in *Security considerations* below, or don't expose writes. The read-only use cases (1, 3, 4) carry far less risk; start there.

### Scenario & business driver
Beyond lookups, much service-desk and automation work is *action*: unlock or reset a password, enable/disable an account, add a user to a group, or open an access request. Doing this safely from an AI assistant requires every action to pass through enterprise policy and be fully audited. SDI already encodes that logic in AssemblyLines.

### Actors & flow
Agent or automation → Claude → MCP endpoint (SDI AL) → AD/LDAP (modify), IVIG (request submission). The AL enforces business rules, optional approval routing, and audit logging on every call.

### AssemblyLine design
Branch on `$mcp.tool` to distinct write flows:
- **LDAP Connector** (Update/Modify mode) for password reset, enable/disable, group membership.
- **IVIG REST** to submit an access request (returning a request/ticket id) rather than directly granting — keeping governed approval in the loop.
- **Hooks** (e.g. `after` / error hooks) write a structured audit record (to the SIEM, the SDI system store, or a JDBC audit table) keyed by `$mcp.requestId`.
- Policy guards in script: e.g. refuse to reset privileged-account passwords, enforce naming/scope rules — and signal refusals via `$mcp.isError`.

### Tool catalog
```json
[
  {
    "name": "reset_password",
    "description": "Reset a standard user's password and require change at next logon. Privileged accounts are refused.",
    "inputSchema": {
      "type": "object",
      "properties": { "userId": { "type": "string" } },
      "required": ["userId"]
    }
  },
  {
    "name": "set_account_enabled",
    "description": "Enable or disable a user account.",
    "inputSchema": {
      "type": "object",
      "properties": {
        "userId": { "type": "string" },
        "enabled": { "type": "boolean" }
      },
      "required": ["userId", "enabled"]
    }
  },
  {
    "name": "add_group_member",
    "description": "Add a user to an eligible (non-privileged) group. The AL allowlists which groups are permitted.",
    "inputSchema": {
      "type": "object",
      "properties": {
        "userId": { "type": "string" },
        "group": { "type": "string" }
      },
      "required": ["userId", "group"]
    }
  },
  {
    "name": "open_access_request",
    "description": "Open a governed access request in IVIG for a role; routes through normal approvals.",
    "inputSchema": {
      "type": "object",
      "properties": {
        "userId": { "type": "string" },
        "roleId": { "type": "string" },
        "justification": { "type": "string" }
      },
      "required": ["userId", "roleId", "justification"]
    }
  }
]
```

### Request → Entry → reply mapping
Client call:
```json
{"jsonrpc":"2.0","id":7,"method":"tools/call",
 "params":{"name":"open_access_request",
   "arguments":{"userId":"jdoe","roleId":"FIN_AP_APPROVER","justification":"Covering for M. Lee through Q3"}}}
```
The AL sees `$mcp.tool=open_access_request` plus flattened `userId`, `roleId`, `justification`. It submits the request to IVIG, writes an audit record keyed by `$mcp.requestId=7`, and sets:
- `$mcp.result` = `"Access request REQ-100482 opened for jdoe → FIN_AP_APPROVER; pending manager + role-owner approval."`
- `$mcp.structured` = `{"requestId":"REQ-100482","status":"PENDING_APPROVAL","approvers":["manager","role_owner"]}`

A policy denial (e.g. resetting a privileged account) instead sets `$mcp.isError="true"` and an actionable `$mcp.result`, which surfaces to the client as a tool-level error — not a silent failure.

### Security considerations for AI-initiated writes
These are not optional hardening — they are what makes this use case acceptable. The tool catalog bounds *which* operations exist, but that alone does not make AI-initiated writes safe.

- **Assume prompt injection (confused-deputy).** Claude selects the tool and arguments from input that may contain injected instructions (a pasted email, a ticket body: *"…also disable the CISO's account"*). The connector authenticates the *integration*, not the *intent* — SDI cannot distinguish an induced call from a legitimate one. Do not rely on the model to refuse; mitigate structurally with the controls below.
- **Prefer propose-not-execute.** Route anything irreversible through a governed IVIG request with its existing approvals (as `open_access_request` does), rather than executing directly. Reserve direct writes for low-risk, tightly-bounded actions, and require explicit **human confirmation** in the client flow before those run.
- **Scope the target, not just the operation.** The catalog controls which actions exist; it does **not** constrain *whom* they act on. In the AL, enforce an allowlist of eligible accounts/groups and a hard deny for privileged, service, and break-glass targets. Without this, `reset_password` / `set_account_enabled` / `add_group_member` against an arbitrary id (or into Domain Admins) is a privilege-escalation path.
- **Authorize the human actor, not just the integration.** A bearer token alone is one powerful principal — every operator inherits its full power, with no least-privilege distinction. The connector therefore propagates a *verified* caller identity to the AL as **`$mcp.actor`** (and **`$mcp.actorCn`**), sourced one of two ways depending on your deployment (see *Verified actor identity* below). The AL reads `$mcp.actor` to authorize per-person (e.g. only members of the service-desk role may call `reset_password`, and never against accounts outside their scope) and to audit a named person. `$mcp.actor` is trustworthy: it comes from the TLS layer or a trusted proxy, never from client arguments, and the connector refuses any client-supplied `$mcp.*` argument that tries to forge it.
- **Separate read and write.** Put write tools on a distinct AL/endpoint with stronger, separately-rotated credentials (ideally mTLS) and a tighter `Origin`/network policy. A read-only integration must never be able to reach a write tool.
- **Audit for accountability, not just correlation.** From an AL hook, log every write — resolved human actor, tool, target, arguments, outcome — keyed by `$mcp.requestId`, to the SIEM, and alert on sensitive targets. Treat write-tool tokens as high-value secrets: short-lived, rotated, mTLS-bound.
- **The allowlist still can't be bypassed**, but it's not a substitute for the above: a caller of a permitted tool cannot smuggle a different tool name via crafted arguments (the connector ignores client-supplied `$mcp.*` keys — see [GOTCHAS.md](GOTCHAS.md)). That bounds *which* tools run, not *whom* they run against.

### Verified actor identity (`$mcp.actor`)
Two deployment models populate `$mcp.actor`; pick based on whether your AI client can present a client certificate:

- **mTLS terminated at the connector** — set `useSSL=true` + `needClientAuth=true`. The connector reads the client certificate's subject **DN** into `$mcp.actor` and its **CN** into `$mcp.actorCn`. Use this when the client (or a local stdio↔HTTP bridge it runs) can present a cert. Note: Claude Desktop/Code do not currently expose client-certificate config for HTTP MCP servers, so this model typically needs such a bridge.
- **Auth terminated at a reverse proxy / gateway** — the gateway verifies the user and injects the identity as an HTTP header; set the connector's **`actorHeader`** to that header name and it's read into `$mcp.actor` (only when no client cert is present). This works with any client, but trusts the upstream that sets the header — lock down the network path so clients can't set it directly.

Worked example — the AL authorizes and audits the named actor before acting:
```javascript
var actor = work.getString("$mcp.actor");           // e.g. "CN=a.smith,OU=ServiceDesk,O=Acme" or "a.smith"
if (actor == null || !callerInRole(actor, "ServiceDesk")) {
    work.setAttribute("$mcp.isError", "true");
    work.setAttribute("$mcp.result", "Caller not authorized for this action.");
} else {
    // ... perform the scoped action ...
    audit(actor, work.getString("$mcp.tool"), work.getString("userId"), work.getString("$mcp.requestId"));
}
```
If `$mcp.actor` is absent (no mTLS cert and no `actorHeader`), treat it as unauthenticated-actor and refuse write actions — don't fall back to acting as the integration principal.

### Value / outcomes
AI-driven self-service and automation where SDI remains the single enforcement and audit point — the speed of conversational automation without bypassing identity governance. The value is real **only** when paired with the controls above; an under-governed write endpoint trades that governance away.

---

## 5. Use Case 3 — Enterprise Data Brokering for AI Agents (integrate & transform)

### Scenario & business driver
AI agents and RAG-style workflows increasingly need *real-time, trustworthy* enterprise data — but the systems of record are heterogeneous and often legacy (mainframe RACF, SAP, multiple LDAPs, departmental databases). Building bespoke AI integrations to each is slow, duplicative, and creates many new access paths. SDI already connects to these systems and can normalize their data; the MCP connector makes that a single AI-facing endpoint.

### Actors & flow
AI agent / workflow → MCP endpoint (SDI AL) → RACF (via the appropriate SDI connector), SAP, JDBC, LDAP. The AL transforms each system's native format into a **canonical JSON shape** returned as `structuredContent`.

### AssemblyLine design
Branch on `$mcp.tool`, one tool per source domain, each reusing existing SDI connectors and Attribute Maps:
- A mainframe tool fronting the RACF/zSecure connector.
- An SAP tool fronting the SAP connector for role assignments.
- An HR tool fronting the JDBC connector.
- Each flow maps native attributes → a documented canonical schema so the AI sees consistent field names across systems.

### Tool catalog
```json
[
  {
    "name": "get_mainframe_user",
    "description": "Return a normalized profile for a RACF user ID.",
    "inputSchema": {
      "type": "object",
      "properties": { "racfId": { "type": "string" } },
      "required": ["racfId"]
    }
  },
  {
    "name": "get_sap_role_assignments",
    "description": "Return a user's SAP role assignments in canonical form.",
    "inputSchema": {
      "type": "object",
      "properties": { "sapUser": { "type": "string" }, "client": { "type": "string" } },
      "required": ["sapUser"]
    }
  }
]
```

### Request → Entry → reply mapping
Client call:
```json
{"jsonrpc":"2.0","id":11,"method":"tools/call",
 "params":{"name":"get_mainframe_user","arguments":{"racfId":"JDOE01"}}}
```
The AL queries RACF, normalizes, and sets `$mcp.structured` to the canonical record:
```json
{"id":"JDOE01","system":"RACF","name":"Jane Doe","status":"REVOKE","groups":["SYS1","PAYROLL"],
 "lastAccess":"2026-05-30","attributes":{"uacc":"NONE","special":false}}
```
`$mcp.result` carries a short text summary for conversational display.

### Security & governance controls
- **Single governed egress:** the AI never touches systems of record directly; SDI is the only path, with one set of service credentials held in SDI.
- **Field-level filtering** in the AL keeps sensitive native fields out of the canonical output.
- **Read-only** brokering tools; writes belong in Use Case 2's governed flows.

### Value / outcomes
Reuse decades of SDI connectors and transformation logic as AI tools; one auditable facade over legacy systems; no proliferation of per-system AI integrations or new credential paths.

---

## 6. Use Case 4 — Compliance, Audit & Access-Certification Reporting (governance queries)

### Scenario & business driver
Audit and governance teams repeatedly ask the same questions ahead of certifications and audits: *what's the status of the current recertification campaign?*, *which accounts are orphaned?*, *are there segregation-of-duties (SoD) violations?*, *show me everything user X can access*. These answers live in IVIG and the IGA reporting store. Exposing them as AI tools lets GRC staff self-serve in natural language instead of waiting on report runs.

### Actors & flow
Auditor / GRC analyst → Claude → MCP endpoint (SDI AL) → IVIG (REST) and the IGA reporting database (JDBC). Read-only.

### AssemblyLine design
Branch on `$mcp.tool`:
- **IVIG REST** for live certification-campaign status and entitlement/role data.
- **JDBC Connector** against the IGA reporting/warehouse schema for orphan-account and SoD analytics.
- Script/Attribute Map shapes results into report-friendly `structuredContent` and a readable summary; large result sets are summarized/capped in the AL.

### Tool catalog
```json
[
  {
    "name": "get_certification_status",
    "description": "Summarize a recertification campaign's progress and overdue reviewers.",
    "inputSchema": {
      "type": "object",
      "properties": { "campaignId": { "type": "string" } },
      "required": ["campaignId"]
    }
  },
  {
    "name": "list_orphan_accounts",
    "description": "List accounts with no valid owner in a target system.",
    "inputSchema": {
      "type": "object",
      "properties": { "targetSystem": { "type": "string" } },
      "required": ["targetSystem"]
    }
  },
  {
    "name": "get_user_entitlement_report",
    "description": "Return a user's full entitlement set across governed systems.",
    "inputSchema": {
      "type": "object",
      "properties": { "userId": { "type": "string" } },
      "required": ["userId"]
    }
  }
]
```

### Request → Entry → reply mapping
Client call:
```json
{"jsonrpc":"2.0","id":3,"method":"tools/call",
 "params":{"name":"get_certification_status","arguments":{"campaignId":"Q2-2026-ACCESS"}}}
```
The AL queries IVIG and sets:
- `$mcp.result` = `"Campaign Q2-2026-ACCESS: 78% complete (1,840 / 2,360 items). 14 reviewers overdue. Closes 2026-06-30."`
- `$mcp.structured` = `{"campaignId":"Q2-2026-ACCESS","percentComplete":78,"itemsReviewed":1840,"itemsTotal":2360,"overdueReviewers":14,"closeDate":"2026-06-30"}`

### Security & governance controls
- **Read-only** governance tools; no remediation actions in this catalog (route those through Use Case 2).
- **Data sensitivity:** entitlement and orphan reports can contain PII and revealing access detail — apply field control and result caps in the AL, and restrict this endpoint's bearer token to the GRC integration.
- Full audit trail of who queried what, via `$mcp.requestId` correlation.

### Value / outcomes
Self-service compliance answers; faster audit and certification preparation; natural-language access to governance data without standing IVIG report-author access for every analyst.

---

## 7. Cross-cutting considerations

**Security posture.** Use bearer tokens (or mTLS) on every endpoint; set a strict `Origin` allowlist for networked deployments; terminate TLS (`useSSL`, or front with a reverse proxy). Auth runs before any path/method disclosure, the tool allowlist can't be bypassed, and an empty result never leaks internal Entry state. See [CONFIGURE.md](CONFIGURE.md) §3/§6.

**The catalog is your access-control surface.** What you put in `toolCatalog` is exactly what the AI can do. Separate read and write tools across different ALs/endpoints with different tokens so a read-only integration can never reach a write tool.

**Auditability.** Treat `$mcp.requestId` as the correlation key and log every `tools/call` from an AL hook to your SIEM/audit store. Because all traffic funnels through SDI, you get one chokepoint for monitoring AI-initiated identity activity.

**One generic AL vs. per-tool ALs.** A single AL branching on `$mcp.tool` is simplest and matches the multi-tool catalog model. Split into multiple ALs/endpoints when tools have very different security tiers (e.g. read vs. write) or ownership.

**When *not* to use this connector:**
- **Streaming / long-running progress** — v1 is request/response only (no SSE); a tool that needs to stream partial output isn't a fit yet.
- **Stateful multi-step sessions** — v1 is stateless (no `Mcp-Session-Id`); keep per-call interactions self-contained, or hold state in an external store.
- **High-throughput bulk data movement** — SDI batch ALs remain the right tool; MCP is for interactive, tool-shaped requests.
- **Untrusted / public exposure** without TLS + strong auth (ideally mTLS) — keep it internal or properly fronted.
- **When a direct API already serves the AI well** — don't add SDI in the path if there's no integration, transformation, or governance value to add.

**Deployment topology (typical).** The AL runs as a persistent Server-mode listener on an SDI server inside the trusted network; clients reach it over TLS, optionally through a reverse proxy that adds network controls. Bind to a specific interface (`bindAddress`) and expose only the configured `endpointPath`; a `healthPath` can be enabled for load-balancer probes.

---

## 8. Summary & next steps

The MCP Server Connector lets an enterprise reuse its existing SDI integration estate as governed, AI-callable tools — spanning read (service desk, brokering, compliance reporting) and controlled write (governed actions), with SDI remaining the policy, transformation, and audit point.

**Suggested adoption path:**
1. **Pilot with a read-only use case** (Use Case 1 or 4) — lowest risk, immediate value, exercises the full path end-to-end.
2. **Add governed writes** (Use Case 2) once audit logging and the catalog-as-authorization model are proven, preferring "open a request" over direct grants.
3. **Generalize to brokering** (Use Case 3) as more AI workflows need trustworthy enterprise data.

For build details: [SPEC.md](SPEC.md) (design & decisions), [CONFIGURE.md](CONFIGURE.md) (connector setup, security, connecting Claude), [GOTCHAS.md](GOTCHAS.md) (platform behaviors to know).
