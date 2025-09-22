package com.example.liongardmock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

public final class SchemaExampleGenerator {
    private final JsonNode specRoot;
    private final ObjectMapper jsonMapper;

    public SchemaExampleGenerator(JsonNode specRoot) {
        this.specRoot = specRoot;
        this.jsonMapper = new ObjectMapper();
    }

    public JsonNode generate(JsonNode schema) {
        if (schema == null || schema.isMissingNode() || schema.isNull()) {
            return NullNode.getInstance();
        }
        return buildExample(schema, new HashSet<>());
    }

    private JsonNode buildExample(JsonNode schema, Set<String> refStack) {
        if (schema == null || schema.isMissingNode() || schema.isNull()) {
            return NullNode.getInstance();
        }

        if (schema.has("example")) {
            return schema.get("example");
        }

        if (schema.has("default")) {
            return schema.get("default");
        }

        if (schema.has("enum") && schema.get("enum").isArray() && schema.get("enum").size() > 0) {
            return schema.get("enum").get(0);
        }

        if (schema.has("examples") && schema.get("examples").isArray() && schema.get("examples").size() > 0) {
            return schema.get("examples").get(0);
        }

        if (schema.has("$ref")) {
            String ref = schema.get("$ref").asText();
            if (refStack.contains(ref)) {
                return NullNode.getInstance();
            }
            refStack.add(ref);
            JsonNode resolved = resolveRef(ref);
            JsonNode result = buildExample(resolved, refStack);
            refStack.remove(ref);
            return result;
        }

        if (schema.has("allOf")) {
            ObjectNode combined = jsonMapper.createObjectNode();
            for (JsonNode item : schema.get("allOf")) {
                JsonNode sub = buildExample(item, refStack);
                if (sub.isObject()) {
                    combined.setAll((ObjectNode) sub);
                }
            }
            if (!combined.isEmpty()) {
                return combined;
            }
        }

        if (schema.has("oneOf")) {
            return buildExample(schema.get("oneOf").get(0), refStack);
        }

        if (schema.has("anyOf")) {
            return buildExample(schema.get("anyOf").get(0), refStack);
        }

        String type = schema.path("type").asText("");
        if (type.isEmpty()) {
            if (schema.has("properties")) {
                type = "object";
            } else if (schema.has("items")) {
                type = "array";
            }
        }

        return switch (type) {
            case "object" -> buildObjectExample(schema, refStack);
            case "array" -> buildArrayExample(schema, refStack);
            case "integer" -> jsonMapper.getNodeFactory().numberNode(0);
            case "number" -> jsonMapper.getNodeFactory().numberNode(0.0);
            case "boolean" -> jsonMapper.getNodeFactory().booleanNode(false);
            case "string" -> buildStringExample(schema);
            default -> NullNode.getInstance();
        };
    }

    private JsonNode buildObjectExample(JsonNode schema, Set<String> refStack) {
        ObjectNode objectNode = jsonMapper.createObjectNode();
        JsonNode properties = schema.get("properties");
        if (properties != null && properties.isObject()) {
            Set<String> required = new HashSet<>();
            JsonNode requiredNode = schema.get("required");
            if (requiredNode != null && requiredNode.isArray()) {
                for (JsonNode item : requiredNode) {
                    required.add(item.asText());
                }
            }

            Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String name = field.getKey();
                JsonNode propertySchema = field.getValue();
                JsonNode exampleValue = buildExample(propertySchema, refStack);
                if (!exampleValue.isNull()) {
                    objectNode.set(name, exampleValue);
                } else if (required.contains(name)) {
                    objectNode.putNull(name);
                }
            }
        }

        JsonNode additionalProperties = schema.get("additionalProperties");
        if ((objectNode.isEmpty() || schema.path("exampleAdditionalProperty").asBoolean(false)) && additionalProperties != null) {
            JsonNode additionalSchema = additionalProperties.isBoolean() ? null : additionalProperties;
            JsonNode additionalExample = additionalSchema == null ? jsonMapper.getNodeFactory().textNode("value")
                    : buildExample(additionalSchema, refStack);
            objectNode.set("key", additionalExample);
        }

        return objectNode;
    }

    private JsonNode buildArrayExample(JsonNode schema, Set<String> refStack) {
        ArrayNode arrayNode = jsonMapper.createArrayNode();
        JsonNode items = schema.get("items");
        if (items != null && !items.isMissingNode()) {
            arrayNode.add(buildExample(items, refStack));
        }
        return arrayNode;
    }

    private JsonNode buildStringExample(JsonNode schema) {
        String format = schema.path("format").asText("");
        return switch (format) {
            case "date" -> jsonMapper.getNodeFactory().textNode("1970-01-01");
            case "date-time" -> jsonMapper.getNodeFactory().textNode("1970-01-01T00:00:00Z");
            case "uuid" -> jsonMapper.getNodeFactory().textNode("00000000-0000-0000-0000-000000000000");
            case "email" -> jsonMapper.getNodeFactory().textNode("user@example.com");
            case "uri", "url" -> jsonMapper.getNodeFactory().textNode("https://example.com");
            default -> jsonMapper.getNodeFactory().textNode("string");
        };
    }

    private JsonNode resolveRef(String ref) {
        if (!ref.startsWith("#/")) {
            throw new IllegalArgumentException("Unsupported $ref format: " + ref);
        }
        String[] parts = ref.substring(2).split("/");
        JsonNode current = specRoot;
        for (String part : parts) {
            current = current.path(part);
            if (current.isMissingNode()) {
                throw new IllegalArgumentException("Could not resolve reference: " + ref);
            }
        }
        return current;
    }
}
