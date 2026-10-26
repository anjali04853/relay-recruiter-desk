# Relay — API C2C Recruiter Workspace

A Java 17 / Spring Boot application for the internship workflow: ingest authorized posts → filter US opportunities published within 24 hours → extract recruiter emails → create personalized drafts → review and approve → dry-run or send via SMTP.

## Run

Requires JDK 17+ and Maven 3.6.3+.

```powershell
mvn test
mvn package
java -jar target/recruiter-desk-1.0.0.jar
```

Open [http://localhost:8080](http://localhost:8080). Alternatively run `./start.ps1`; it builds the JAR if absent. After source changes, rebuild with `mvn package`.

On Windows environments with a long sandbox temporary path, use `./start.ps1`; it supplies short project-local Java and Unix-domain socket temporary paths. The test configuration does the same. If launching the JAR directly in such an environment, add `"-Djdk.net.unixdomain.tmpdir=$PWD\target"` before `-jar`.

The app binds to **127.0.0.1** and stores data in `./data/`. No account or external credentials are required for the local demonstration. Do not expose this single-user application to a network without adding authentication, authorization, HTTPS, and operational controls. LinkedIn connection is an integration identity, not application access control.

## Demonstrate the complete workflow

1. Click **Load demo**. Four matching sample posts and four contacts are added; one old post and one non-US post are rejected. Demo data is clearly labeled and uses reserved `.example` email addresses.
2. Open **Discover posts**, search the imported data, and inspect keyword matches.
3. Open **Recruiters** to inspect unique extracted email addresses or download CSV.
4. Open **Email template**, replace the starter with the team's actual supplied format, and add your own profile information. The original assignment did not include the team template.
5. Click **Create drafts**. Each contact gets at most one draft. Existing drafts are preserved.
6. Open **Outreach**, edit a message, then approve it. Editing resets approval.
7. Click **Test dry run**. An activity entry is recorded, no email is sent, and the message remains approved. Suppressing a contact blocks pending outreach.

The app does not invent recruiter emails: it extracts addresses explicitly present in the supplied post text. Names and companies come from imported post metadata and may need human verification. A contact's first observed post is retained as provenance. Posts remain in the historical database after the 24-hour window; the window is enforced when ingesting, not a rolling deletion rule. Country must be explicitly `US`; ambiguous or missing country metadata is rejected.

## LinkedIn sign-in

LinkedIn uses OAuth 2.0. Standard sign-in access provides identity and **does not grant unrestricted keyword search of public LinkedIn posts**. This application does not automate password entry, bypass CAPTCHA, or scrape logged-in pages.

Create your LinkedIn developer app, enable **Sign In with LinkedIn using OpenID Connect**, and register exactly this redirect URL:

```text
http://localhost:8080/auth/linkedin/callback
```

Set environment variables before launching the server:

```powershell
$env:LINKEDIN_CLIENT_ID = 'your-client-id'
$env:LINKEDIN_CLIENT_SECRET = 'your-client-secret'
$env:LINKEDIN_REDIRECT_URI = 'http://localhost:8080/auth/linkedin/callback'
java -jar target/recruiter-desk-1.0.0.jar
```

Use **Connections → Connect LinkedIn**. The application checks a one-time, ten-minute OAuth state value, exchanges the authorization code server-side, fetches `/v2/userinfo`, rotates the session ID, and retains only name/email in the session. Tokens and client secrets are not returned to the browser. Reuse of the browser's authorized LinkedIn session can streamline subsequent sign-ins, but initial user consent is required. Disconnect clears the local session; it does not revoke LinkedIn app consent.

Official documentation: [LinkedIn API access](https://learn.microsoft.com/en-us/linkedin/shared/authentication/getting-access), [OpenID Connect sign-in](https://learn.microsoft.com/en-us/linkedin/consumer/integrations/self-serve/sign-in-with-linkedin-v2).

## Post import API

Import a JSON array using **Discover posts → Import JSON / Upload file** or the API:

```json
[
  {
    "author": "Recruiter Name",
    "company": "Staffing Company",
    "text": "Java Developer C2C, US remote. Send profiles to recruiter@company.com",
    "location": "Austin, TX",
    "country": "US",
    "publishedAt": "2026-10-26T14:00:00Z",
    "url": "https://www.linkedin.com/posts/example"
  }
]
```

**Replace the example timestamp with a current ISO-8601 UTC timestamp** before importing. Future posts are rejected. At most 500 posts per batch, 30,000 characters per post, HTTPS URLs only. Malformed entries roll back the entire batch. Nonmatching, old, future and non-US posts are counted as filtered. Duplicate post URLs are ignored; without a URL, the author, text and timestamp identify the post. Addresses are deduplicated case-insensitively across contacts.

Keyword matching is case-insensitive and uses word boundaries, with OR semantics: Java Developer, C2C, Bench, Sales, W2, Full-Time (also Full Time), Hotlist.

### Authorized provider adapter

Set `POST_PROVIDER_URL` to your own **HTTPS** endpoint backed by data you are authorized to access. This is a generic integration contract, not a claim that LinkedIn supplies this endpoint. Set `POST_PROVIDER_TOKEN` if your endpoint needs a bearer token.

**Sync posts** sends an HTTP GET with `country=US`, `since=<UTC instant 24h ago>`, and `keywords=<comma-separated list>`. Your adapter must return HTTP 200 with the JSON array above (up to 500 entries / 4 MB). Adapt your approved LinkedIn or licensed provider response to this contract. The app revalidates all returned posts locally. Sync is initiated manually; no background collection is enabled.

## SMTP and live delivery

Dry-run mode is the default. No messages have been sent during development. To enable live email after setting the real team template and reviewing recipients:

```powershell
$env:SMTP_HOST = 'smtp.your-provider.com'
$env:SMTP_PORT = '587'
$env:SMTP_USERNAME = 'your-username'
$env:SMTP_PASSWORD = 'your-app-password'
$env:MAIL_FROM = 'you@your-domain.com'
$env:LIVE_SEND = 'true'
java -jar target/recruiter-desk-1.0.0.jar
```

STARTTLS and SMTP authentication default to required/enabled. Provider configuration must support STARTTLS. A single message must be explicitly approved and sent from the UI/API. A maximum of 25 deliveries or uncertain delivery attempts is allowed in a rolling 24-hour period. Demo contacts cannot receive live email. Sent, in-flight and uncertain messages cannot be re-approved or resent.

SMTP failures are conservatively marked **UNKNOWN** because an interrupted connection can occur after the server accepted the message. Check SMTP provider logs before reconciling delivery. The app does not automatically retry uncertain sends. If the process stops during sending, that draft remains **SENDING**, also requiring provider-log reconciliation. Exactly-once delivery cannot be guaranteed by SMTP.

## API reference

All mutating API calls require `X-Desk-Request: 1`; JSON bodies use `Content-Type: application/json`. There is no cross-origin API access. Keep this app bound to localhost.

| Method | Route | Purpose |
|---|---|---|
| GET | `/api/status` | Configuration flags and current LinkedIn profile |
| GET | `/api/stats` | Counts and recent activity |
| GET | `/api/posts` | Imported post history |
| POST | `/api/import` | Import JSON array |
| POST | `/api/demo` | Load sample data |
| POST | `/api/sync` | Fetch configured provider |
| GET | `/api/contacts` | Extracted contacts |
| POST | `/api/contacts/{id}/suppress` | Block pending outreach |
| GET | `/api/export` | Recruiter CSV with spreadsheet formula protection |
| GET / PUT | `/api/template` | Read/save `{subject, body}` |
| POST | `/api/drafts/generate` | Create missing drafts |
| GET | `/api/drafts` | Message queue |
| PUT | `/api/drafts/{id}` | Edit `{subject, body}` and reset approval |
| POST | `/api/drafts/{id}/approve` | Approve reviewed content |
| POST | `/api/drafts/{id}/send` | Dry-run or live delivery |
| GET | `/auth/linkedin` | Start OAuth sign-in |
| GET | `/auth/linkedin/callback` | Validate state and complete sign-in |
| POST | `/api/logout` | Disconnect local LinkedIn session |

Database field names in list responses are uppercase. Templates support `{{name}}`, `{{email}}`, `{{company}}`, `{{keywords}}`, and `{{source_url}}`. Environment variables are documented in `.env.example`; `.env` files are **not** automatically loaded by Spring Boot. Never commit credentials or local recruiter data.

## Verification

`mvn test` covers age/country/keyword filters, future timestamps, deduplication, transactional rollback, template rendering, approval reset, suppression, JSON serialization, required request headers, demo ingestion, draft generation, dry-run delivery, export, rejection of invalid OAuth callbacks, successful mock SMTP delivery, duplicate-send prevention, demo-send blocking, and uncertain SMTP outcome handling. Real LinkedIn and SMTP integration tests require your credentials and are not part of the automated suite.

The hiring message's “tomorrow at 7 PM” corresponds to **27 October 2026 at 7 PM IST** when interpreted relative to the supplied current date, 26 October 2026. No scheduler or automatic submission is configured.
