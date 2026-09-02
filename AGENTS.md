# AGENTS.md

## Purpose

This file contains persistent repository instructions for Codex and other coding agents.

Treat the Markdown documents in this repository as the product and engineering source of truth.
Do not silently change locked business rules, security rules, API semantics, or game probabilities.

If a user instruction conflicts with this file, follow the user's explicit instruction.
If a deeper directory later contains its own `AGENTS.md`, follow the more specific instructions for files in that directory.

---

## 1. Read Before Coding

Before making a non-trivial change, read only the documents relevant to the task.

Core references:

- `00_README.md` — project overview and locked MVP rules
- `01_PRD.md` — product requirements and business behavior
- `02_USER_FLOW.md` — customer, staff, and admin flows
- `03_ARCHITECTURE.md` — system boundaries and responsibilities
- `04_ERD.md` — entities, relationships, indexes, data rules
- `05_API_SPEC.md` — REST contract and error codes
- `06_BACKEND_GUIDE.md` — Spring Boot implementation rules
- `07_FRONTEND_GUIDE.md` — Next.js implementation rules
- `08_YUT_3D_ANIMATION_SPEC.md` — critical 3D yut animation behavior
- `09_SECURITY_AND_ABUSE.md` — PIN, QR, privacy, abuse protection
- `10_DEPLOYMENT.md` — deployment assumptions
- `11_MVP_PLAN.md` — implementation order and completion checklist

Do not load every document automatically for a tiny task.
Read the smallest set needed to perform the task correctly.

---

## 2. Locked MVP Business Rules

Unless the user explicitly changes a rule, preserve all of the following.

### Store

- Every store has its own QR code/token.
- Public URLs must not expose predictable `storeId` values.
- Store data must remain isolated by `store_id`.
- One admin account may manage multiple stores.
- Stores are created by store-owner self-signup (`POST /api/admin/auth/signup`) or by the system operator. Signup provisions the admin account, store, OWNER membership, QR token and three prizes in one transaction.

### Customer identity

- Customer provides name and phone number.
- MVP has no SMS verification.
- Phone number is the main participation identity.
- Normalize phone numbers before hashing/comparison.

### Participation cooldown

Participation identity:

`store + normalized phone number`

Cooldown:

- 2 calendar days.
- This is not an exact 48-hour timer.
- Example: participation on 2026-09-02 allows another participation starting 2026-09-04 00:00.
- Business timezone is `Asia/Seoul`.

If an unused coupon exists for the same customer/store, show that coupon before allowing a new game.

### Staff PIN

- Every store has a unique random 6-digit numeric staff PIN.
- Staff PIN is required to redeem a coupon. That is its only use.
- Game entry does NOT require staff approval: a customer with the store QR goes name/phone -> game.
  Abuse control for game entry is the 2 calendar-day cooldown and the active-coupon rule.
- Never log the plaintext PIN.
- Prefer one-way hashing for persistent PIN storage.
- PIN must be regeneratable by an authorized store admin.

### Prize tiers

There are exactly three MVP prize tiers:

- `TIER_1` = 도 / 개, shown to customers as 3등
- `TIER_2` = 걸 / 윷, shown to customers as 2등
- `TIER_3` = 모, shown to customers as 1등

A store admin configures the three prize definitions.

No prize inventory/quantity limit exists in MVP.

### Fixed probabilities

Probabilities are system-controlled and must not be editable by store admins.

- 도 `DO` = 32.5%
- 개 `GAE` = 32.5%
- 걸 `GEOL` = 12.5%
- 윷 `YUT` = 12.5%
- 모 `MO` = 10%

Equivalent tier probability:

- Tier 1 = 65%
- Tier 2 = 25%
- Tier 3 = 10%

Use `java.security.SecureRandom` or an equivalent server-side secure random generator.

Never use client-side randomness to determine a prize.

### Coupon policy

Supported redemption policies:

- `SAME_DAY`
- `NEXT_DAY`
- `ANYTIME`

Store admins configure the policy per prize.

Coupon validity:

- 90 days from issuance.
- Preserve the issued prize/policy as a coupon snapshot.
- Later prize edits must not mutate an already-issued coupon.

Coupon states:

- `ISSUED`
- `REDEEMED`
- `EXPIRED`
- `CANCELLED`

Coupon redemption must be atomic and resistant to duplicate requests.

### Review platform

MVP review target:

- Naver Place

Flow:

`customer writes review -> staff verifies review -> QR access -> identity -> game`

The application verifies staff approval, not review sentiment.
Never require a positive review, a specific rating, or favorable wording.

