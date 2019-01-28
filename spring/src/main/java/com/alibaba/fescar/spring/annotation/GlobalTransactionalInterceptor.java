/*
 *  Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.alibaba.fescar.spring.annotation;

import java.lang.reflect.Method;

import com.alibaba.fescar.common.exception.ShouldNeverHappenException;
import com.alibaba.fescar.common.util.StringUtils;
import com.alibaba.fescar.tm.api.DefaultFailureHandlerImpl;
import com.alibaba.fescar.tm.api.FailureHandler;
import com.alibaba.fescar.tm.api.TransactionalExecutor;
import com.alibaba.fescar.tm.api.TransactionalTemplate;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GlobalTransactionalInterceptor implements MethodInterceptor {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalTransactionalInterceptor.class);

    private final TransactionalTemplate transactionalTemplate = new TransactionalTemplate();
    private final FailureHandler failureHandler;

    public GlobalTransactionalInterceptor(FailureHandler failureHandler) {
        if (null == failureHandler) {
            failureHandler = new DefaultFailureHandlerImpl();
        }
        this.failureHandler = failureHandler;
    }

    /**
     * 拦截 @GlobalTransactional注解
     * @param methodInvocation
     * @return
     * @throws Throwable
     */
    @Override
    public Object invoke(final MethodInvocation methodInvocation) throws Throwable {
        final GlobalTransactional anno = getAnnotation(methodInvocation.getMethod());
        if (anno != null) {
            try {
                // 通过transactionalTemplate 对被注解了@GlobalTransactional 的方法按分布式事务方式执行
                return transactionalTemplate.execute(new TransactionalExecutor() {
                    // 执行业务原始方法
                    @Override
                    public Object execute() throws Throwable {
                        return methodInvocation.proceed();
                    }

                    //获取@GlobalTransactional注解中的timeoutMills 属性值
                    @Override
                    public int timeout() {
                        return anno.timeoutMills();
                    }

                    //获取@GlobalTransactional注解中的name 属性值
                    @Override
                    public String name() {
                        String name = anno.name();
                        if (!StringUtils.isEmpty(name)) {
                            return name;
                        }
                        // name 属性值为空，则使用方法名
                        return formatMethod(methodInvocation.getMethod());
                    }
                });
            } catch (TransactionalExecutor.ExecutionException e) {
                TransactionalExecutor.Code code = e.getCode();
                switch (code) {
                    case RollbackDone:
                        throw e.getOriginalException();
                    case BeginFailure:
                        failureHandler.onBeginFailure(e.getTransaction(), e.getCause());
                        throw e.getCause();
                    case CommitFailure:
                        failureHandler.onCommitFailure(e.getTransaction(), e.getCause());
                        throw e.getCause();
                    case RollbackFailure:
                        failureHandler.onRollbackFailure(e.getTransaction(), e.getCause());
                        throw e.getCause();
                    default:
                        throw new ShouldNeverHappenException("Unknown TransactionalExecutor.Code: " + code);

                }
            }

        }

        //没有被注解@GlobalTransactional的方法 执行原方法
        return methodInvocation.proceed();
    }

    private GlobalTransactional getAnnotation(Method method) {
        if (method == null) {
            return null;
        }
        return method.getAnnotation(GlobalTransactional.class);
    }

    private String formatMethod(Method method) {
        StringBuilder sb = new StringBuilder();

        String methodName = method.getName();
        Class<?>[] params = method.getParameterTypes();
        sb.append(methodName);
        sb.append("(");

        int paramPos = 0;
        for (Class<?> clazz : params) {
            sb.append(clazz.getName());
            if (++paramPos < params.length) {
                sb.append(",");
            }
        }
        sb.append(")");
        return sb.toString();
    }
}
