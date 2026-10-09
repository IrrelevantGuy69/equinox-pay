package com.equinox.core.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final java.util.Optional<RateLimitInterceptor> rateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        rateLimitInterceptor.ifPresent(interceptor -> registry.addInterceptor(interceptor).addPathPatterns("/api/**"));
    }
}
