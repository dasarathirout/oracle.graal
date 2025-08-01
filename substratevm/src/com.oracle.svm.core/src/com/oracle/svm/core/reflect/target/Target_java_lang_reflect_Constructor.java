/*
 * Copyright (c) 2017, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.core.reflect.target;

import static com.oracle.svm.core.annotate.TargetElement.CONSTRUCTOR_NAME;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;

import org.graalvm.nativeimage.ImageSingletons;

import com.oracle.svm.configure.config.ConfigurationMemberInfo;
import com.oracle.svm.configure.config.SignatureUtil;
import com.oracle.svm.core.SubstrateUtil;
import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Inject;
import com.oracle.svm.core.annotate.RecomputeFieldValue;
import com.oracle.svm.core.annotate.RecomputeFieldValue.Kind;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.annotate.TargetElement;
import com.oracle.svm.core.metadata.MetadataTracer;
import com.oracle.svm.core.reflect.MissingReflectionRegistrationUtils;

import sun.reflect.generics.repository.ConstructorRepository;

@TargetClass(value = Constructor.class)
public final class Target_java_lang_reflect_Constructor {
    /** Generic info is created on demand at run time. */
    @Alias @RecomputeFieldValue(kind = Kind.Reset) //
    private ConstructorRepository genericInfo;

    @Alias @RecomputeFieldValue(kind = Kind.Custom, declClass = AnnotationsComputer.class)//
    byte[] annotations;

    @Alias @RecomputeFieldValue(kind = Kind.Custom, declClass = ParameterAnnotationsComputer.class)//
    byte[] parameterAnnotations;

    @Alias //
    @RecomputeFieldValue(kind = Kind.Custom, declClass = ExecutableAccessorComputer.class) //
    Target_jdk_internal_reflect_ConstructorAccessor constructorAccessor;

    /**
     * We need this indirection to use {@link #acquireConstructorAccessor()} for checking if
     * run-time conditions for this method are satisfied.
     */
    @Inject //
    @RecomputeFieldValue(kind = Kind.Reset) //
    Target_jdk_internal_reflect_ConstructorAccessor constructorAccessorFromMetadata;

    @Alias
    @TargetElement(name = CONSTRUCTOR_NAME)
    @SuppressWarnings("hiding")
    native void constructor(Class<?> declaringClass, Class<?>[] parameterTypes, Class<?>[] checkedExceptions, int modifiers, int slot, String signature, byte[] annotations,
                    byte[] parameterAnnotations);

    @Alias
    native Target_java_lang_reflect_Constructor copy();

    @Substitute
    public Target_jdk_internal_reflect_ConstructorAccessor acquireConstructorAccessor() {
        if (MetadataTracer.enabled()) {
            ConstructorUtil.traceConstructorAccess(SubstrateUtil.cast(this, Executable.class));
        }
        if (constructorAccessorFromMetadata == null) {
            throw MissingReflectionRegistrationUtils.reportInvokedExecutable(SubstrateUtil.cast(this, Executable.class));
        }
        return constructorAccessorFromMetadata;
    }

    static class AnnotationsComputer extends ReflectionMetadataComputer {
        @Override
        public Object transform(Object receiver, Object originalValue) {
            return ImageSingletons.lookup(EncodedRuntimeMetadataSupplier.class).getAnnotationsEncoding((AccessibleObject) receiver);
        }
    }

    static class ParameterAnnotationsComputer extends ReflectionMetadataComputer {
        @Override
        public Object transform(Object receiver, Object originalValue) {
            return ImageSingletons.lookup(EncodedRuntimeMetadataSupplier.class).getParameterAnnotationsEncoding((Executable) receiver);
        }
    }
}

class ConstructorUtil {

    static void traceConstructorAccess(Executable ctor) {
        MetadataTracer.singleton().traceMethodAccess(ctor.getDeclaringClass(), CONSTRUCTOR_NAME, SignatureUtil.toInternalSignature(ctor.getParameterTypes()),
                        ConfigurationMemberInfo.ConfigurationMemberDeclaration.DECLARED);
    }
}
