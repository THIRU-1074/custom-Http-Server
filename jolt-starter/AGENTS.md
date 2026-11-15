# Agent rules

- Read [llms.txt](llms.txt) before writing code. It is the full Jolt API.
- Run `mvn -q test` after every change. Add a `JoltTest` test in `src/test/java/app` for each new route.
- Add no dependencies beyond Jolt and JUnit.
- Keep handlers short. Throw `HttpException` for errors.
- No comments that restate the code.
