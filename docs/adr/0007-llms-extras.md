# ADR 0007: split optional features into llms-extras.txt

## Status
Accepted.

## Context
llms.txt is the compact API sheet for AI tools and is capped at 8000 characters and 2100 tokens (ADR 0003). The first feature wave uses most of that budget, and about eighteen more optional features are planned (compression, WebSocket, sessions, metrics, and others).

## Decision
Keep llms.txt for the core API an agent needs to build a typical server. Put optional and advanced features in llms-extras.txt, one line each under a fixed area heading, capped at 12000 characters and 3200 tokens. Both files are bundled in the jolt-core jar under META-INF and guarded by tests and token-guard.py.

## Consequences
- An agent reads about 2000 tokens for the core API and loads the extras only when a task needs them.
- Features add one line to the extras file instead of growing the core sheet.
- Area headings are fixed so parallel branches append in different places.
