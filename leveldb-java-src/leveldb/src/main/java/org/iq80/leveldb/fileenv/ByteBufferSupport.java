/*
 * Copyright (C) 2011 the original author or authors.
 * See the notice.md file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.iq80.leveldb.fileenv;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;

/**
 * 手动释放 mmap，避免大文件一直被占着直到 GC。
 *
 * ## 为什么不用原版的 MethodHandle 实现
 * 原版用 `MethodHandles.lookup().findVirtual(...)` 去拿
 * `Unsafe.invokeCleaner`，D8 对此的要求是：
 *
 * ```
 * MethodHandle.invoke and MethodHandle.invokeExact are only supported
 * starting with Android O (--min-api 26)
 * ```
 *
 * 本项目的 minSdk 是 23，直接 dex 失败。
 *
 * ## 改法
 * 换成**纯反射**调用同一个方法（`Method.invoke`，不受 min-api 限制）。
 * 一次映射释放多几次反射调用的开销可以忽略 —— 它只在关闭文件时走一次。
 *
 * ## 最坏情况
 * 某些 ROM 裁掉了 `sun.misc.Unsafe`，反射拿不到。
 * 此时静默跳过：映射仍会被 GC 释放，只是晚一点。
 * 这**不影响数据正确性**，只是文件句柄多占用一会儿。
 */
final class ByteBufferSupport
{
    private static final Method INVOKE_CLEANER;
    private static final Object THE_UNSAFE;

    static {
        Method invoker = null;
        Object unsafe = null;
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            unsafe = theUnsafe.get(null);
            invoker = unsafeClass.getMethod("invokeCleaner", ByteBuffer.class);
        }
        catch (Throwable ignored) {
            // 拿不到就算了，见类注释
        }
        INVOKE_CLEANER = invoker;
        THE_UNSAFE = unsafe;
    }

    private ByteBufferSupport()
    {
    }

    public static void unmap(MappedByteBuffer buffer)
    {
        if (buffer == null || INVOKE_CLEANER == null || THE_UNSAFE == null) {
            return;
        }
        try {
            INVOKE_CLEANER.invoke(THE_UNSAFE, buffer);
        }
        catch (Throwable ignored) {
            // 释放失败不致命：交给 GC
        }
    }
}
