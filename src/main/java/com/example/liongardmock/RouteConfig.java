package com.example.liongardmock;

import java.nio.file.Path;
import java.util.List;

public record RouteConfig(
        String httpMethod,
        String pathTemplate,
        List<String> pathParameters,
        MockResponseConfig responseConfig
) {
}
