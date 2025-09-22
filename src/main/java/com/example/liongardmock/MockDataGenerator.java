package com.example.liongardmock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MockDataGenerator {
    private static final Pattern PATH_PARAMETER_PATTERN = Pattern.compile("\\{([^}]+)}");
    private static final List<String> RESPONSE_PRIORITY = List.of("200", "201", "202", "204", "default");

    private final JsonNode specRoot;
    private final SchemaExampleGenerator exampleGenerator;
    private final ObjectMapper jsonMapper;
    private int createdFiles;

    public MockDataGenerator(JsonNode specRoot) {
        this.specRoot = specRoot;
        this.exampleGenerator = new SchemaExampleGenerator(specRoot);
        this.jsonMapper = new ObjectMapper();
    }

    public List<RouteConfig> prepareMocks(Path mockDataDir) throws IOException {
        createdFiles = 0;
        Files.createDirectories(mockDataDir);
        List<RouteConfig> routes = new ArrayList<>();

        JsonNode pathsNode = specRoot.path("paths");
        Iterator<Map.Entry<String, JsonNode>> pathIterator = pathsNode.fields();
        while (pathIterator.hasNext()) {
            Map.Entry<String, JsonNode> pathEntry = pathIterator.next();
            String pathTemplate = pathEntry.getKey();
            JsonNode methodsNode = pathEntry.getValue();

            Iterator<Map.Entry<String, JsonNode>> methodIterator = methodsNode.fields();
            while (methodIterator.hasNext()) {
                Map.Entry<String, JsonNode> methodEntry = methodIterator.next();
                String httpMethod = methodEntry.getKey().toUpperCase(Locale.ROOT);
                if (!isHttpMethod(httpMethod)) {
                    continue;
                }
                JsonNode operationNode = methodEntry.getValue();
                RouteConfig maybeRoute = buildRouteConfig(pathTemplate, httpMethod, operationNode, mockDataDir);
                if (maybeRoute != null) {
                    routes.add(maybeRoute);
                }
            }
        }

        return routes;
    }

    private RouteConfig buildRouteConfig(String pathTemplate,
                                         String httpMethod,
                                         JsonNode operationNode,
                                         Path mockDataDir) throws IOException {
        JsonNode responsesNode = operationNode.path("responses");
        if (responsesNode.isMissingNode() || !responsesNode.fields().hasNext()) {
            return null;
        }

        ResponseSelection selection = pickResponse(responsesNode);
        if (selection == null) {
            return null;
        }

        JsonNode responseNode = selection.responseNode();
        String statusCodeString = selection.statusCode();
        int statusCode = parseStatusCode(statusCodeString);
        JsonNode contentNode = responseNode.path("content");

        String mediaType = "application/json";
        JsonNode jsonContent = contentNode.path(mediaType);
        if ((jsonContent == null || jsonContent.isMissingNode()) && contentNode.isObject() && contentNode.fields().hasNext()) {
            Map.Entry<String, JsonNode> firstContent = contentNode.fields().next();
            mediaType = firstContent.getKey();
            jsonContent = firstContent.getValue();
        }
        boolean hasBody = false;
        Path bodyFile = null;
        if (!jsonContent.isMissingNode()) {
            JsonNode bodyExample = collectExample(jsonContent);
            if (bodyExample != null && !bodyExample.isNull()) {
                hasBody = true;
                String baseName = buildResponseBaseName(operationNode, httpMethod, pathTemplate);
                String fileName = baseName + "-" + statusCodeString.replaceAll("[^0-9A-Za-z]", "_") + ".json";
                bodyFile = mockDataDir.resolve(fileName);
                writeExampleIfAbsent(bodyFile, bodyExample);
            }
        }

        List<String> pathParameters = extractPathParameters(pathTemplate);
        MockResponseConfig responseConfig = new MockResponseConfig(statusCode, mediaType, bodyFile, hasBody);
        return new RouteConfig(httpMethod, pathTemplate, pathParameters, responseConfig);
    }

    private JsonNode collectExample(JsonNode jsonContent) {
        if (jsonContent.has("example")) {
            return jsonContent.get("example");
        }

        if (jsonContent.has("examples") && jsonContent.get("examples").isObject()) {
            Iterator<Map.Entry<String, JsonNode>> examples = jsonContent.get("examples").fields();
            if (examples.hasNext()) {
                Map.Entry<String, JsonNode> first = examples.next();
                JsonNode valueNode = first.getValue().get("value");
                if (valueNode != null) {
                    return valueNode;
                }
            }
        }

        JsonNode schema = jsonContent.get("schema");
        if (schema == null) {
            return NullNode.getInstance();
        }
        return exampleGenerator.generate(schema);
    }

    private void writeExampleIfAbsent(Path bodyFile, JsonNode bodyExample) throws IOException {
        if (Files.exists(bodyFile)) {
            return;
        }
        Files.createDirectories(bodyFile.getParent());
        jsonMapper.writerWithDefaultPrettyPrinter().writeValue(bodyFile.toFile(), bodyExample);
        createdFiles++;
    }

    private ResponseSelection pickResponse(JsonNode responsesNode) {
        Map<String, JsonNode> responses = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> iterator = responsesNode.fields();
        while (iterator.hasNext()) {
            Map.Entry<String, JsonNode> entry = iterator.next();
            responses.put(entry.getKey(), entry.getValue());
        }

        for (String preferred : RESPONSE_PRIORITY) {
            if (responses.containsKey(preferred)) {
                return new ResponseSelection(preferred, responses.get(preferred));
            }
        }

        if (!responses.isEmpty()) {
            Map.Entry<String, JsonNode> first = responses.entrySet().iterator().next();
            return new ResponseSelection(first.getKey(), first.getValue());
        }
        return null;
    }

    private boolean isHttpMethod(String maybeMethod) {
        return switch (maybeMethod) {
            case "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD" -> true;
            default -> false;
        };
    }

    private List<String> extractPathParameters(String pathTemplate) {
        Matcher matcher = PATH_PARAMETER_PATTERN.matcher(pathTemplate);
        List<String> params = new ArrayList<>();
        while (matcher.find()) {
            params.add(matcher.group(1));
        }
        return params;
    }

    private int parseStatusCode(String status) {
        if ("default".equalsIgnoreCase(status)) {
            return 200;
        }
        try {
            return Integer.parseInt(status);
        } catch (NumberFormatException ex) {
            return 200;
        }
    }

    private String buildResponseBaseName(JsonNode operationNode, String httpMethod, String pathTemplate) {
        String operationId = operationNode.path("operationId").asText("");
        if (operationId == null || operationId.isBlank()) {
            String sanitizedPath = pathTemplate.replaceAll("[^A-Za-z0-9]+", "_");
            String fallback = httpMethod + "_" + sanitizedPath;
            return fallback.replaceAll("_+", "_").replaceAll("^_", "").replaceAll("_$", "");
        }
        return operationId.replaceAll("[^A-Za-z0-9_\-]", "_");
    }

    private record ResponseSelection(String statusCode, JsonNode responseNode) {
    }

    public int newlyCreatedFileCount() {
        return createdFiles;
    }
}
