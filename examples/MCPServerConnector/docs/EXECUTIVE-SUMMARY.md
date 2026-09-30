# MCP Server Connector — Executive Summary

**Component:** `com.vdi.di:mcp-server-connector` (v0.1.0) · IBM Verify Directory Integrator (VDI) 10
**Status:** Use Case 1 (read-only Identity Service Desk) demonstrated end-to-end against a live directory.

---

## What it is

The MCP Server Connector turns an IBM VDI **AssemblyLine** into a **Model Context Protocol (MCP) server**. When the AssemblyLine runs, it *is* an MCP endpoint that an AI client such as Claude can call over standard HTTPS. Each inbound AI "tool call" becomes the AssemblyLine's work item; the AssemblyLine's response becomes the tool result.

In practical terms: the integration logic an enterprise **already owns** in VDI — its connections to Active Directory/LDAP, HR systems, databases, and governance platforms — becomes callable by AI **without giving the AI, or its operators, direct credentials to those back-end systems**. The AssemblyLine remains the single point of enforcement and audit.

## The problem it addresses

Service-desk and operations staff spend significant time answering routine identity questions and navigating multiple consoles to do it. Granting every agent direct read access to the underlying systems is over-provisioning and an audit liability. Pointing an AI directly at those systems is worse. This connector lets staff ask questions in natural language while every answer flows through one governed, audited, attribute-controlled path.

## What was demonstrated (Use Case 1)

**AI-Assisted Identity Service Desk** — a set of **read-only** identity-lookup tools, implemented as one AssemblyLine and exposed as MCP tools. The AI decides *which* tool to call from the user's natural-language question; the AssemblyLine decides *what data* is allowed back.

| Tool | Answers |
|------|---------|
| `lookup_user` | A user's core profile (name, email) by ID |
| `get_user_groups` | Which groups a user belongs to |
| `list_group_members` | Who is in a given group |
| `search_users` | Find users by partial name, ID, or email |
| `get_user_overview` | Profile **and** group memberships in a single call |

All five were validated returning correct, live data, including negative cases (unknown user → clean "no match", not an error) and an injection-safety check (a wildcard query is escaped, not executed).

## Where it runs

The demo deploys against a **containerized OpenLDAP** directory (`osixia/openldap`, domain `example.com`), stood up with a single `docker compose up`. The directory is seeded with **5 users** and **3 groups** (with overlapping membership and a manager hierarchy) so the tools exercise realistic single-result, multi-result, and composite lookups. The connector, the AssemblyLine, and the directory all run locally, making the demo fully self-contained and reproducible.

```
Claude (MCP client)  →  MCP Server Connector / VDI AssemblyLine  →  OpenLDAP (Docker)
        natural language        governed tool calls, one audited egress        directory of record
```

## Security & governance built into the demo

- **No standing back-end access for the AI or the agent** — credentials stay in VDI; the AI holds only a scoped bearer token to the MCP endpoint.
- **Bearer-token authentication** and an **Origin allow-list** (DNS-rebinding protection) on every request; TLS and mutual-TLS are supported for networked deployments.
- **Attribute minimization enforced in the AssemblyLine** — only whitelisted attributes are ever returned, regardless of what the directory holds.
- **Injection-safe search** — free-text queries are escaped before reaching LDAP.
- **Verified caller identity** available (`$mcp.actor`) for per-person authorization and audit — the identity comes from the TLS layer or a trusted proxy and cannot be forged by the AI.
- **Read-only by construction** — the demo catalog contains only lookup tools; no write path is exposed.

## Status and next steps

Use Case 1 is **demonstrated and validated** end-to-end against a live directory — a working reference for how governed, AI-facing identity services can be delivered on existing VDI infrastructure. It is a proof of capability, not yet a production-hardened release (for example, mutual-TLS is designed and supported but not yet exercised with real certificates end-to-end).

Natural follow-on work, in increasing order of risk and value:

1. **Broaden read-only coverage** — additional lookups (manager chains, membership checks), and enrich the directory to demonstrate account-status and org-hierarchy tools.
2. **Governed write actions (Use Case 2)** — group membership changes, password resets — gated on verified caller identity, approval routing, and full audit. Higher risk; the AssemblyLine, never the AI, remains the enforcement point.
3. **Compliance & access-certification reporting (Use Case 4)** — read-only governance queries over the same endpoint.

See [`USE-CASES.md`](USE-CASES.md) for the full use-case catalog, [`CONFIGURE.md`](CONFIGURE.md) for setup, and [`SPEC.md`](SPEC.md) for the connector design.
