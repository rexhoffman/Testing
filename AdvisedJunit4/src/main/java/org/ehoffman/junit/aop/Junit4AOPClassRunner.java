/*
 * The MIT License
 * Copyright © 2015 Rex Hoffman
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.ehoffman.junit.aop;

import static org.ehoffman.advised.internal.AnnotationUtils.convertExceptionIfPossible;
import static org.ehoffman.advised.internal.AnnotationUtils.inspect;

import java.lang.annotation.Annotation;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import org.aopalliance.intercept.MethodInterceptor;
import org.ehoffman.advised.ConstraintException;
import org.ehoffman.advised.ContextAwareMethodInvocation;
import org.ehoffman.advised.ObjectFactory;
import org.ehoffman.advised.internal.ProviderAwareObjectFactoryAggregate;
import org.ehoffman.advised.internal.TestContext;
import org.junit.AssumptionViolatedException;
import org.junit.Test;
import org.junit.internal.runners.model.EachTestNotifier;
import org.junit.internal.runners.statements.InvokeMethod;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.Statement;

public class Junit4AOPClassRunner extends BlockJUnit4ClassRunner {

    private static final TestContext CONTEXT = new TestContext();

    public Junit4AOPClassRunner(final Class<?> klass) throws InitializationError {
        super(klass);
    }

    @Override
    protected void validateTestMethods(List<Throwable> errors) {
        //No Op
        //TODO: validate with the object factories?
    }

    @Override
    protected void runChild(final FrameworkMethod method, final RunNotifier notifier) {
        runContextualizedLeaf(method, notifier);
    }

    @Override
    protected List<FrameworkMethod> computeTestMethods() {
        return getTestClass().getAnnotatedMethods(Test.class);
    }
     
    private final void runContextualizedLeaf(final FrameworkMethod frameworkMethod, final RunNotifier notifier) {
        final EachTestNotifier eachNotifier = new EachTestNotifier(notifier, describeChild(frameworkMethod));
        eachNotifier.fireTestStarted();
        Statement statement = methodBlock(frameworkMethod);
        ProviderAwareObjectFactoryAggregate registrar = new ProviderAwareObjectFactoryAggregate();
        for (Annotation annotation : inspect(frameworkMethod.getMethod().getAnnotations())) {
            MethodInterceptor advice = CONTEXT.getAdviceFor(annotation);
            if (advice != null) {
                statement = advise(statement, advice, frameworkMethod.getMethod(), registrar, annotation);
            }
        }
        try {
            statement.evaluate();
        } catch (final Throwable e) {
            final ConstraintException contraintException = convertExceptionIfPossible(e, ConstraintException.class);
            if (contraintException != null) {
                eachNotifier.addFailedAssumption(new AssumptionViolatedException(contraintException.getMessage(),
                                contraintException));
            } else {
                eachNotifier.addFailure(e);
            }
        } finally {
            eachNotifier.fireTestFinished();
        }
    }

    private Statement advise(final Statement advised, final MethodInterceptor advisor, final Method method,
        final ProviderAwareObjectFactoryAggregate registry, final Annotation annotation) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                advisor.invoke(new ContextAwareMethodInvocation() {
                    
                    @Override
                    public void registerObjectFactory(ObjectFactory factory) {
                        registry.register(annotation, factory);
                        
                    }
                    
                    @Override
                    public ObjectFactory getCurrentContextFactory() {
                        return registry;
                    }

                    @Override
                    public Object proceed() throws Throwable {
                        if (method.getParameterTypes().length != 0 && InvokeMethod.class.isAssignableFrom(advised.getClass())) {
                            Field testMethodField = advised.getClass().getDeclaredField("testMethod");
                            testMethodField.setAccessible(true);
                            FrameworkMethod fmethod = (FrameworkMethod) testMethodField.get(advised);
                            Field targetField = advised.getClass().getDeclaredField("target");
                            targetField.setAccessible(true);
                            Object target = targetField.get(advised);
                            fmethod.invokeExplosively(target, registry.getArgumentsFor(method));
                        } else {
                            advised.evaluate();
                        }
                        return null;
                    }

                    @Override
                    public Object getThis() {
                        return null;
                    }

                    @Override
                    public AccessibleObject getStaticPart() {
                        return null;
                    }

                    @Override
                    public Object[] getArguments() {
                        return new Object[] {};
                    }

                    @Override
                    public Method getMethod() {
                        return method;
                    }

                    @Override
                    public Annotation getTargetAnnotation() {
                        return annotation;
                    }
                });
            }
        };
    }
}