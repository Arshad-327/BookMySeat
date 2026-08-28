package com.bookmyseat.event.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * Path-scoped, not global: the read endpoints are public and must stay that way.
     *
     * <p>The pattern is the whole allow-list. Any admin endpoint added outside
     * /api/admin/** is unguarded, so keep new write endpoints under that prefix.
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AdminRoleInterceptor())
                .addPathPatterns("/api/admin/**");
    }
}
