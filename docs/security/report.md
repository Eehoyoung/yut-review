# Security Review: yut-review

## Scope

Repository-wide static security audit with independent baseline, architecture review, focused authorization/business-logic review, focused privacy/AI/infrastructure review, and parent revalidation.

- Scan mode: repository
- Target kind: git_revision
- Target ID: target_sha256_478d28177288deff04c49df136801f1b2db1279f0b68ffab345f2145d3dd2237
- Revision: 3877b6211008004a48dce07e27724f4304b5c01d
- Inventory strategy: repository
- Included paths: .
- Excluded paths: none
- Runtime or test status: Static offline source review.
- Artifacts reviewed: Core backend production source/config, Security-sensitive frontend routes/shared API state, Docker Compose and Nginx configs, environment examples and deployment docs, tracked-file and secret-pattern inventory

Limitations and exclusions:
- Managed Deep Scan workers were unavailable.
- Bundled development skills, generated/binary artifacts, live infrastructure, runtime secrets and online dependency advisories remain partial.
- Excluded .claude/skills/\*\*: Bundled development skill assets were inventoried and pattern-searched but not audited line-by-line as application runtime.
- Excluded output/\*\* and binary artifacts: Generated PDF/PPTX/binary artifacts were not audited as executable product source.

### Scan Summary

| Field | Value |
| --- | --- |
| Scan outcome | completed |
| Reportable findings | 10 |
| Severity mix | high: 1, medium: 6, low: 3 |
| Confidence mix | high: 9, medium: 1 |
| Coverage | partial |
| Validation mode | Multi-pass source tracing with counterevidence. |

Canonical artifacts: `scan-manifest.json`, `findings.json`, and `coverage.json`. This report is a deterministic projection of those files.

## Threat Model

Multi-store Spring Boot/PostgreSQL and Next.js service behind Nginx; public QR flows issue persisted games/coupons, admins use Bearer JWT plus DB membership, field tests may use cloudflared, and optional AI sends aggregates/public labels plus admin text.

### Assets

- Store isolation
- game/coupon integrity
- customer PII
- staff PIN and bearer tokens
- admin authority
- consent evidence
- service availability
- canonical transport
- AI no-PII boundary

### Trust Boundaries

- Internet to public/signup APIs
- JWT to tenant membership
- Nginx/cloudflared to client identity
- backend to PostgreSQL
- authorized store context/text to LLM provider

### Attacker Capabilities

- Unauthenticated caller with public QR
- targeted caller knowing a phone number
- claimant of an unregistered business identifier
- malicious tenant admin
- distributed or proxy-sharing DoS actor

### Security Objectives

- Exact tenant authorization
- server-decided idempotent games
- atomic single-use redemption
- PII/PIN/secret protection
- abuse-resistant public allocation
- read-only store-bound AI
- canonical HTTPS production ingress
- service-specific consent

### Assumptions

- Static offline review; runtime secrets, TLS terminator, database ACLs, Cloudflare behavior and current advisory feeds were not verified.

## Findings

