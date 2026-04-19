# LOAD_CONTEXT.md — RTP Ledger Transaction Manager

## Spring Boot + Lombok Direction
- This repository is a Spring Boot 3.2 / Java 21 project baseline.
- All ongoing implementation should follow Lombok-enabled conventions where they reduce boilerplate.
- Do not treat this as a plain Java-only style guide; apply Spring Boot layering and Lombok constructor injection defaults.

## How to Bootstrap Any AI Session on This Project

Paste this block at the start of every new AI conversation:

```
/loadcontext
Project: rtp-ledger
Read: CLAUDE.md, TODO.md, then the specific module file in .github/instructions/
I am continuing development. Do not suggest architectural changes without a STOP GATE review.
Use Spring Boot 3.2 + Lombok conventions for implementation (constructor injection, minimal boilerplate).
Current checkpoint: CP-09 Pitch Assets — COMPLETE (prototype pitch docs delivered; see TODO.md)
```

## What Each File Tells the AI

| File | Purpose |
|------|---------|
| `CLAUDE.md` | Architecture decisions, data models, critical rules |
| `TODO.md` | Current checkpoint, what is done, what is next |
| `.github/instructions/client.instructions.md` | Client module rules |
| `.github/instructions/server.instructions.md` | Server module rules |
| `.github/instructions/infra.instructions.md` | Docker, DB, K6 rules |
| `.github/copilot-instructions.md` | Global Copilot/AI rules for this repo |

## Session Hygiene Rules

- Always read `TODO.md` first to know which checkpoint is active
- Never skip a STOP GATE — if you are unsure of the current checkpoint, ask
- Do not refactor across module boundaries without Sathish approval
- Follow Lombok-first style for non-hot-path boilerplate (`@RequiredArgsConstructor`, `@Getter`, `@Builder` where appropriate)
- Chronicle Map and Chronicle Queue paths are environment-specific — never hardcode
- All balance arithmetic uses `BigDecimal` — never `double` or `float`
- CockroachDB DSN comes from environment variable `CRDB_URL` — never hardcode credentials
