package com.vrudenko.kanban_board.config;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

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
 * <p>springdoc declares only the variables a handler binds with {@code @PathVariable}.
 *
 * <p>Decisions:
 *
 * <p>One global customizer bean, not unused {@code @PathVariable} bindings or class-level
 * {@code @Parameters}: both are per-handler memory, and that memory had already lapsed on 11 of 24
 * operations, which left a fuzzer unable to build a request for the nested task and subtask routes.
 * {@link #customise(OpenAPI)} walks every path in the live document, so a new nested controller
 * needs no change. Existing parameters are never modified or removed, so a handler that binds an id
 * keeps springdoc's own declaration.
 *
 * <p>Every id in this API is a string, so the added parameter is a string with {@code minLength} 1,
 * which is what springdoc publishes for the {@code @NotBlank}-bound ids. Each inserted parameter
 * and schema is a fresh instance, so the document never shares a mutable node between operations.
 *
 * <p>Known holes: an added ancestor id is routing-only. For example, {@code
 * ColumnController.updateById} binds only {@code columnId}, and ownership is verified along the
 * leaf's chain to the user, so a mismatched {@code boardId} still reaches the column with no
 * authorization impact. Declaring the id as required does not make the server validate it.
 *
 * <p>{@link OpenApiParameterCompletenessTest} guards the result.
 */
@Component
public class PathTemplateParameterOpenApiCustomizer implements GlobalOpenApiCustomizer {

    private static final Pattern PATH_TEMPLATE_VARIABLE = Pattern.compile("\\{([^/{}]+)}");

    private static final String PATH_LOCATION = "path";

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getPaths() == null) {
            return;
        }

        openApi.getPaths()
                .forEach(
                        (pathName, pathItem) -> {
                            var templateVariables = templateVariables(pathName);
                            if (templateVariables.isEmpty()) {
                                return;
                            }
                            for (var operation : pathItem.readOperations()) {
                                var declared = declaredPathParameterNames(pathItem, operation);
                                for (var variable : templateVariables) {
                                    if (!declared.contains(variable)) {
                                        operation.addParametersItem(pathParameter(variable));
                                    }
                                }
                            }
                        });
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
        var names = new LinkedHashSet<String>();
        collectPathParameterNames(pathItem.getParameters(), names);
        collectPathParameterNames(operation.getParameters(), names);
        return names;
    }

    private static void collectPathParameterNames(List<Parameter> parameters, Set<String> names) {
        if (parameters == null) {
            return;
        }
        for (var parameter : parameters) {
            if (PATH_LOCATION.equals(parameter.getIn())) {
                names.add(parameter.getName());
            }
        }
    }

    private static PathParameter pathParameter(String name) {
        var parameter = new PathParameter();
        parameter.setName(name);
        parameter.setRequired(true);
        parameter.setSchema(new StringSchema().minLength(1));
        return parameter;
    }
}
