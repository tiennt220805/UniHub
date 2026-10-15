package com.unihub.shared.infrastructure.routing;

import com.unihub.shared.application.routing.ReadFromPrimary;
import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.AbstractPointcutAdvisor;
import org.springframework.aop.support.ComposablePointcut;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.core.Ordered;

/**
 * Applies {@link ReadFromPrimary}: forces the Master for the duration of the annotated call. It
 * runs outside the transaction interceptor, so the flag is set before the transaction takes a
 * connection.
 */
final class ReadFromPrimaryAdvisor extends AbstractPointcutAdvisor {

    private final Pointcut pointcut =
            new ComposablePointcut(new AnnotationMatchingPointcut(ReadFromPrimary.class, true))
                    .union(new AnnotationMatchingPointcut(null, ReadFromPrimary.class, true));

    private final MethodInterceptor interceptor =
            invocation -> {
                boolean previous = ReadWriteRoutingDataSource.forceMaster();
                try {
                    return invocation.proceed();
                } finally {
                    ReadWriteRoutingDataSource.restore(previous);
                }
            };

    ReadFromPrimaryAdvisor() {
        setOrder(Ordered.HIGHEST_PRECEDENCE);
    }

    @Override
    public Pointcut getPointcut() {
        return pointcut;
    }

    @Override
    public Advice getAdvice() {
        return interceptor;
    }
}
