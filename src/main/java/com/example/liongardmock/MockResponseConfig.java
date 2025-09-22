package com.example.liongardmock;

import java.nio.file.Path;

public record MockResponseConfig(
        int statusCode,
        String mediaType,
        Path bodyFile,
        boolean hasBody
) {
}
