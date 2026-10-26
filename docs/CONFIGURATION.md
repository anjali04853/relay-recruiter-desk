# Configuration and integrations

[← Project overview](../README.md)

Configuration is read from environment variables at startup. [`.env.example`](../.env.example) lists the supported settings; copying it to `.env` alone does not configure the application. Restart the server after changing environment variables.

## Configuration reference

| Variable | Default | Purpose |
| --- | --- | --- |
| `PORT` | `8080` | Local HTTP port |
| `DB_PASSWORD` | Empty | H2 database password; keep consistent for an existing database |
| `LINKEDIN_CLIENT_ID` | Empty | LinkedIn app client ID |
| `LINKEDIN_CLIENT_SECRET` | Empty | LinkedIn app client secret |
| `LINKEDIN_REDIRECT_URI` | `http://localhost:8080/auth/linkedin/callback` | Registered OAuth callback |
| `POST_PROVIDER_URL` | Empty | Authorized HTTPS post-provider endpoint |
| `POST_PROVIDER_TOKEN` | Empty | Optional provider bearer token |
| `LIVE_SEND` | `false` | Enable SMTP delivery instead of dry runs |
| `MAIL_FROM` | Empty | Sender email address |
| `SMTP_HOST` | `localhost` | SMTP hostname |
| `SMTP_PORT` | `587` | SMTP port |
| `SMTP_USERNAME` | Empty | SMTP username |
| `SMTP_PASSWORD` | Empty | SMTP password or app password |
| `SMTP_AUTH` | `true` | Enable SMTP authentication |
| `SMTP_TLS_REQUIRED` | `true` | Require STARTTLS |

Keep credentials in your environment. Never commit real credentials or recruiter data.

## LinkedIn OAuth sign-in

Create a LinkedIn developer app, enable **Sign In with LinkedIn using OpenID Connect**, and register this callback exactly:

```text
http://localhost:8080/auth/linkedin/callback
```

Set credentials before launching:

```powershell
$env:LINKEDIN_CLIENT_ID = 'your-client-id'
$env:LINKEDIN_CLIENT_SECRET = 'your-client-secret'
$env:LINKEDIN_REDIRECT_URI = 'http://localhost:8080/auth/linkedin/callback'
.\start.ps1
```

Use **Connections → Connect LinkedIn**. Initial user consent is required. The flow requests `openid profile email`, validates a single-use state with a ten-minute expiry, exchanges the code server-side, fetches the member profile, and rotates the local session ID. Only name and email are retained in the session; tokens and secrets are not returned to the browser.

Disconnecting clears the local session. It does not revoke app consent in LinkedIn. Identity access is separate from post discovery; an approved or licensed source must supply posts through imports or the provider contract below.

References: [LinkedIn API access](https://learn.microsoft.com/en-us/linkedin/shared/authentication/getting-access) · [OpenID Connect sign-in](https://learn.microsoft.com/en-us/linkedin/consumer/integrations/self-serve/sign-in-with-linkedin-v2).

## Authorized post provider

Configure your own HTTPS adapter backed by data you are authorized to access:

```powershell
$env:POST_PROVIDER_URL = 'https://your-provider.example/api/posts'
$env:POST_PROVIDER_TOKEN = 'your-provider-token'
.\start.ps1
```

Replace the placeholder hostname with your actual endpoint. This is a generic adapter contract, not a LinkedIn search endpoint.

**Sync posts** sends a GET with `country=US`, `since=<UTC instant 24h ago>`, and `keywords=<comma-separated assignment keywords>`. When configured, the token is sent as `Authorization: Bearer <token>`.

Return HTTP 200 and a JSON array using the [post schema](API.md#post-import-format). Responses are checked against a 4,000,000-character limit and batches against a 500-post limit. Local filters are reapplied before persistence. Sync is manual; background collection is not scheduled.

## Templates and SMTP delivery

Paste the team's supplied format into **Email template**. Complete the starter's personal profile placeholders before actual outreach. Supported variables: `{{name}}`, `{{email}}`, `{{company}}`, `{{keywords}}`, and `{{source_url}}`.

Saving a template affects new drafts. Existing drafts retain their content. Editing a draft resets approval.

Configure a STARTTLS-capable SMTP account to enable live delivery:

```powershell
$env:SMTP_HOST = 'smtp.your-provider.com'
$env:SMTP_PORT = '587'
$env:SMTP_USERNAME = 'your-username'
$env:SMTP_PASSWORD = 'your-app-password'
$env:MAIL_FROM = 'you@your-domain.com'
$env:LIVE_SEND = 'true'
.\start.ps1
```

Each message must be individually approved and sent. Demo contacts are blocked from live delivery. Suppression blocks pending outreach to a contact. A rolling limit counts up to 25 sent, in-flight, and uncertain attempts per 24 hours.

### Delivery states

| State | Meaning |
| --- | --- |
| `DRAFT` | Awaiting review |
| `APPROVED` | Eligible for a dry run or send request |
| `SENDING` | Claimed for delivery; SMTP attempt in progress |
| `SENT` | SMTP submission returned successfully; inbox receipt is not independently confirmed |
| `UNKNOWN` | SMTP error occurred; delivery outcome needs reconciliation |
| `SUPPRESSED` | Pending outreach was blocked for this contact |

A dry run records an event and leaves the message `APPROVED`. Sent, in-flight, and uncertain messages cannot be re-approved or resent through the normal workflow.

An SMTP server may accept a message before a connection fails, so failed attempts are conservatively marked `UNKNOWN`. A process interruption can leave a message `SENDING`. Check provider logs before reconciling either state. There is no automatic retry or reconciliation endpoint; SMTP cannot guarantee exactly-once delivery.

## Local operation

The database is stored in `data/` relative to the working directory. This local application does not implement multi-user authorization. A network deployment would require authentication, authorization, HTTPS, and deployment-specific operational controls.

On Windows, `start.ps1` supplies project-local temporary directories for Java networking. For direct JAR startup in environments with long temporary paths:

```powershell
java "-Djava.io.tmpdir=$PWD\target" "-Djdk.net.unixdomain.tmpdir=$PWD\target" -jar target/recruiter-desk-1.0.0.jar
```
