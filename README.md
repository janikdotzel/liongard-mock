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

## Customising responses

- Edit any JSON file under the mock directory to return bespoke payloads. The server reloads files on every request.
- To echo a path parameter inside a response, insert a placeholder like `{{EnvironmentID}}` anywhere in the JSON; the mock engine replaces it with the incoming value.
- POST/PUT/PATCH operations ignore the request body by default and return a canned response defined in their JSON file.

## Stopping the server

Press `Ctrl+C` in the terminal that launched the server. A shutdown hook disposes of the HTTP listener immediately.

## Notes

- Endpoints without JSON responses return an empty body with the documented status code.
- Authentication and rate limiting are intentionally omitted to keep the PoC lean.
