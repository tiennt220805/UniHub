package com.unihub.shared.web;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Adds {@code correlationId} to every {@link ProblemDetail} the application writes through MVC
 * (error.yaml: always present; M-17). One place instead of one per producer: the domain advice, a
 * sealed-Result variant built by {@link Problems}, and Spring's own request errors all end here, so
 * a producer added later cannot forget it. The two bodies written outside MVC, by the security
 * filters, take the value from {@code ProblemResponses}.
 */
@RestControllerAdvice
class CorrelationIdProblemAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(
            MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(
            Object body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        if (body instanceof ProblemDetail problem) {
            var correlationId = CorrelationIds.current();
            if (correlationId != null) {
                problem.setProperty(CorrelationIds.MDC_KEY, correlationId);
            }
        }
        return body;
    }
}
