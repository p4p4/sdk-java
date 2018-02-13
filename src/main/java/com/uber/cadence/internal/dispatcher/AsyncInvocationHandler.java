/*
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package com.uber.cadence.internal.dispatcher;

import com.uber.cadence.workflow.WFuture;

import java.lang.reflect.InvocationHandler;
import java.util.concurrent.atomic.AtomicReference;

abstract class AsyncInvocationHandler implements InvocationHandler {

    protected static final ThreadLocal<AtomicReference<WFuture>> asyncResult = new ThreadLocal<>();

    public static void initAsyncInvocation() {
        if (asyncResult.get() != null) {
            throw new IllegalStateException("already in asyncStart invocation");
        }
        asyncResult.set(new AtomicReference<>());
    }

    public static WFuture getAsyncInvocationResult() {
        try {
            AtomicReference<WFuture> reference = asyncResult.get();
            if (reference == null) {
                throw new IllegalStateException("initAsyncInvocation wasn't called");
            }
            WFuture result = reference.get();
            if (result == null) {
                throw new IllegalStateException("asyncStart result wasn't set");
            }
            return result;
        } finally {
            asyncResult.remove();
        }
    }
}
