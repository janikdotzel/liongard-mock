# Liongard API Mock

This project hosts a lightweight mock server for the Liongard v1 Developer API. It reads the official OpenAPI specification (`Liongard_v1_OAS.yaml`), materialises JSON response bodies, and serves them over HTTP so you can integration-test against a local surrogate for the real service.

## Requirements

- Java 21
- Maven 3.9+

## Building

```bash
mvn package
```

The build produces `target/liongard-mock-0.1.0-SNAPSHOT-jar-with-dependencies.jar`, which contains the mock server and its dependencies.

## Running the mock server

```bash
java -jar target/liongard-mock-0.1.0-SNAPSHOT-jar-with-dependencies.jar \
  --port=8080 \
  --spec=Liongard_v1_OAS.yaml \
  --mock-dir=mock-data
```

- `--port` (optional): TCP port to listen on (`8080` by default).
- `--spec` (optional): Path to the Liongard OpenAPI document.
- `--mock-dir` (optional): Folder that stores per-operation JSON payloads.

On the first run, the server inspects the OpenAPI document, generates response payloads based on the documented schemas, and writes them to `mock-data/<operation>-<status>.json`. Subsequent runs reuse the files so you can edit them to craft deterministic scenarios.

## Exercising the API

1. Start the mock (see above) and wait for the console message that it is listening on the chosen port.
2. In Postman (or any HTTP client), point the collection/base URL to `http://localhost:8080` and remove authentication, because the mock ignores auth headers.
3. Fire requests exactly as defined in the official spec. Path parameters such as `/environments/{EnvironmentID}` can use any realistic value (e.g. `/environments/123`); the mock will substitute the ID into the JSON response where placeholders like `{{EnvironmentID}}` exist.
4. If you receive a 404, double-check the method and path combination—the server only hosts routes described in the spec. Successful calls return the canned JSON payloads from `mock-data/`.
5. Tailor the responses by editing the relevant JSON files and repeat the request; the server reloads files per call, so no restart is needed.

## Customising responses

- Edit any JSON file under the mock directory to return bespoke payloads. The server reloads files on every request.
- To echo a path parameter inside a response, insert a placeholder like `{{EnvironmentID}}` anywhere in the JSON; the mock engine replaces it with the incoming value.
- POST/PUT/PATCH operations ignore the request body by default and return a canned response defined in their JSON file.

## Stopping the server

Press `Ctrl+C` in the terminal that launched the server. A shutdown hook disposes of the HTTP listener immediately.

## Notes

- Endpoints without JSON responses return an empty body with the documented status code.
- Authentication and rate limiting are intentionally omitted to keep the PoC lean.
