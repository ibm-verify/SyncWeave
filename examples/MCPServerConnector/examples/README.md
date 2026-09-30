# Example — UC1 Identity Service Desk

The runnable VDI solution for **Use Case 1** (AI-Assisted Identity Service Desk), demonstrated against the Docker OpenLDAP in [`../docker`](../docker).

| File | What it is |
|------|------------|
| `MCP_Server_Example.xml` | The VDI solution export. Contains the `MCPServer_LDAP` AssemblyLine (the five UC1 tools) and a `MCP_Smoke_Test` AL (bare `echo`/`server_time`). |
| `MCP_Server_Example.properties` | Externalized config the ALs read via `@SUBSTITUTE{...}` — LDAP URL, base DNs, and the bind account. |

## Committed without credentials

Both secrets ship **blank** — set them before the AL will serve:

- **`ldapAdminPwd`** (in the `.properties`) — set to the same value as `LDAP_ADMIN_PASSWORD` in [`../docker/docker-compose.yml`](../docker/docker-compose.yml).
- **`bearerToken`** (in the `.properties`) — blank, so `authMode=bearer` fails closed (`401`) until you set one. Create a strong random token and paste it as `bearerToken=<value>`:
  ```bash
  openssl rand -hex 32     # 64-char hex; use this exact value as each client's Bearer token
  ```
  (Or click **Generate Token** on the `MCPServerConnection` Connection tab in the Config Editor and copy the value.) It's plaintext here — keep real values out of git (see below); rotate by regenerating and updating the property and every client.

## Run it

Full walkthrough — start the directory, set the secrets, run the AL, call a tool — is in [`../docs/USE-CASES.md`](../docs/USE-CASES.md) §3 (*Running the demo*). In short:

```bash
cd ../docker && docker compose up -d          # 1. directory (loads seed.ldif)
# 2. set ldapAdminPwd + generate bearer token (see USE-CASES.md §3)
ibmdisrv -c MCP_Server_Example.xml -r MCPServer_LDAP   # 3. run the MCP server
```

Validate with [`../scripts/revalidate.sh`](../scripts/revalidate.sh); connector setup and client connection are in [`../docs/CONFIGURE.md`](../docs/CONFIGURE.md).