### Future integration

Solapi Kakao Alimtalk is not part of MVP.

Design notification boundaries so a future implementation can replace:

`NoopNotificationService`

with something such as:

`SolapiNotificationService`

without coupling coupon/game domains directly to the vendor SDK.

---

## 3. Critical Game Integrity Rules

These rules are non-negotiable unless explicitly changed by the user.

1. The backend decides and persists the yut result before the visual result is revealed.
2. The frontend never determines the actual prize.
3. Refreshing the browser must not reroll a completed/created game.
4. Retrying the same logical game request must not issue another coupon.
5. Reveal endpoints must be idempotent.
6. Game creation and coupon issuance should commit atomically.
7. The persisted backend result is the source of truth.

Expected flow:

`request -> eligibility -> server result -> persist -> animation -> reveal`

---

## 4. 3D Yut Animation Rules

The 3D animation is a primary product feature, not decorative polish.

Preferred stack:

- Three.js
- React Three Fiber
- `@react-three/drei`
- `@react-three/rapier`
- GLTF/GLB assets

Visual direction:

- mobile-game energy: 60%
- premium/minimal presentation: 40%

Required motion sequence:

`READY -> THROW -> AIR -> IMPACT -> BOUNCE -> ROLL -> SETTLE -> RESULT_LOCK -> REVEAL`

Use hybrid physics:

- early phase: real physics
- final phase: subtle deterministic orientation correction

The final visible yut faces must always match the server result.

Do not replace the required 3D experience with:
- CSS-only spinning
- a GIF
- a fixed video
- arbitrary random rotations with no physical interaction

Optimize for mobile Safari, Chrome, and Samsung Internet.

---

## 5. Backend Conventions

Preferred stack:

- Java 17
- Spring Boot 3.x
- Spring Security
- Spring Data JPA
- QueryDSL
- Gradle
- PostgreSQL 17

Recommended domain layout:

```text
common
auth
admin
store
qr
customer
game
prize
coupon
analytics
notification
```

Rules:

- Keep controllers thin.
- Put business rules in services/policies/domain objects.
- Do not expose JPA entities directly from controllers.
- Use request/response DTOs.
- Validate all public input.
- Keep transaction boundaries explicit.
- Use database constraints where they provide real integrity protection.
- Avoid N+1 queries.
- Every admin store operation must verify membership/authorization server-side.
- Never trust `storeId`, tier, result, coupon state, or staff verification supplied by the client.

Use an injectable `Clock` for cooldown/date logic where practical so tests are deterministic.

---

## 6. Frontend Conventions

Preferred stack:

- Next.js
- React
- TypeScript
- Tailwind CSS
- TanStack Query
- Zustand

Rules:

- TypeScript strictness should not be weakened to make errors disappear.
- Do not use `any` unless unavoidable and documented.
- Server state belongs primarily in TanStack Query.
- Keep Zustand for small client/session/game UI state.
- Do not persist full phone numbers in localStorage.
- Prevent repeated submission while mutations are pending.
- Render server error codes into customer-friendly Korean messages.
- Customer-facing flows are mobile-first.

Do not duplicate backend authorization or business rules as the only enforcement mechanism.
Frontend checks are UX; backend checks are authoritative.

---

## 7. API Rules

Follow `05_API_SPEC.md` unless the task explicitly changes the API.

Use a consistent error envelope.

Do not silently rename public error codes.

Important errors include:

- `STORE_NOT_FOUND`
- `STORE_INACTIVE`
- `QR_TOKEN_INVALID`
- `QR_TOKEN_REVOKED`
- `ACTIVE_COUPON_EXISTS`
- `PARTICIPATION_COOLDOWN`
- `STAFF_PIN_INVALID`
- `STAFF_PIN_RATE_LIMITED`
- `GAME_ALREADY_CREATED`
- `GAME_ALREADY_REVEALED`
- `COUPON_NOT_ACTIVE`
- `COUPON_NOT_YET_VALID`
- `COUPON_EXPIRED`
- `COUPON_ALREADY_REDEEMED`

If an API contract changes:
1. update implementation,
2. update tests,
3. update `05_API_SPEC.md`.

---

## 8. Security and Privacy

Never commit:
- secrets
- JWT keys
- database passwords
- encryption keys
- real customer phone numbers
- real staff PINs

Never log:
- plaintext phone number unless strictly required and deliberately redacted
- staff PIN
- access tokens
- encryption keys
- passwords

Phone storage model:

- normalized phone -> HMAC/hash for lookup/cooldown
- encrypted phone -> only if reversible access is actually needed
- last 4 digits -> display

