# LAA Civil Manage API

[![Ministry of Justice Repository Compliance Badge](https://github-community.service.justice.gov.uk/repository-standards/api/laa-civil-manage-api/badge?style=flat)](https://github-community.service.justice.gov.uk/repository-standards/laa-civil-manage-api)
[![API docs](https://img.shields.io/badge/API_docs_-view-85EA2D.svg?logo=swagger)](https://laa-civil-manage-api-dev.cloud-platform.service.justice.gov.uk/swagger-ui/index.html)

API to allow legal providers to manage their applications for civil legal aid.

## Local development

Requires Java 25 (managed by `mise.toml`).

This repo uses Spotless + Lefthook for local quality gates:

```bash
mise install
mise exec -- lefthook install
```

Configured hooks:

- pre-commit: `env -u JAVA_HOME ./gradlew spotlessApply spotlessCheck`
- pre-push: `env -u JAVA_HOME ./gradlew test`

The hooks explicitly unset `JAVA_HOME` to avoid failures in GUI Git clients that export `JAVA_HOME=undefined`.

Running with the `local` profile needs Entra/ADS secrets. Copy the template and fill it in — `.env`
is gitignored and loaded automatically (via `spring.config.import` in `application-local.yaml`):

```bash
cp .env.example .env             # then fill in the values; never commit .env
```

```bash
./gradlew build                  # full build + tests
./gradlew test                   # run tests only
./gradlew test --rerun-tasks     # ignore cached results
./gradlew bootRun --args='--spring.profiles.active=local' # run locally with clean logging
./gradlew generateOpenApiDocs    # regenerate openApi/*.json
./gradlew spotlessApply          # auto-format code/config/docs
./gradlew spotlessCheck          # verify formatting
```

> While running locally, you can view the API docs
> at [http://localhost:8080/swagger-ui/index.html](http://localhost:8080/swagger-ui/index.html).

Run a single test class:

```bash
./gradlew test --tests "uk.gov.justice.laa_civil_manage_api.controllers.PriorAuthorityControllerTest"
```

### Code coverage

Coverage is measured with [JaCoCo](https://docs.gradle.org/current/userguide/jacoco_plugin.html). Generate a
report locally to check your coverage before raising a PR:

```bash
./gradlew test jacocoTestReport   # run tests and generate the coverage report
```

The report is written under `build/reports/jacoco/`:

- `jacoco.xml` — machine-readable report (consumed by CI)
- `html/index.html` — open in a browser for a line-by-line breakdown

On a pull request, CI runs this report and posts a coverage summary as a PR comment. The build gates on an
overall coverage threshold (`min-coverage-overall` in `.github/workflows/deploy.yml`), so a significant drop
will be flagged there.

## Authentication

This API is fully secured using Microsoft Entra ID via OAuth 2.0.

* **Frontend API Calls:** All incoming requests must be authenticated using the **Authorization Code flow**. The
  frontend application attaches a valid user JWT (Bearer token) to the `Authorization` header of every request.
* **Downstream API Calls:** Any request that needs to interact with the downstream Access Data Store utilizes the *
  *On-Behalf-Of (OBO) flow**. The backend exchanges the user's incoming Entra token for a new token scoped specifically
  for the Data Store, ensuring strict, end-to-end user identity propagation.

*(Note: If you need to test endpoints locally without a token, you can temporarily set `SKIP_AUTH=true` in your `.env`
file).*

## CORS

CORS requires an explicit allowlist of trusted frontend origins (no wildcards). It is configured centrally in `SecurityConfig`.

Set the comma-separated allowlist per environment via `CORS_ALLOWED_ORIGINS`:

```text
CORS_ALLOWED_ORIGINS=https://laa-civil-manage-dev.cloud-platform.service.justice.gov.uk
```

- Local dev: Defaults to http://localhost:3000,http://localhost:5173 (override in .env).
- Deployed envs: Set in deploy/infrastructure/helm/values-*.yaml. An empty string ("") acts as a fail-safe, denying all cross-origin requests.
- Enforcement: Browsers block unlisted origins; non-browser server-to-server calls are unaffected.
- Request headers: Allow * to support automatically injected APM/tracing headers (e.g., AWS X-Ray).
- Exposed headers: Location and X-Correlation-ID are explicitly exposed so frontend JS can read 201 Created responses and track request IDs.

## Health checks and system alerts

- **`/actuator/health`**: Checks this app plus every downstream dependency (Access Data Store, Legal Framework API,
  Provider Details API). Returns `503`/`DOWN` if any of them fail — this is what we alert on.
- **`/actuator/health/liveness`, `/actuator/health/readiness`**: Kubernetes probes. These only check the app itself,
  ignoring downstream dependencies, so Kubernetes doesn't restart or evict pods over an outage it can't fix.

Note that most endpoints (all `/prior-authorities` and `/applications` routes) depend on the Access Data Store, so an
ADS outage still breaks most of the API even though the pod stays up — only `/expertTypes` is unaffected. Kubernetes
staying calm doesn't mean the app is fully functional; it just avoids making a bad situation worse.

See `HealthEndpointIntegrationTest` for tests that document this behaviour end-to-end.

## Example requests

All examples assume a local instance running at `http://localhost:8080`. Unless `SKIP_AUTH=true` is set locally, all
requests require a valid Entra ID token in the `Authorization` header.

### Prior authorities

The lifecycle is: create a draft -> update the draft -> upload supporting documents -> submit. `GET`/`PUT`/`POST .../submit`/
`POST .../documents` all act on the `priorAuthorityId` returned when the draft was created.

Because a prior-authority request can vary significantly based on its type, the payload relies on specific nested
objects (`expertDetails`, `counselDetails` or `disbursementDetails`) corresponding to the `priorAuthorityType`. Only
one of these should be populated at a time.

#### Create a draft — Expert

```bash
curl -i -X POST http://localhost:8080/prior-authorities \
  -H "Authorization: Bearer <token>" \
  -H 'Content-Type: application/json' \
  -d '{
    "applicationId": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
    "priorAuthorityType": "EXPERT",
    "justification": "Required for comprehensive child behavioral assessment. Costs split 4 ways.",
    "expertDetails": {
      "expertType": "Psychologist",
      "expertFullName": "Dr John Doe",
      "expertPostcode": "SW1H 9AJ",
      "expertCosts": {
        "billingType": "HOURLY",
        "hourlyRate": 50.00,
        "timeRequested": {
          "hours": 2,
          "minutes": 30
        },
        "totalAmount": 125.00,
        "costsSharedWithOtherParties": true,
        "apportionment": {
          "partiesSharingCosts": 4,
          "clientShareAmount": 31.25
        }
      }
    }
  }'
```

#### Create a draft — Counsel

```bash
curl -i -X POST http://localhost:8080/prior-authorities \
  -H "Authorization: Bearer <token>" \
  -H 'Content-Type: application/json' \
  -d '{
    "applicationId": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
    "priorAuthorityType": "COUNSEL",
    "justification": "Required highly specialized counsel for complex cross-jurisdictional elements.",
    "counselDetails": {
      "counselType": "KINGS_COUNSEL_ALONE"
    }
  }'
```

#### Create a draft — Disbursement

```bash
curl -i -X POST http://localhost:8080/prior-authorities \
  -H "Authorization: Bearer <token>" \
  -H 'Content-Type: application/json' \
  -d '{
    "applicationId": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
    "priorAuthorityType": "DISBURSEMENT",
    "justification": "Train fare required for expert to attend the client assessment in person.",
    "disbursementDetails": {
      "disbursementPurpose": "Travel",
      "disbursementAmount": 125.50
    }
  }'
```

Returns `201` with a `Location` header (`/prior-authorities/<id>`) and `{"priorAuthorityId": "..."}`. A draft can be
created with only `applicationId` populated and filled in incrementally via updates below — cross-field validation is
only enforced on `POST .../submit`.

#### Update an existing draft

```bash
curl -i -X PUT http://localhost:8080/prior-authorities/c3b07e24-d92b-410a-9d95-88f117a12b43 \
  -H "Authorization: Bearer <token>" \
  -H 'Content-Type: application/json' \
  -d '{
    "applicationId": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
    "justification": "Updated justification after case review."
  }'
```

Returns `204 No Content`.

#### Get a prior authority (draft or submitted)

```bash
curl -i http://localhost:8080/prior-authorities/c3b07e24-d92b-410a-9d95-88f117a12b43 \
  -H "Authorization: Bearer <token>"
```

Returns `200` with `{"priorAuthorityId": "...", "status": null | "PENDING" | ..., "draft": { ... }}` (`status` is
`null` while it's still a draft), or `404` if it doesn't exist.

#### Upload a supporting document

Only PDF files are accepted (validated by extension, magic bytes and detected media type).

```bash
curl -i -X POST http://localhost:8080/prior-authorities/c3b07e24-d92b-410a-9d95-88f117a12b43/documents \
  -H "Authorization: Bearer <token>" \
  -F "file=@./example.pdf"
```

Returns `200` with `{"documentId": "...", "fileName": "...", "size": ..., "uploadedAt": "..."}`.

#### Submit

Locks the draft and forwards it to the Access Data Store for validation.

```bash
curl -i -X POST http://localhost:8080/prior-authorities/c3b07e24-d92b-410a-9d95-88f117a12b43/submit \
  -H "Authorization: Bearer <token>"
```

Returns `201` with a `Location` header and `{"priorAuthorityId": "...", "submittedAt": "..."}`.

### Applications

```bash
curl -i 'http://localhost:8080/applications?page=1&pageSize=10&status=APPLICATION_GRANTED' \
  -H "Authorization: Bearer <token>"
```

`page`, `pageSize` and `status` are all optional (defaults: `page=1`, `pageSize=10`, `status=APPLICATION_GRANTED`).
`status` is one of `APPLICATION_SUBMITTED`, `APPLICATION_GRANTED`, `APPLICATION_REFUSED`.

```bash
curl -i http://localhost:8080/applications/11111111-2222-3333-4444-555555555555 \
  -H "Authorization: Bearer <token>"
```

### Expert types

Sourced from the Legal Framework API. Returns an empty list when the matter type has no associated expert types,
including when it is not a recognised matter type code.

```bash
curl -i 'http://localhost:8080/expertTypes?matterType=KPBLW' \
  -H "Authorization: Bearer <token>"
```
