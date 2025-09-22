package com.example.liongardmock;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class LiongardMockServer {
    private static final int DEFAULT_PORT = 8080;
    private static final Path DEFAULT_SPEC_PATH = Paths.get("Liongard_v1_OAS.yaml");
    private static final Path DEFAULT_MOCK_DIR = Paths.get("mock-data");

    private LiongardMockServer() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parseArguments(args);
        if (options.containsKey("help")) {
            printUsage();
            return;
        }

        int port = parsePort(options.getOrDefault("port", String.valueOf(DEFAULT_PORT)));
        Path specPath = options.containsKey("spec") ? Paths.get(options.get("spec")) : DEFAULT_SPEC_PATH;
        Path mockDir = options.containsKey("mock-dir") ? Paths.get(options.get("mock-dir")) : DEFAULT_MOCK_DIR;

        OpenApiSpecLoader loader = new OpenApiSpecLoader();
        JsonNode spec = loadSpec(loader, specPath);

        MockDataGenerator generator = new MockDataGenerator(spec);
        List<RouteConfig> routes = generator.prepareMocks(mockDir);
        System.out.printf(Locale.ROOT, "Generated %d routes (%d new mock files created).%n",
                routes.size(), generator.newlyCreatedFileCount());

        MockApiServer server = new MockApiServer(port, routes);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        server.start();
        System.out.println("Press Ctrl+C to stop the mock server.");

        keepRunning();
    }

    private static Map<String, String> parseArguments(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (String arg : args) {
            if ("--help".equals(arg) || "-h".equals(arg)) {
                options.put("help", "true");
                continue;
            }
            if (arg.startsWith("--")) {
                String[] parts = arg.substring(2).split("=", 2);
                if (parts.length == 2) {
                    options.put(parts[0], parts[1]);
                }
            }
        }
        return options;
    }

    private static int parsePort(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Port must be a valid integer: " + value, ex);
        }
    }

    private static JsonNode loadSpec(OpenApiSpecLoader loader, Path specPath) throws IOException {
        System.out.printf(Locale.ROOT, "Loading OpenAPI spec from %s%n", specPath);
        return loader.load(specPath);
    }

    private static void keepRunning() {
        try {
            while (true) {
                Thread.sleep(Long.MAX_VALUE);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void printUsage() {
        System.out.println("Liongard API mock server");
        System.out.println("Usage: java -jar liongard-mock.jar [--port=PORT] [--spec=PATH_TO_OAS] [--mock-dir=PATH]");
    }
}
