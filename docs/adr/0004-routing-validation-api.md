# ADR 0004: Routing, parameter, validation and error-shape API growth

Status: accepted

## Context

Four gaps force users to hand-roll code or leave Express-shaped habits behind: guarding one route (middleware is only prefix-scoped), converting `:id` and `?page` by hand, collecting field errors, and answering with a standard error body. Constitution rules 3 and 6 require an ADR for public API growth; PRINCIPLES 6 and 8 bound how.

## Decision

1. **Route-level middleware (#20).** `get|post|put|patch|delete(path, mw, handler)` and `(path, mw, mw, handler)`, plus one-middleware forms for typed handlers, and `Middleware.then`. A chain is folded into a single `Handler` at registration, so routes without middleware pay nothing and no routing code changes. Overloads are told apart by lambda arity (`Middleware` 3, `Handler` 2, typed 1) and by `Class` arguments; tests cover lambdas and method references. Two middleware cover most routes; `then` composes longer chains instead of more overloads.
2. **Typed parameters (#13).** `Params.param(name, Class)` and `Params.query(name, Class, default)` as default methods, so `Request` and typed handlers share them. Supported: String, Integer, Long, Boolean, Double, UUID, Instant, LocalDate and enums (case-insensitive), with primitive classes accepted. A malformed value is a 400 naming the parameter and never echoing the value. Parsing uses plain JDK parsers; no reflection beyond `Class.getEnumConstants`.
3. **Validation (#22).** `Validator<T>` (with `and` for composition), `Rules` (the collector with `notBlank`, `range`, `size`, `matches`, `oneOf`, `error`), `Validate.with` (wraps a typed write handler) and `Validate.check` (inside any handler), and `ValidationException` (422, `errors()`). Plain functions, no annotations, no reflection. The default reply is JSON `{"errors":{field:message}}`; the first message per field wins.
4. **problem+json (#23).** `JoltConfig.problemJson(boolean)`, off by default. When on, framework errors, `HttpException`, validation errors and `onError` replies are `application/problem+json` with `type` (`about:blank`), `title`, `status`, `detail`, plus `errors` for validation. A mapped reply's map or record body is merged in as extension members. A 405 and other bare status replies keep their empty bodies.

## Consequences

- About 40 small public methods and four types; most are one-line overloads. llms.txt gains four lines.
- No dependencies; nothing runs on the hot path for apps that use none of it. Problem bodies are built only on the error path.
- `JoltConfig` gains a record component, so its canonical constructor changes; use the fluent copies.