Coupon and QR tokens must be unguessable random tokens.

Treat public identifiers as attacker-controlled input.

---

## 9. Database Changes

Before changing persistence:
1. inspect `04_ERD.md`,
2. preserve store isolation,
3. preserve coupon snapshot behavior,
4. preserve cooldown lookup performance.

Add indexes for actual query paths.

Do not introduce a new database, queue, cache, or migration framework simply because it is convenient.
Use the existing project choice unless the user explicitly approves an architectural change.

---

## 10. Testing Requirements

For backend changes, run the project's existing test command.
If the backend is Gradle-based and the wrapper exists, normally run:

```bash
./gradlew test
```

For frontend changes, inspect `package.json` and run the scripts that actually exist.
Typical checks may include:

```bash
npm run lint
npm run test
npm run build
```

Do not invent a command if the script does not exist.

At minimum, business-rule tests should cover:

### Participation
- same store + same phone blocked during cooldown
- participation allowed on `lastPlayedDate + 2 days`
- same phone may participate independently at another store
- active coupon takes precedence over new game

### Game
- probability boundaries
- tier mapping
- repeated request cannot reroll
- reveal is idempotent
- client cannot choose result

### Coupon
- next-day coupon blocked on issue date
- expired coupon blocked
- redeemed coupon cannot be redeemed twice
- another store's PIN cannot redeem the coupon
- simultaneous redemption succeeds once

### Security
- revoked QR rejected
- invalid staff PIN rejected
- unauthorized admin cannot access another store

---

## 11. Definition of Done

Before declaring a coding task complete:

1. implementation matches the relevant docs,
2. no locked business rule was silently changed,
3. tests relevant to the change were added or updated,
4. available lint/test/build checks were run,
5. failures are reported instead of hidden,
6. API/ERD/docs are updated if the contract changed,
7. no secret or personal data was added,
8. no unrelated refactor was mixed into the task.

For code review tasks, prioritize:
1. data isolation,
2. game integrity,
3. coupon double redemption,
4. participation cooldown correctness,
5. security/privacy,
6. mobile 3D regressions,
7. maintainability.

---

## 12. Working Style for Codex

- Make the smallest coherent change that fully solves the task.
- Inspect existing patterns before creating a new abstraction.
- Do not rewrite working modules without a reason.
- Do not silently delete features.
- Do not leave placeholder logic in a path claimed as complete.
- Mark intentional TODOs clearly.
- Explain important assumptions in the final summary.
- If documentation and code disagree, do not guess: identify the mismatch and follow the latest explicit user requirement.
- Keep documentation concise and update the actual source-of-truth file rather than duplicating large blocks of policy in new files.

When asked to implement a large feature, use the sequence in `11_MVP_PLAN.md` and complete one coherent phase at a time unless the user requests otherwise.

---

## 13. `/goal` Execution Manifest Protocol

The repository may include a long-horizon Codex goal manifest such as `작업지시.json`.

When the active `/goal` objective points to a `.json` file, names `작업지시.json`, or otherwise tells Codex to execute a goal manifest:

1. Read this `AGENTS.md` first.
2. Read the referenced JSON file in full.
3. Validate that it is parseable JSON before relying on it.
4. Read every file listed under its mandatory context before material implementation.
5. Treat the manifest's goal, locked rules, success criteria, validation rules, and stop conditions as execution instructions subordinate only to newer explicit user instructions and higher-priority system/developer policy.
6. Inspect the existing repository before scaffolding. Do not overwrite functioning code merely because the manifest describes a target layout.
7. Continue through implementation, integration, validation, and fixes. Do not interpret a long-horizon goal as a request to only produce a plan.

Codex `/goal` objectives are intentionally short. A goal may therefore be set simply to a filename. In that case, the filename is a pointer to the detailed execution manifest, not the entire instruction itself.

### JSON-only parallel subagent protocol

When spawning subagents for work governed by a goal manifest, **every subagent instruction must be provided as one valid JSON object**. Do not spawn subagents with vague free-form prompts such as `work on backend` or `review security`.

The parent/orchestrator must construct each child instruction using the schema declared by the goal manifest. If the manifest has no schema, use at least these fields:

```json
{
  "schema_version": "1.0",
  "agent_id": "unique-id",
  "role": "explorer|worker|reviewer|specialist",
  "mode": "read_only|write|review",
  "objective": "one bounded outcome",
  "context_files": [],
  "dependencies": [],
  "allowed_read_paths": [],
  "allowed_write_paths": [],
  "forbidden_write_paths": [],
  "locked_rules": [],
  "tasks": [],
  "required_checks": [],
  "deliverables": [],
  "return_format": "Return a single valid JSON handoff object.",
  "stop_conditions": []
}
```

