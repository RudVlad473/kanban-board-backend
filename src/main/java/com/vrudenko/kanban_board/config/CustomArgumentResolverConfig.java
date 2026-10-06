package com.vrudenko.kanban_board.config;

import java.util.List;

import com.vrudenko.kanban_board.security.CurrentUserId;
import com.vrudenko.kanban_board.security.CurrentUserIdResolver;

import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CustomArgumentResolverConfig implements WebMvcConfigurer {

    static {
        // The id comes from the session, never from the client, so the generated OpenAPI document
        // must not publish it as a parameter. springdoc does the same for @AuthenticationPrincipal.
        SpringDocUtils.getConfig().addAnnotationsToIgnore(CurrentUserId.class);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserIdResolver());
    }
}
