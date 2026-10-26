# API reference

[← Project overview](../README.md)

**Base URL:** `http://localhost:8080`

All mutating API requests require `X-Desk-Request: 1`. JSON bodies also require `Content-Type: application/json`. Cross-origin API access is not enabled. This API is intended for the local single-user workspace.

## Endpoints

| Method | Route | Purpose |
| --- | --- | --- |
| GET | `/api/status` | Configuration flags and connected LinkedIn profile |
| GET | `/api/stats` | Counts and recent activity |
| GET | `/api/posts` | Imported post history |
| POST | `/api/import` | Import an array of posts |
| POST | `/api/demo` | Load fictional demo posts |
| POST | `/api/sync` | Fetch configured provider |
| GET | `/api/contacts` | Extracted recruiter contacts |
| POST | `/api/contacts/{id}/suppress` | Block pending outreach |
| GET | `/api/export` | Contacts CSV with spreadsheet formula protection |
| GET / PUT | `/api/template` | Read or save `{subject, body}` |
| POST | `/api/drafts/generate` | Create missing drafts for eligible contacts |
| GET | `/api/drafts` | Message queue |
| PUT | `/api/drafts/{id}` | Edit `{subject, body}` and reset approval |
| POST | `/api/drafts/{id}/approve` | Approve reviewed content |
| POST | `/api/drafts/{id}/send` | Dry run or SMTP delivery |
| GET | `/auth/linkedin` | Start OAuth sign-in |
| GET | `/auth/linkedin/callback` | Validate callback and complete sign-in |
| POST | `/api/logout` | Clear local LinkedIn session |

Database-backed list responses use uppercase fields such as `ID`, `AUTHOR`, `EMAIL`, and `STATUS`. Errors return an `error` message. Missing request headers receive HTTP 403; invalid input is generally rejected with HTTP 400.

## Post import format

Use **Discover posts → Import JSON / Upload file**, or POST a JSON array to `/api/import`:

```json
[
  {
    "author": "Recruiter Name",
    "company": "Example Staffing",
    "text": "Java Developer C2C, US remote. Contact recruiter@staffing.example",
    "location": "Austin, TX",
    "country": "US",
    "publishedAt": "2026-10-26T14:00:00Z",
    "url": "https://www.linkedin.com/posts/example"
  }
]
```

Replace the timestamp with a current ISO-8601 UTC timestamp before importing. The sample email and post link are illustrative.

### Ingestion rules

- Each post needs `text` and a valid `publishedAt`. Country must explicitly be `US`, ignoring case.
- Publication must be within the previous 24 hours and must not be in the future.
- At least one keyword must match: **Java Developer, C2C, Bench, Sales, W2, Full-Time, Hotlist**. Matching ignores case and uses word boundaries; **Full Time** is also supported.
- Batches support up to 500 posts; post text supports up to 30,000 characters. Nonempty URLs must use HTTPS.
- Malformed entries roll back the batch. Old, future, non-US, and nonmatching posts are counted as filtered.
- Repeated URLs are deduplicated. Without a URL, identity derives from author, text, and timestamp. Demo posts have a separate identity namespace.
- Emails are extracted from text and deduplicated case-insensitively. Names and companies come from source metadata. Contacts retain their first observed post as provenance.
- Imported posts remain in history after the 24-hour window expires.

Successful import response:

```json
{
  "added": 4,
  "filtered": 2,
  "duplicates": 0
}
```

## PowerShell examples

Load the demo:

```powershell
Invoke-RestMethod -Method Post `
  -Uri 'http://localhost:8080/api/demo' `
  -Headers @{ 'X-Desk-Request' = '1' }
```

Generate drafts:

```powershell
Invoke-RestMethod -Method Post `
  -Uri 'http://localhost:8080/api/drafts/generate' `
  -Headers @{ 'X-Desk-Request' = '1' }
```

Inspect the queue:

```powershell
Invoke-RestMethod -Uri 'http://localhost:8080/api/drafts'
```

For credentials, template variables, delivery limits, and SMTP outcome handling, see the [configuration guide](CONFIGURATION.md).