Rules for parallel execution:

- Use subagents only when work can be meaningfully separated.
- Prefer parallel agents aggressively for read-heavy exploration, testing, triage, and review.
- Parallel write agents are allowed only when their `allowed_write_paths` do not overlap.
- Never assign the same writable file/path to two concurrently running agents.
- The primary agent owns root/shared files, dependency manifests, cross-workstream contracts, final integration, and conflict resolution unless the manifest explicitly assigns them otherwise.
- If two workstreams need the same file, serialize that work or have the parent make the shared edit.
- Read-only agents may inspect broad paths but must have an empty `allowed_write_paths` list.
- Wait for all agents in a wave before integrating work that depends on that wave.
- Each subagent must return a concise JSON handoff with status, changed files, checks, findings, blockers, and integration notes. Prefer summaries over raw terminal logs.
- The parent must inspect important diffs and verify integration independently; a child saying `completed` is not proof that the repository works.
- If a subagent fails, the parent may retry once with a refined JSON task. If it still fails, the parent takes over the missing work rather than abandoning the overall goal.
- If the goal manifest provides `agent_blueprints`, use the matching blueprint as the base for each spawned JSON instruction and fill in repository-specific paths/findings before spawning.
- If subagent tooling is unavailable, execute the same bounded workstreams sequentially in the primary thread; lack of parallelism is not a reason to stop the goal.
- Never allow a subagent to expand scope, alter locked business rules, or edit outside its declared writable paths merely to make its local task easier.

### Recommended orchestration order

For large end-to-end goals, prefer waves:

1. parallel read-only exploration,
2. parent-owned shared scaffolding/configuration,
3. parallel implementation with exclusive file ownership,
4. parent integration,
5. parallel read-only QA/security/mobile review,
6. parent fixes and final validation.

Use subagents to reduce elapsed time and context pollution, not to create uncontrolled concurrent edits.


---

## 14. Local Docker Field-Test Deployment

The immediate MVP deployment target is a **Windows development laptop running the full application through Docker Compose**, followed by real in-store testing from customer phones.

Read `12_LOCAL_FIELD_TEST.md` before changing deployment, Docker, Nginx, QR absolute URL generation, or field-test configuration.

Locked field-test rules unless the user explicitly changes them:

- The application stack must be runnable locally with Docker Compose.
- Use production-style builds for frontend/backend during field testing; do not rely only on `npm run dev` or an IDE-started backend.
- Required core services: `postgres`, `backend`, `frontend`, `nginx`.
- Provide a `field-test` path for Cloudflare Quick Tunnel. Prefer an optional `cloudflared` Compose profile while keeping host-installed `cloudflared` compatible.
- Recommended host ingress is `localhost:8088 -> nginx:80`.
- Nginx is the single application ingress. Do not publicly expose PostgreSQL, Spring Boot, or Next.js directly.
- Route frontend at `/` and backend at `/api` through the same public origin where practical.
- Field-test mode must not require AWS, RDS, S3, CloudFront, Redis, SMS, or Solapi to execute the complete MVP flow.
- Local static 3D assets are allowed for field testing; preserve the future CDN abstraction.
- Quick Tunnel is a temporary testing endpoint. Do not describe it as production hosting.
- Store QR persistence must remain token-based. Do not persist a temporary `trycloudflare.com` hostname as the store's permanent QR identity.
- A field-test QR should be generated from the current public origin plus `/s/{storeToken}` or an explicitly configured temporary public origin.
- Because Quick Tunnel hostnames may change when the tunnel restarts, field-test QR codes must be regenerated for the new session.
- Ensure proxy/forwarded headers and application URL handling work behind Cloudflare + Nginx.
- Do not weaken authentication, staff PIN security, privacy rules, or coupon integrity merely because the environment is a field test.

Before declaring the end-to-end MVP goal complete, attempt practical field-test readiness validation:

1. `docker compose ... up -d --build` succeeds,
2. containers become ready/healthy,
3. local Nginx origin responds,
4. backend health is reachable through Nginx,
5. the Cloudflare tunnel can target the Nginx origin when cloudflared is available,
6. README/docs contain exact startup and tunnel URL discovery steps,
7. no external paid infrastructure is required for this test profile.

If actual Cloudflare connectivity cannot be exercised in the current Codex environment, do not fake success. Validate the Compose/cloudflared configuration statically, document the exact command, and report external tunnel execution as the remaining local-environment check.
