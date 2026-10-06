package com.vrudenko.kanban_board.config;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.PathParameter;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.stereotype.Component;

/**
 * Declare every path-template variable as a required string path parameter on each operation.
 *
 * springdoc declares only the variables a handler binds with @PathVariable, so a nested
 * route whose handler binds just the leaf id published no ancestor ids and a fuzzer could not build
 * a request for it (11 of 24 operations when found).
 *
 * Decisions:
 *
 * One global customizer, not unused @PathVariable bindings or
 * class-level @Parameters: both are per-handler memory, and that memory had already lapsed on 11
 * operations. A new nested controller needs no change.
 *
 * Known hole: an added ancestor id is routing-only. Ownership is verified along the leaf's chain
 * to the user and the ancestor ids are never compared, so a mismatched boardId still
 * reaches the resource. Declaring an id as required does not make the server validate it.
 *
 * Guarded by OpenApiParameterCompletenessTest.
 */
@Component
public class PathTemplateParameterOpenApiCustomizer implements GlobalOpenApiCustomizer {

    private static final Pattern PATH_TEMPLATE_VARIABLE = Pattern.compile("\\{([^/{}]+)}");

    private static final String PATH_LOCATION = "path";

    @Override
    public void customise(OpenAPI openApi) {
        Optional.ofNullable(openApi.getPaths())
                .ifPresent(
                        paths ->
                                paths.forEach(
                                        PathTemplateParameterOpenApiCustomizer::declareMissing));
    }

    private static void declareMissing(String pathName, PathItem pathItem) {
        var templateVariables = templateVariables(pathName);
        for (var operation : pathItem.readOperations()) {
            var declared = declaredPathParameterNames(pathItem, operation);
            templateVariables.stream()
                    .filter(variable -> !declared.contains(variable))
                    .forEach(variable -> operation.addParametersItem(pathParameter(variable)));
        }
    }

    private static Set<String> templateVariables(String pathName) {
        var names = new LinkedHashSet<String>();
        var matcher = PATH_TEMPLATE_VARIABLE.matcher(pathName);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static Set<String> declaredPathParameterNames(PathItem pathItem, Operation operation) {
        return Stream.of(pathItem.getParameters(), operation.getParameters())
                .flatMap(
                        parameters ->
                                Optional.ofNullable(parameters).stream().flatMap(List::stream))
                .filter(parameter -> PATH_LOCATION.equals(parameter.getIn()))
                .map(Parameter::getName)
                .collect(Collectors.toSet());
    }

    private static PathParameter pathParameter(String name) {
        var parameter = new PathParameter();
        parameter.setName(name);
        parameter.setRequired(true);
        parameter.setSchema(new StringSchema().minLength(1));
        return parameter;
    }
}
