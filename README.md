# Buy Together

Buying a car alone gets you the list price. Dealers do give better deals on volume, but one buyer can't bring volume.

**Buy Together** lets people who want the **same car in the same city** find each other. Someone posts *"Looking to buy a Tata Nexon in Jaipur, who's in?"*, others join, and once the group is big enough the members plan a showroom visit together and negotiate as a group.

The platform only connects people. It does not talk to dealers or handle money.

This repository is the **Spring Boot backend**.

---

## Features

- **Phone OTP login.** No passwords. The first login creates the account.
- **Create a group:** brand, model, city, target size, optional note and deadline
- **Browse groups** by city, brand, model and status, newest first, with paging
- **Join and leave** with live seat counts ("7 of 10 joined"). The group switches to `FULL` at the target and back to `OPEN` if someone leaves
- **Close** a group (creator only)
- **Auto expiry:** a nightly job expires groups whose deadline has passed
- **Members list** and **group chat**, visible to members only
- **Report** a group as spam, fake or abusive
- **Guava in-memory cache** for group pages and search results

## Tech stack

| Area | Tools |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.5 (Web, Validation, Security, Actuator) |
| Database | MongoDB (Atlas in production) |
| Cache | Google Guava `Cache` |
| Auth | Phone OTP + JWT (jjwt 0.12, HS256), stateless |
| Errors | RFC 7807 `application/problem+json` |
| Testing | JUnit 5, Mockito, AssertJ |
| DevOps | Docker (multi-stage), GitHub Actions CI |

## Design

```
            ┌───────────── Controllers ─────────────┐
            │ Auth  User  Group  Message  Report     │
            └──────┬───────────────┬────────────────┘
                   │ commands      │ queries
          ┌────────▼───────┐  ┌────▼──────────────┐
          │  GroupService  │  │ GroupQueryService │──► GroupCache (Guava)
          └────────┬───────┘  └────┬──────────────┘
                   │ evicts cache  │
          ┌────────▼───────────────▼──────────────┐
          │  Repositories (Spring Data + custom   │
          │  atomic updates with MongoTemplate)   │
          └───────────────────┬───────────────────┘
                              ▼
                           MongoDB
```

Decisions worth calling out:

- **Writes and reads are split.** `GroupService` changes groups and evicts the cache. `GroupQueryService` reads through the cache. Each class has one reason to change.
- **The cache sits behind an interface.** Services use `GroupCache`. `GuavaGroupCache` is the only class that knows about Guava, so moving to Caffeine or Redis later is a one-class change.
- **Only shared data is cached.** A cached `GroupView` is the same for every viewer. Per-user fields (`joined`, `createdByMe`) are added after the lookup, so one user's data can never leak to another through the cache.
- **Joining is safe under concurrency.** Seats are taken with a conditional `findAndModify` (`status = OPEN and memberCount < targetSize`, then `$inc`), and membership has a unique `(groupId, userId)` index. If two people race for the last seat, one wins and the other's membership row is removed again.
- **Pluggable OTP delivery (Strategy).** `OtpSender` has a logging implementation for development, chosen by `app.otp.sender`. A real SMS gateway is one new class.
- **OTPs are kept in memory, not in the database.** They're stored hashed in a Guava cache with expire-after-write, with a resend cooldown and a wrong-attempt limit.
- **`PhoneNumber` is a value object.** It accepts `98765 43210`, `+91-9876543210`, `09876543210`, always stores `+919876543210`, and masks itself in logs.
- **Time is injected.** All services use a `Clock` (IST), which keeps deadline and expiry logic testable.
- **Config is typed.** Settings are validated `@ConfigurationProperties` records, and the app refuses to start without a proper `JWT_SECRET`.

## Caching

| Cache | Key | Expiry | Max entries | Cleared when |
|---|---|---|---|---|
| Group | group id | 10 min | 5 000 | That group changes (join, leave, close, expiry) |
| Browse results | filters + page + size | 30 s | 500 | Any group changes |
| OTP codes | phone number | 5 min | 100 000 | Code used, too many attempts, or new code sent |

Chat messages, memberships and the profile are never cached.

## API

`🔒` means the request needs `Authorization: Bearer <token>`.

### Auth

| Method | Endpoint | Body |
|---|---|---|
| POST | `/api/auth/otp/send` | `{ "phone": "9876543210" }` |
| POST | `/api/auth/otp/verify` | `{ "phone": "9876543210", "otp": "123456", "name": "Anmol" }` |

`send` returns `{ "expiresInSeconds": 300, "resendAfterSeconds": 60 }`.
`verify` returns `{ "token", "expiresAt", "newUser", "user" }`. `name` is only used the first time.

### Profile

| Method | Endpoint | Body |
|---|---|---|
| GET 🔒 | `/api/users/me` | |
| PUT 🔒 | `/api/users/me` | `{ "name": "Anmol", "city": "Jaipur" }` |

### Groups

