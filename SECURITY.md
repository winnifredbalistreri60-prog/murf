# Security Policy

Please report vulnerabilities **privately** via GitHub Security Advisories ("Report a vulnerability" on the Security tab), not as public issues.

Scope: the Android app, the server helper scripts in `server/` (phone relay, agent screen, local mode). Issues in Hermes Agent or hermes-webui should go to those projects.

Design notes:
- Credentials are stored with EncryptedSharedPreferences; nothing secret is committed to this repository.
- Agent screen and phone-relay APIs are protected by the WebUI session (nginx `auth_request`); screen control additionally checks Origin.
- Every phone action requested by the agent requires user confirmation unless explicitly allowed "always".
- Local mode binds only to 127.0.0.1 and generates its own keys; copying secrets from the server needs an explicit confirmation.