| Finding | Severity | Confidence | Detailed write-up |
| --- | --- | --- | --- |
| [Unauthenticated game creation can exhaust storage and serialize a store](#finding-1) | high | high | inline below |
| [Checked-in Compose cannot select the hardened production Nginx configuration](#finding-2) | medium | high | inline below |
| [Phone-only customer-state lookup discloses an active coupon bearer token](#finding-3) | medium | high | inline below |
| [Signup grants store-owner authority without proof of business or email control](#finding-4) | medium | high | inline below |
| [Public signup performs expensive persistent provisioning without throttling](#finding-5) | medium | high | inline below |
| [Cloudflare proxy topology collapses users into shared rate-limit buckets](#finding-6) | medium | high | inline below |
| [AI chat history bypasses the server personal-data filter](#finding-7) | medium | high | inline below |
| [Prize CSV export permits spreadsheet formula injection](#finding-8) | low | high | inline below |
| [Phone HMAC secret has no minimum-strength validation](#finding-9) | low | medium | inline below |
| [Login limiter retains unique client keys indefinitely](#finding-10) | low | high | inline below |

### Confidence Scale

| Label | Meaning |
| --- | --- |
| high | Direct evidence supports the finding with no material unresolved blocker. |
| medium | Evidence supports a plausible issue, but material runtime or reachability proof remains. |
| low | Evidence is incomplete and the item is retained only for explicit follow-up. |

<a id="finding-1"></a>

### [1] Unauthenticated game creation can exhaust storage and serialize a store

| Field | Value |
| --- | --- |
| Severity | high |
| Confidence | high |
| Confidence rationale | Validated against current source with counterevidence considered. |
| Category | resource-exhaustion |
| CWE | CWE-770 |
| Affected lines | backend/src/main/java/com/yutreview/PublicController.java:15-18, backend/src/main/java/com/yutreview/CoreServices.java:289-296, backend/src/main/java/com/yutreview/Repositories.java:19-22 |

#### Summary

A caller with a public QR token can rotate phones and idempotency keys to create unlimited games/coupons while each request holds a store-row write lock.

#### Root Cause

A caller with a public QR token can rotate phones and idempotency keys to create unlimited games/coupons while each request holds a store-row write lock.

#### Validation

Source-to-sink path independently rechecked in current revision.

#### Dataflow

A caller with a public QR token can rotate phones and idempotency keys to create unlimited games/coupons while each request holds a store-row write lock.

#### Reachability

Reachability and prerequisites are stated in the finding summary and locations.

#### Severity

**High** — The scan assigned high severity; no separate canonical severity rationale was recorded.

Additional runtime or deployment evidence could raise or lower this severity.

#### Remediation

Add durable per-store/client limits and volume ceilings, and replace the store-wide lock with a customer-key concurrency strategy.

Tests:
- Add a regression test that exercises the stated attacker path and verifies the control fails closed.

<a id="finding-2"></a>

### [2] Checked-in Compose cannot select the hardened production Nginx configuration

| Field | Value |
| --- | --- |
| Severity | medium |
| Confidence | high |
| Confidence rationale | Validated against current source with counterevidence considered. |
| Category | security-misconfiguration |
| CWE | CWE-16 |
| Affected lines | docker-compose.yml:64-69, nginx/default.conf:1-8, nginx/production.conf:1-22 |

#### Summary

The prod backend profile does not change Compose's mount of cleartext default.conf; no checked-in production override selects TLS config/certificates or HTTPS ports.

#### Root Cause

The prod backend profile does not change Compose's mount of cleartext default.conf; no checked-in production override selects TLS config/certificates or HTTPS ports.

#### Validation

Source-to-sink path independently rechecked in current revision.

#### Dataflow

The prod backend profile does not change Compose's mount of cleartext default.conf; no checked-in production override selects TLS config/certificates or HTTPS ports.

#### Reachability

Reachability and prerequisites are stated in the finding summary and locations.

#### Severity

**Medium** — The scan assigned medium severity; no separate canonical severity rationale was recorded.

Additional runtime or deployment evidence could raise or lower this severity.

#### Remediation

Add a fail-closed production Compose override/profile selecting production ingress and verified TLS termination while disabling field-test bootstrap.

Tests:
- Add a regression test that exercises the stated attacker path and verifies the control fails closed.

<a id="finding-3"></a>

### [3] Phone-only customer-state lookup discloses an active coupon bearer token

| Field | Value |
| --- | --- |
| Severity | medium |
| Confidence | high |
| Confidence rationale | Validated against current source with counterevidence considered. |
| Category | broken-access-control |
| CWE | CWE-639 |
| Affected lines | backend/src/main/java/com/yutreview/PublicController.java:13-20, backend/src/main/java/com/yutreview/CoreServices.java:238-242 |

#### Summary

The public state endpoint ignores the supplied name and returns couponToken after only a store-plus-phone lookup; redemption still requires staff PIN.

#### Root Cause

The public state endpoint ignores the supplied name and returns couponToken after only a store-plus-phone lookup; redemption still requires staff PIN.

#### Validation

Source-to-sink path independently rechecked in current revision.

#### Dataflow

The public state endpoint ignores the supplied name and returns couponToken after only a store-plus-phone lookup; redemption still requires staff PIN.

#### Reachability

Reachability and prerequisites are stated in the finding summary and locations.

#### Severity

**Medium** — The scan assigned medium severity; no separate canonical severity rationale was recorded.

Additional runtime or deployment evidence could raise or lower this severity.

#### Remediation

Return only coarse state and require a possession-bound customer session or recovery secret before returning an existing coupon token.

Tests:
- Add a regression test that exercises the stated attacker path and verifies the control fails closed.

<a id="finding-4"></a>

### [4] Signup grants store-owner authority without proof of business or email control

| Field | Value |
| --- | --- |
| Severity | medium |
| Confidence | high |
| Confidence rationale | Validated against current source with counterevidence considered. |
| Category | identity-verification |
| CWE | CWE-290 |
| Affected lines | backend/src/main/java/com/yutreview/AdminController.java:22-32, backend/src/main/java/com/yutreview/CoreServices.java:60-76, backend/src/main/java/com/yutreview/CoreServices.java:182-200 |

#### Summary

Any unauthenticated caller can claim an unregistered ten-digit business number and immediately receive OWNER membership, staff PIN and QR capability.

#### Root Cause

Any unauthenticated caller can claim an unregistered ten-digit business number and immediately receive OWNER membership, staff PIN and QR capability.

#### Validation

Source-to-sink path independently rechecked in current revision.

#### Dataflow

Any unauthenticated caller can claim an unregistered ten-digit business number and immediately receive OWNER membership, staff PIN and QR capability.

#### Reachability

Reachability and prerequisites are stated in the finding summary and locations.

#### Severity

**Medium** — The scan assigned medium severity; no separate canonical severity rationale was recorded.

Additional runtime or deployment evidence could raise or lower this severity.

#### Remediation

Preserve self-signup while adding proof-of-control or operator review/recovery before the business claim becomes authoritative.

Tests:
- Add a regression test that exercises the stated attacker path and verifies the control fails closed.

<a id="finding-5"></a>

### [5] Public signup performs expensive persistent provisioning without throttling

| Field | Value |
| --- | --- |
| Severity | medium |
| Confidence | high |
| Confidence rationale | Validated against current source with counterevidence considered. |
| Category | resource-exhaustion |
| CWE | CWE-400 |
| Affected lines | backend/src/main/java/com/yutreview/AdminController.java:31-32, backend/src/main/java/com/yutreview/CoreServices.java:60-76, backend/src/main/java/com/yutreview/StorePosterService.java:37-78 |

#### Summary

Anonymous signup performs BCrypt, creates multiple persistent rows, and renders/stores a large PNG with no limiter, challenge or capacity quota.

#### Root Cause

Anonymous signup performs BCrypt, creates multiple persistent rows, and renders/stores a large PNG with no limiter, challenge or capacity quota.

#### Validation

Source-to-sink path independently rechecked in current revision.

#### Dataflow

Anonymous signup performs BCrypt, creates multiple persistent rows, and renders/stores a large PNG with no limiter, challenge or capacity quota.

#### Reachability

Reachability and prerequisites are stated in the finding summary and locations.

#### Severity

**Medium** — The scan assigned medium severity; no separate canonical severity rationale was recorded.

Additional runtime or deployment evidence could raise or lower this severity.

#### Remediation

Add shared signup throttles/global quotas and defer heavy provisioning until verification or activation.

Tests:
- Add a regression test that exercises the stated attacker path and verifies the control fails closed.

<a id="finding-6"></a>

### [6] Cloudflare proxy topology collapses users into shared rate-limit buckets

| Field | Value |
| --- | --- |
| Severity | medium |
| Confidence | high |
| Confidence rationale | Validated against current source with counterevidence considered. |
| Category | rate-limit-denial-of-service |
| CWE | CWE-400 |
| Affected lines | docker-compose.yml:81-87, nginx/default.conf:16-23, backend/src/main/java/com/yutreview/CoreServices.java:226-236 |

#### Summary

Nginx uses its immediate peer for X-Real-IP; in the included cloudflared topology one attacker can consume login or per-store PIN budgets for unrelated users.

#### Root Cause

Nginx uses its immediate peer for X-Real-IP; in the included cloudflared topology one attacker can consume login or per-store PIN budgets for unrelated users.

#### Validation

Source-to-sink path independently rechecked in current revision.

#### Dataflow

Nginx uses its immediate peer for X-Real-IP; in the included cloudflared topology one attacker can consume login or per-store PIN budgets for unrelated users.

#### Reachability

Reachability and prerequisites are stated in the finding summary and locations.

#### Severity

**Medium** — The scan assigned medium severity; no separate canonical severity rationale was recorded.

Additional runtime or deployment evidence could raise or lower this severity.

#### Remediation

Normalize original client IP only through an explicit trusted-proxy policy and combine it with account/store-aware durable throttles.

Tests:
- Add a regression test that exercises the stated attacker path and verifies the control fails closed.

<a id="finding-7"></a>

### [7] AI chat history bypasses the server personal-data filter

| Field | Value |
| --- | --- |
| Severity | medium |
| Confidence | high |
| Confidence rationale | Validated against current source with counterevidence considered. |
| Category | sensitive-data-exposure |
| CWE | CWE-201 |
| Affected lines | backend/src/main/java/com/yutreview/AiController.java:36-93, backend/src/main/java/com/yutreview/AiService.java:188-215, backend/src/main/java/com/yutreview/LlmProvider.java:165-180 |

#### Summary

Current chat text is filtered, but client-supplied history is copied directly into provider messages and can contain PII, coupon tokens, JWTs or secrets.

#### Root Cause

Current chat text is filtered, but client-supplied history is copied directly into provider messages and can contain PII, coupon tokens, JWTs or secrets.

#### Validation

Source-to-sink path independently rechecked in current revision.

#### Dataflow

Current chat text is filtered, but client-supplied history is copied directly into provider messages and can contain PII, coupon tokens, JWTs or secrets.

#### Reachability

Reachability and prerequisites are stated in the finding summary and locations.

#### Severity

**Medium** — The scan assigned medium severity; no separate canonical severity rationale was recorded.

Additional runtime or deployment evidence could raise or lower this severity.

#### Remediation

Filter every history turn with the central no-PII/secret policy and preferably keep conversation state server-owned.

Tests:
- Add a regression test that exercises the stated attacker path and verifies the control fails closed.

<a id="finding-8"></a>

### [8] Prize CSV export permits spreadsheet formula injection

| Field | Value |
| --- | --- |
| Severity | low |
| Confidence | high |
| Confidence rationale | Validated against current source with counterevidence considered. |
| Category | csv-injection |
| CWE | CWE-1236 |
| Affected lines | backend/src/main/java/com/yutreview/AdminController.java:42-46, backend/src/main/java/com/yutreview/AnalyticsService.java:102-123 |

#### Summary

Store-controlled prize names are delimiter-escaped but leading spreadsheet formula characters remain active.

#### Root Cause

Store-controlled prize names are delimiter-escaped but leading spreadsheet formula characters remain active.

#### Validation

Source-to-sink path independently rechecked in current revision.

#### Dataflow

Store-controlled prize names are delimiter-escaped but leading spreadsheet formula characters remain active.

#### Reachability

Reachability and prerequisites are stated in the finding summary and locations.

#### Severity

**Low** — The scan assigned low severity; no separate canonical severity rationale was recorded.

Additional runtime or deployment evidence could raise or lower this severity.

#### Remediation

Neutralize spreadsheet formula triggers before RFC CSV quoting and test all trigger prefixes.

Tests:
- Add a regression test that exercises the stated attacker path and verifies the control fails closed.

<a id="finding-9"></a>

### [9] Phone HMAC secret has no minimum-strength validation

| Field | Value |
| --- | --- |
| Severity | low |
| Confidence | medium |
| Confidence rationale | Validated against current source with counterevidence considered. |
| Category | weak-cryptography-configuration |
| CWE | CWE-326 |
| Affected lines | backend/src/main/java/com/yutreview/CoreServices.java:17-22, backend/src/main/resources/application.yml:17-22 |

#### Summary

PhoneService accepts any string as the HMAC key; a weak deployed value plus database access enables offline phone enumeration.

#### Root Cause

PhoneService accepts any string as the HMAC key; a weak deployed value plus database access enables offline phone enumeration.

#### Validation

Source-to-sink path independently rechecked in current revision.

#### Dataflow

PhoneService accepts any string as the HMAC key; a weak deployed value plus database access enables offline phone enumeration.

#### Reachability

Reachability and prerequisites are stated in the finding summary and locations.

#### Severity

**Low** — The scan assigned low severity; no separate canonical severity rationale was recorded.

Additional runtime or deployment evidence could raise or lower this severity.

#### Remediation

Require a Base64-decoded random 32-byte HMAC key and document a safe rotation/migration procedure.

Tests:
- Add a regression test that exercises the stated attacker path and verifies the control fails closed.

<a id="finding-10"></a>

### [10] Login limiter retains unique client keys indefinitely

| Field | Value |
| --- | --- |
| Severity | low |
| Confidence | high |
| Confidence rationale | Validated against current source with counterevidence considered. |
| Category | resource-exhaustion |
| CWE | CWE-770 |
| Affected lines | backend/src/main/java/com/yutreview/AdminController.java:32, backend/src/main/java/com/yutreview/CoreServices.java:232-236 |

#### Summary

The login ConcurrentHashMap has no TTL eviction or size bound, so many distinct effective source identities can grow process memory monotonically.

#### Root Cause

The login ConcurrentHashMap has no TTL eviction or size bound, so many distinct effective source identities can grow process memory monotonically.

#### Validation

Source-to-sink path independently rechecked in current revision.

#### Dataflow

The login ConcurrentHashMap has no TTL eviction or size bound, so many distinct effective source identities can grow process memory monotonically.

#### Reachability

Reachability and prerequisites are stated in the finding summary and locations.

#### Severity

**Low** — The scan assigned low severity; no separate canonical severity rationale was recorded.

Additional runtime or deployment evidence could raise or lower this severity.

#### Remediation

Use a bounded TTL cache or shared limiter with hard cardinality limits and independent eviction.

Tests:
- Add a regression test that exercises the stated attacker path and verifies the control fails closed.

## Reviewed Surfaces

| Surface | Risk Area | Outcome | Notes |
| --- | --- | --- | --- |
| Public game and coupon APIs | not recorded | Reported | Allocation DoS and phone-only coupon token disclosure. |
| Signup, login, JWT and tenant authorization | not recorded | Reported | Signup identity/allocation and limiter findings; no cross-store IDOR. |
| PII, cryptography, retention and AI | not recorded | Reported | AES-GCM/retention and store-bound read-only tools effective; history filter bypass and HMAC validation gap. |
| Compose, Nginx and Cloudflare | not recorded | Reported | Shared client identity and production ingress selection findings. |
| Frontend rendering and exports | not recorded | Reported | CSV injection; no direct XSS sink found. |
| Required and advertising consent | not recorded | No issue found | Current externally reachable consent enforcement and service-specific append-only history confirmed. |
| Bundled development tooling | not recorded | Needs follow-up | Separate supply-chain/tooling audit recommended. |
| Live deployment and current dependency advisories | not recorded | Needs follow-up | No network advisory lookup or production runtime test. |

## Open Questions And Follow Up

- Which external production deployment selects TLS ingress, secret storage and database backup controls?
- What entropy and rotation controls protect current runtime secrets?
- Should bundled developer skills receive a separate supply-chain scan?
- Operator-controlled hardening; modifying deployment environment generally conveys broader authority and private gateways are legitimate.
  - Follow-up prompt: Review deferred unit privacy-openai-base-url and close its stated proof gap.
- No externally reachable path uses the short consent-defaulting constructors.
  - Follow-up prompt: Review deferred unit legacy-consent-constructors and close its stated proof gap.
- Privacy/usability defect without an established attacker path.
  - Follow-up prompt: Review deferred unit marketing-withdrawal-version and close its stated proof gap.