| Method | Endpoint | Notes |
|---|---|---|
| POST 🔒 | `/api/groups` | `{ "brand": "Tata", "model": "Nexon", "city": "Jaipur", "targetSize": 10, "note": "...", "deadline": "2026-12-31" }` |
| GET | `/api/groups?city=&brand=&model=&status=OPEN&page=0&size=20` | Public. Shows `joined` when logged in |
| GET | `/api/groups/{groupId}` | Public |
| GET 🔒 | `/api/groups/mine` | Groups you created or joined |
| POST 🔒 | `/api/groups/{groupId}/join` | |
| POST 🔒 | `/api/groups/{groupId}/leave` | The creator can't leave and should close instead |
| POST 🔒 | `/api/groups/{groupId}/close` | Creator only |
| GET 🔒 | `/api/groups/{groupId}/members` | Members only |

Example group:

```json
{
  "id": "6740f0c2a1b2c3d4e5f60718",
  "brand": "Tata",
  "model": "Nexon",
  "city": "Jaipur",
  "note": "Planning a visit before Diwali",
  "targetSize": 10,
  "memberCount": 7,
  "spotsLeft": 3,
  "status": "OPEN",
  "deadline": "2026-12-31",
  "createdAt": "2026-11-21T10:00:00Z",
  "joined": true,
  "createdByMe": false
}
```

### Chat and reports

| Method | Endpoint | Notes |
|---|---|---|
| GET 🔒 | `/api/groups/{groupId}/messages?page=0&size=30` | Members only, newest first |
| POST 🔒 | `/api/groups/{groupId}/messages` | `{ "text": "Saturday 11am at the MI Road showroom?" }` |
| POST 🔒 | `/api/groups/{groupId}/reports` | `{ "reason": "SPAM", "details": "..." }`, one report per user per group |

### Errors

Every error uses the same shape, with a stable `code` the frontend can switch on:

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "This group is already full",
  "code": "GROUP_FULL"
}
```

Validation errors also include `fieldErrors`, for example `{ "targetSize": "must not be null" }`.

## Configuration

| Variable | Required | Default | Purpose |
|---|---|---|---|
| `SPRING_DATA_MONGODB_URI` | Yes (in production) | test Atlas database (temporary) | MongoDB connection string. With Atlas, put `/buy_together` before the `?` |
| `JWT_SECRET` | Yes (in production) | test secret (temporary) | At least 32 characters |

> **Testing phase:** `application.yml` currently has built-in test values for the database and JWT secret so the app runs with no setup. They will be removed before going live, after which both variables become required.
| `JWT_TTL` | No | `7d` | Token lifetime |
| `CORS_ALLOWED_ORIGINS` | No | `http://localhost:3000,http://localhost:5173` | Frontend origins, comma-separated (wildcards allowed) |
| `OTP_SENDER` | No | `log` | `log` prints OTPs to the server log, for development only |
| `SERVER_PORT` | No | `8080` | HTTP port |

Group rules (size 2–50, max 3 open groups per person, deadline within 90 days) and cache sizes are in `src/main/resources/application.yml`.

## Run locally

You need Java 17 and Maven. During the testing phase no other setup is needed:

```bash
mvn spring-boot:run
```

To use your own database and secret instead:

```bash
export SPRING_DATA_MONGODB_URI="mongodb://localhost:27017/buy_together"
export JWT_SECRET="replace-with-a-long-random-secret-at-least-32-chars"
mvn spring-boot:run
```

Try the login. The OTP shows up in the server log:

```bash
curl -X POST localhost:8080/api/auth/otp/send -H "Content-Type: application/json" -d '{"phone":"9876543210"}'
curl -X POST localhost:8080/api/auth/otp/verify -H "Content-Type: application/json" \
  -d '{"phone":"9876543210","otp":"<from log>","name":"Anmol"}'
```

With Docker:

```bash
docker build -t buy-together .
docker run -p 8080:8080 \
  -e SPRING_DATA_MONGODB_URI="mongodb+srv://<user>:<password>@<cluster>/buy_together?retryWrites=true&w=majority" \
  -e JWT_SECRET="replace-with-a-long-random-secret-at-least-32-chars" \
  buy-together
```

Health check: `GET /actuator/health`

## Tests

```bash
mvn verify
```

The unit tests cover phone parsing, the OTP flow (cooldown, attempts, one-time use), JWT issue and verify, cache loading and eviction, and the group rules (seat race, duplicate join, full or closed groups, creator rules, size and deadline limits). They run without a database. GitHub Actions runs them on every push.

## Project structure

```
src/main/java/com/buytogether
├── auth            OTP send/verify, login
│   └── otp         OTP service, code generator, sender strategy
├── user            Profile
├── group           Groups: commands, queries, atomic repository ops, expiry job
│   ├── member      Memberships
│   └── cache       GroupCache interface + Guava implementation
├── chat            Group messages
├── report          Reports
├── security        JWT, filter, Spring Security setup
├── config          Clock, CORS
└── common          Errors, paging, text helpers, PhoneNumber
```

## Next up

- Real SMS sender for OTP
- WebSocket push for chat (REST polling works today)
- Polls inside a group (pick a date or showroom)
- Hide a group automatically after several reports
