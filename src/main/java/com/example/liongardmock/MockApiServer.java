package com.example.liongardmock;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class MockApiServer {
    private final int port;
    private final List<CompiledRoute> routes;
    private final ExecutorService executor;
    private HttpServer server;

    public MockApiServer(int port, List<RouteConfig> routeConfigs) {
        this.port = port;
        this.routes = routeConfigs.stream().map(CompiledRoute::new).toList();
        this.executor = Executors.newCachedThreadPool();
    }

    public void start() throws IOException {
        if (routes.isEmpty()) {
            throw new IllegalStateException("No routes were generated from the OpenAPI specification.");
        }
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new RoutingHandler(routes));
        server.setExecutor(executor);
        server.start();
        System.out.printf(Locale.ROOT, "[%s] Mock API server listening on port %d with %d routes.%n",
                Instant.now(), port, routes.size());
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        executor.shutdownNow();
    }

    private static final class RoutingHandler implements HttpHandler {
        private final List<CompiledRoute> routes;

        private RoutingHandler(List<CompiledRoute> routes) {
            this.routes = routes;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try (exchange; InputStream requestBody = exchange.getRequestBody()) {
                drainRequestBody(requestBody);
                String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
                String path = exchange.getRequestURI().getPath();

                List<CompiledRoute> pathMatches = routes.stream()
                        .filter(route -> route.matchesPath(path))
                        .toList();

                if (pathMatches.isEmpty()) {
                    respondNotFound(exchange, path);
                    return;
                }

                Optional<CompiledRoute> routeMatch = pathMatches.stream()
                        .filter(route -> route.methodEquals(method))
                        .findFirst();

                if (routeMatch.isEmpty()) {
                    respondMethodNotAllowed(exchange, pathMatches);
                    return;
                }

                CompiledRoute route = routeMatch.get();
                route.respond(exchange, method, path);
            } catch (Exception ex) {
                respondWithError(exchange, ex);
            }
        }

        private void respondNotFound(HttpExchange exchange, String path) throws IOException {
            String message = "No mock route found for " + path;
            byte[] body = message.getBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(404, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        }

        private void respondMethodNotAllowed(HttpExchange exchange, List<CompiledRoute> pathMatches) throws IOException {
            Set<String> allowed = pathMatches.stream()
                    .map(route -> route.config.httpMethod())
                    .collect(Collectors.toCollection(HashSet::new));
            Headers headers = exchange.getResponseHeaders();
            headers.set("Allow", String.join(", ", allowed));
            exchange.sendResponseHeaders(405, -1);
        }

        private void respondWithError(HttpExchange exchange, Exception ex) throws IOException {
            String message = "Mock server internal error: " + ex.getMessage();
            byte[] body = message.getBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(500, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        }

        private void drainRequestBody(InputStream requestBody) throws IOException {
            requestBody.transferTo(OutputStream.nullOutputStream());
        }
    }

    private static final class CompiledRoute {
        private final RouteConfig config;
        private final Pattern pathPattern;
        private final Map<String, String> parameterGroupNames;

        private CompiledRoute(RouteConfig config) {
            this.config = Objects.requireNonNull(config);
            this.parameterGroupNames = buildGroupNames(config.pathParameters());
            this.pathPattern = Pattern.compile(toRegex(config.pathTemplate(), parameterGroupNames));
        }

        private boolean matchesPath(String path) {
            return pathPattern.matcher(path).matches();
        }

        private boolean methodEquals(String method) {
            return config.httpMethod().equalsIgnoreCase(method);
        }

        private void respond(HttpExchange exchange, String method, String path) throws IOException {
            Matcher matcher = pathPattern.matcher(path);
            if (!matcher.matches()) {
                respondWithMismatch(exchange, path);
                return;
            }
            Map<String, String> pathParams = extractParams(matcher);
            MockResponseConfig response = config.responseConfig();
            Headers headers = exchange.getResponseHeaders();
            if (response.mediaType() != null && response.hasBody()) {
                headers.set("Content-Type", response.mediaType());
            }

            boolean shouldWriteBody = response.hasBody() && !"HEAD".equalsIgnoreCase(method) && response.bodyFile() != null;
            if (shouldWriteBody) {
                byte[] payload = Files.readAllBytes(response.bodyFile());
                exchange.sendResponseHeaders(response.statusCode(), payload.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(applyParamSubstitutions(payload, pathParams));
                }
            } else {
                exchange.sendResponseHeaders(response.statusCode(), -1);
            }
        }

        private byte[] applyParamSubstitutions(byte[] payload, Map<String, String> pathParams) {
            if (pathParams.isEmpty()) {
                return payload;
            }
            String content = new String(payload, StandardCharsets.UTF_8);
            for (Map.Entry<String, String> entry : pathParams.entrySet()) {
                String placeholder = "{{" + entry.getKey() + "}}";
                content = content.replace(placeholder, entry.getValue());
            }
            return content.getBytes(StandardCharsets.UTF_8);
        }

        private Map<String, String> extractParams(Matcher matcher) {
            if (parameterGroupNames.isEmpty()) {
                return Collections.emptyMap();
            }
            Map<String, String> values = new HashMap<>();
            for (Map.Entry<String, String> entry : parameterGroupNames.entrySet()) {
                String value = matcher.group(entry.getValue());
                values.put(entry.getKey(), value);
            }
            return values;
        }

        private void respondWithMismatch(HttpExchange exchange, String path) throws IOException {
            String message = "Failed to match route for path " + path;
            byte[] body = message.getBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(500, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        }

        private static Map<String, String> buildGroupNames(List<String> parameters) {
            Map<String, String> result = new HashMap<>();
            for (String parameter : parameters) {
                String sanitized = sanitizeGroupName(parameter);
                result.put(parameter, sanitized);
            }
            return result;
        }

        private static String toRegex(String template, Map<String, String> groupNames) {
            StringBuilder regex = new StringBuilder("^");
            int index = 0;
            while (index < template.length()) {
                char ch = template.charAt(index);
                if (ch == '{') {
                    int end = template.indexOf('}', index);
                    if (end < 0) {
                        throw new IllegalArgumentException("Unmatched '{' in path template: " + template);
                    }
                    String originalName = template.substring(index + 1, end);
                    String groupName = groupNames.getOrDefault(originalName, sanitizeGroupName(originalName));
                    regex.append("(?<").append(groupName).append(">[^/]+)");
                    index = end + 1;
                } else {
                    if (".[]{}()*+-?^$|".indexOf(ch) >= 0) {
                        regex.append('\\');
                    }
                    regex.append(ch);
                    index++;
                }
            }
            regex.append("$");
            return regex.toString();
        }

        private static String sanitizeGroupName(String name) {
            StringBuilder sanitized = new StringBuilder();
            for (int i = 0; i < name.length(); i++) {
                char ch = name.charAt(i);
                if (Character.isLetterOrDigit(ch)) {
                    sanitized.append(ch);
                } else {
                    sanitized.append('_');
                }
            }
            if (sanitized.length() == 0 || !Character.isLetter(sanitized.charAt(0))) {
                sanitized.insert(0, 'p');
            }
            return sanitized.toString();
        }
    }
}
