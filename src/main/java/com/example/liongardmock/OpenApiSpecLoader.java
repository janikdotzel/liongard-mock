package com.example.liongardmock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class OpenApiSpecLoader {
    private final ObjectMapper yamlMapper;

    public OpenApiSpecLoader() {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
    }

    public JsonNode load(Path specPath) throws IOException {
        if (!Files.exists(specPath)) {
            throw new IOException("OpenAPI specification not found at " + specPath);
        }
        return yamlMapper.readTree(Files.newBufferedReader(specPath));
    }
}
