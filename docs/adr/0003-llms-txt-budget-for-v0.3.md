# ADR 0003: raise the llms.txt budget for the v0.3 feature set

## Status
Accepted.

## Context
`llms.txt` is Jolt's compact API sheet for AI tools. The guards capped it at 7000 characters and 1800 tokens. After the Express-parity and SOLID work it stands at about 6100 characters and 1650 tokens, leaving room for fewer than ten more one-line entries. The v0.3 plan adds about fifteen features (route-level middleware, validation, multipart, security headers, roles, and more).

## Decision
Raise the limits once, to 8000 characters and 2100 tokens, enough for the planned features at one line each. Existing text is still tightened before anything is added. Any further raise needs a new ADR.

## Consequences
- The full Jolt API still fits in about 2000 tokens, far below framework documentation an agent would otherwise read.
- `LlmsTxtBudgetTest` and `token-guard.py` enforce the new limits; the todo benchmark app budget (+5%) is unchanged.
