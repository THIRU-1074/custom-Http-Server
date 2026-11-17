# ADR 0016: static files and templates from the class path

## Status
Accepted.

## Context
Issue 68, found by the BookMyShow reference app. A packaged app (`java -jar`, Docker) ships its UI and templates inside its jar, but `Static.serve` and `app.views` accept only file-system `Path`s. Constitution rules 3 and 6 require an ADR for the new public methods.

## Decision
One internal abstraction, `in.jolt.internal.http.Resources`, with two roots: a directory and a class-loader prefix. `find(relative)` returns a `Resource` (name, size, modified, ETag, a ranged reader). `Static`, `res.sendFile` and `Views` use it, so MIME types, `index.html` with a 301 for directories, ETag and Last-Modified, single Range and If-Range, 304, SPA fallback, dotfile 404, traversal 403 and the size cap run on one code path for both roots.

| Public API added | Why |
| --- | --- |
| `Static.classpath(root)`, `Static.classpath(urlPrefix, root)` | Mirror `Static.serve(root)` and `serve(urlPrefix, root)`; the name says where files come from. |
| `Static.classpath(urlPrefix, root, ClassLoader)` | Plugin hosts and tests with their own loader. |
| `app.viewsClasspath(root, layout)`, `viewsClasspath(root, layout, ClassLoader)` | A `views(String, String)` overload would read as a file-system path; a public `Resources` value type would add a type and two more overloads for no extra power. `layout` may be null. |

Rules:
- The default loader is the thread context class loader, captured when the middleware or views are created, never per request.
- Lookups are direct: one `ClassLoader.getResource` per candidate path, no scanning. `file:` URLs (exploded `target/classes`) are served through the directory root, so symlink escapes stay 403 and files keep their mtime ETag. `jar:file:` URLs open the jar with `JarFile` (no verification) for metadata and again for the body, each closed at once; the JDK shares the parsed central directory with the class loader's open copy, so this is a stat and a map lookup. No `URLConnection` is connected or left open.
- Jar entries get `ETag` `W/"size-crc32"` from the central directory (a free content hash) and `Last-Modified` from the entry time when present. Directory entries may be missing, so a directory is a path whose `index.html` exists.
- Paths are split on `/`; empty segments are dropped, a `..` segment, backslash or NUL is 403, a segment starting with `.` or holding `:` is 404. Encoded separators are decoded before this check. The root itself must be non-empty and pass the same check, so the whole class path (and `.class` files or config) cannot be exposed by accident.
- Class-path templates are read once and cached for the life of the app: jar resources do not change, and `jolt-dev` restarts on recompile. File-system templates keep their mtime check.
- No cache of lookups: the issue marks it optional, and caching misses would need a bound against request-driven growth for little gain.

## Consequences
- Five public methods, no new public type, no `JoltConfig` field, nothing on the hot path of apps that do not use them.
- Only `file:` and `jar:file:` resources are served; other URL schemes (nested boot jars, `jrt:`) answer 404 and would need their own decision.
- `Response.sendFile(Path)` reads file attributes once instead of three times.
- Docs: one line under `## Response` in `llms-extras.txt`; `llms.txt` is unchanged.
