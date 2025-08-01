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
package com.oracle.svm.hosted.reflect;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.graalvm.nativeimage.AnnotationAccess;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.c.function.CFunctionPointer;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;
import org.graalvm.nativeimage.impl.AnnotationExtractor;
import org.graalvm.nativeimage.impl.ConfigurationCondition;
import org.graalvm.nativeimage.impl.RuntimeJNIAccessSupport;
import org.graalvm.nativeimage.impl.RuntimeProxyCreationSupport;
import org.graalvm.nativeimage.impl.RuntimeReflectionSupport;
import org.graalvm.nativeimage.impl.RuntimeSerializationSupport;

import com.oracle.graal.pointsto.ObjectScanner;
import com.oracle.graal.pointsto.infrastructure.UniverseMetaAccess;
import com.oracle.graal.pointsto.meta.AnalysisMetaAccess;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisUniverse;
import com.oracle.svm.configure.ConfigurationFile;
import com.oracle.svm.configure.ReflectionConfigurationParser;
import com.oracle.svm.core.BuildPhaseProvider;
import com.oracle.svm.core.ParsingReason;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.annotate.Delete;
import com.oracle.svm.core.configure.ConfigurationFiles;
import com.oracle.svm.core.feature.AutomaticallyRegisteredFeature;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.fieldvaluetransformer.FieldValueTransformerWithAvailability;
import com.oracle.svm.core.hub.ClassForNameSupport;
import com.oracle.svm.core.hub.ClassForNameSupportFeature;
import com.oracle.svm.core.hub.DynamicHub;
import com.oracle.svm.core.meta.MethodOffset;
import com.oracle.svm.core.meta.MethodPointer;
import com.oracle.svm.core.meta.MethodRef;
import com.oracle.svm.core.reflect.ReflectionAccessorHolder;
import com.oracle.svm.core.reflect.SubstrateAccessor;
import com.oracle.svm.core.reflect.SubstrateConstructorAccessor;
import com.oracle.svm.core.reflect.SubstrateMethodAccessor;
import com.oracle.svm.core.reflect.target.ReflectionSubstitutionSupport;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.hosted.FallbackFeature;
import com.oracle.svm.hosted.FeatureImpl;
import com.oracle.svm.hosted.FeatureImpl.BeforeCompilationAccessImpl;
import com.oracle.svm.hosted.FeatureImpl.DuringAnalysisAccessImpl;
import com.oracle.svm.hosted.FeatureImpl.DuringSetupAccessImpl;
import com.oracle.svm.hosted.ImageClassLoader;
import com.oracle.svm.hosted.analysis.Inflation;
import com.oracle.svm.hosted.annotation.SubstrateAnnotationExtractor;
import com.oracle.svm.hosted.classinitialization.ClassInitializationSupport;
import com.oracle.svm.hosted.code.FactoryMethodSupport;
import com.oracle.svm.hosted.config.ConfigurationParserUtils;
import com.oracle.svm.hosted.meta.HostedField;
import com.oracle.svm.hosted.meta.HostedMetaAccess;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.reflect.proxy.DynamicProxyFeature;
import com.oracle.svm.hosted.snippets.ReflectionPlugins;
import com.oracle.svm.hosted.substitute.AnnotationSubstitutionProcessor;
import com.oracle.svm.util.ModuleSupport;
import com.oracle.svm.util.ReflectionUtil;

import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderConfiguration.Plugins;
import jdk.graal.compiler.phases.util.Providers;
import jdk.graal.compiler.util.Digest;
import jdk.internal.reflect.CallerSensitive;
import jdk.internal.reflect.Reflection;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaMethod;

@AutomaticallyRegisteredFeature
public class ReflectionFeature implements InternalFeature, ReflectionSubstitutionSupport {

    /**
     * The CallerSensitiveAdapter mechanism of the JDK is a formalization of {@link CallerSensitive}
     * methods: the "caller sensitive adapter for a {@link CallerSensitive} method is a method with
     * the same name and same signature (except for a trailing Class parameter). When a
     * {@link CallerSensitive} method is invoked via reflection or a method handle, then the adapter
     * is invoked instead and the caller class is passed in explicitly. This avoids corner cases
     * where {@link Reflection#getCallerClass} returns an internal method of the reflection / method
     * handle implementation.
     */
    private static final Method findCallerSensitiveAdapterMethod = ReflectionUtil.lookupMethod(ReflectionUtil.lookupClass(false, "jdk.internal.reflect.DirectMethodHandleAccessor"),
                    "findCSMethodAdapter", Method.class);
    private static final List<Class<?>> PRIMITIVE_CLASSES = List.of(void.class, boolean.class, byte.class, short.class, char.class, int.class, long.class, float.class, double.class);

    private AnnotationSubstitutionProcessor annotationSubstitutions;

    private ReflectionDataBuilder reflectionData;
    private ImageClassLoader loader;
    private AnalysisUniverse aUniverse;
    private int loadedConfigurations;
    private UniverseMetaAccess metaAccess;

    private record AccessorKey(Executable member, Class<?> targetClass) {
    }

    final Map<AccessorKey, SubstrateAccessor> accessors = new ConcurrentHashMap<>();
    private final Map<SignatureKey, MethodRef> expandSignatureMethods = new ConcurrentHashMap<>();

    private static final Method invokePrototype = ReflectionUtil.lookupMethod(ReflectionAccessorHolder.class, "invokePrototype",
                    Object.class, Object[].class, CFunctionPointer.class);
    private static final Method invokePrototypeForCallerSensitiveAdapter = ReflectionUtil.lookupMethod(ReflectionAccessorHolder.class, "invokePrototypeForCallerSensitiveAdapter",
                    Object.class, Object[].class, CFunctionPointer.class, Class.class);
    private static final Method methodHandleInvokeErrorMethod = ReflectionUtil.lookupMethod(ReflectionAccessorHolder.class, "methodHandleInvokeError",
                    Object.class, Object[].class, CFunctionPointer.class);
    private static final Method newInstanceErrorMethod = ReflectionUtil.lookupMethod(ReflectionAccessorHolder.class, "newInstanceError",
                    Object.class, Object[].class, CFunctionPointer.class);

    FeatureImpl.BeforeAnalysisAccessImpl analysisAccess;

    @Override
    public SubstrateAccessor getOrCreateAccessor(Executable member) {
        return getOrCreateConstructorAccessor(member.getDeclaringClass(), member);
    }

    @Override
    public SubstrateAccessor getOrCreateConstructorAccessor(Class<?> targetClass, Executable member) {
        AccessorKey key = new AccessorKey(member, targetClass);
        SubstrateAccessor existing = accessors.get(key);
        if (existing != null) {
            return existing;
        }

        if (analysisAccess == null) {
            throw VMError.shouldNotReachHere("New Method or Constructor found as reachable after static analysis: " + member);
        }
        return accessors.computeIfAbsent(key, this::createAccessor);
    }

    /**
     * Creates the accessor instances for {@link SubstrateMethodAccessor invoking a method } or
     * {@link SubstrateConstructorAccessor allocating a new instance} using reflection. The accessor
     * instances use function pointer calls to invocation stubs. The invocation stubs unpack the
     * Object[] array arguments and invoke the actual method.
     *
     * The stubs are methods with manually created Graal IR:
     * {@link ReflectionExpandSignatureMethod}. Since they are only invoked via function pointers
     * and never at a normal call site, they need to be registered for compilation manually. From
     * the point of view of the static analysis, they are root methods.
     *
     * The stubs then perform another function pointer call to the actual target method. This is
     * either a direct target stored in the accessor, or a virtual target loaded from the vtable.
     *
     * {@link ConcurrentHashMap#computeIfAbsent} guarantees that this method is called only once per
     * member, so no further synchronization is necessary.
     */
    private SubstrateAccessor createAccessor(AccessorKey key) {
        Executable member = key.member;
        Class<?> targetClass = key.targetClass;
        MethodRef expandSignature;
        MethodRef directTarget = null;
        AnalysisMethod targetMethod = null;
        DynamicHub initializeBeforeInvoke = null;
        if (member instanceof Method) {
            int vtableIndex = SubstrateMethodAccessor.VTABLE_INDEX_STATICALLY_BOUND;
            Class<?> receiverType = null;
            boolean callerSensitiveAdapter = false;

            if (member.getDeclaringClass() == MethodHandle.class && (member.getName().equals("invoke") || member.getName().equals("invokeExact"))) {
                /* Method handles must not be invoked via reflection. */
                expandSignature = asMethodRef(analysisAccess.getMetaAccess().lookupJavaMethod(methodHandleInvokeErrorMethod));
            } else {
                Method target = (Method) member;
                try {
                    Method adapter = (Method) findCallerSensitiveAdapterMethod.invoke(null, member);
                    if (adapter != null) {
                        target = adapter;
                        callerSensitiveAdapter = true;
                    }
                } catch (ReflectiveOperationException ex) {
                    throw VMError.shouldNotReachHere(ex);
                }
                expandSignature = createExpandSignatureMethod(target, callerSensitiveAdapter);
                targetMethod = analysisAccess.getMetaAccess().lookupJavaMethod(target);
                /*
                 * The SubstrateMethodAccessor is also used for the implementation of MethodHandle
                 * that are created to do an invokespecial. So non-abstract instance methods have
                 * both a directTarget and a vtableIndex.
                 */
                if (!targetMethod.isAbstract()) {
                    directTarget = asMethodRef(targetMethod);
                }
                if (!targetMethod.canBeStaticallyBound()) {
                    vtableIndex = SubstrateMethodAccessor.VTABLE_INDEX_NOT_YET_COMPUTED;
                }
                if (callerSensitiveAdapter) {
                    VMError.guarantee(vtableIndex == SubstrateMethodAccessor.VTABLE_INDEX_STATICALLY_BOUND, "Caller sensitive adapters should always be statically bound %s", targetMethod);
                }
                VMError.guarantee(directTarget != null || vtableIndex != SubstrateMethodAccessor.VTABLE_INDEX_STATICALLY_BOUND, "Must have either a directTarget or a vtableIndex");
                if (!targetMethod.isStatic()) {
                    receiverType = target.getDeclaringClass();
                }
                if (targetMethod.isStatic() && !targetMethod.getDeclaringClass().isInitialized()) {
                    initializeBeforeInvoke = analysisAccess.getHostVM().dynamicHub(targetMethod.getDeclaringClass());
                }
            }
            return new SubstrateMethodAccessor(member, receiverType, expandSignature, directTarget, targetMethod, vtableIndex, initializeBeforeInvoke, callerSensitiveAdapter);

        } else {
            Class<?> holder = targetClass;
            MethodRef factoryMethodTarget = null;
            ResolvedJavaMethod factoryMethod = null;
            if (Modifier.isAbstract(holder.getModifiers()) || holder.isInterface() || holder.isPrimitive() || holder.isArray()) {
                /*
                 * Invoking the constructor of an abstract class always throws an
                 * InstantiationException. It should not be possible to get a Constructor object for
                 * an interface, array, or primitive type, but we are defensive and throw the
                 * exception in that case too.
                 */
                expandSignature = asMethodRef(analysisAccess.getMetaAccess().lookupJavaMethod(newInstanceErrorMethod));
            } else {
                expandSignature = createExpandSignatureMethod(member, false);
                targetMethod = analysisAccess.getMetaAccess().lookupJavaMethod(member);
                var aTargetClass = analysisAccess.getMetaAccess().lookupJavaType(targetClass);
                directTarget = asMethodRef(targetMethod);
                factoryMethod = FactoryMethodSupport.singleton().lookup(analysisAccess.getMetaAccess(), targetMethod, aTargetClass, false);
                factoryMethodTarget = asMethodRef(factoryMethod);
                if (!targetMethod.getDeclaringClass().isInitialized()) {
                    initializeBeforeInvoke = analysisAccess.getHostVM().dynamicHub(targetMethod.getDeclaringClass());
                }
            }
            return new SubstrateConstructorAccessor(member, expandSignature, directTarget, targetMethod, factoryMethodTarget, factoryMethod, initializeBeforeInvoke);
        }
    }

    private MethodRef createExpandSignatureMethod(Executable member, boolean callerSensitiveAdapter) {
        return expandSignatureMethods.computeIfAbsent(new SignatureKey(member, callerSensitiveAdapter), signatureKey -> {
            ResolvedJavaMethod prototype = analysisAccess.getMetaAccess().lookupJavaMethod(callerSensitiveAdapter ? invokePrototypeForCallerSensitiveAdapter : invokePrototype).getWrapped();
            return asMethodRef(new ReflectionExpandSignatureMethod("invoke_" + signatureKey.uniqueShortName(), prototype, signatureKey.isStatic, signatureKey.argTypes, signatureKey.returnKind,
                            signatureKey.callerSensitiveAdapter, member));
        });
    }

    private MethodRef asMethodRef(ResolvedJavaMethod method) {
        AnalysisMethod aMethod = (method instanceof AnalysisMethod) ? (AnalysisMethod) method : analysisAccess.getUniverse().lookup(method);
        if (SubstrateOptions.useRelativeCodePointers()) {
            return new MethodOffset(aMethod);
        } else {
            return new MethodPointer(aMethod);
        }
    }

    @Override
    public boolean isCustomSerializationConstructor(Constructor<?> reflectConstructor) {
        if (ReflectionUtil.readField(Constructor.class, "constructorAccessor", reflectConstructor) instanceof SubstrateConstructorAccessor accessor) {
            AnalysisMetaAccess analysisMetaAccess = analysisAccess.getMetaAccess();
            AnalysisMethod analysisConstructor = analysisMetaAccess.lookupJavaMethod(reflectConstructor);
            return !accessor.getFactoryMethod().equals(FactoryMethodSupport.singleton().lookup(analysisMetaAccess, analysisConstructor, analysisConstructor.getDeclaringClass(), false));
        }
        return false;
    }

    @Override
    public List<Class<? extends Feature>> getRequiredFeatures() {
        return List.of(ClassForNameSupportFeature.class, DynamicProxyFeature.class);
    }

    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        ModuleSupport.accessPackagesToClass(ModuleSupport.Access.OPEN, null, false, "java.base", "jdk.internal.reflect");

        ImageSingletons.add(ReflectionSubstitutionSupport.class, this);

        reflectionData = new ReflectionDataBuilder((SubstrateAnnotationExtractor) ImageSingletons.lookup(AnnotationExtractor.class));
        ImageSingletons.add(RuntimeReflectionSupport.class, reflectionData);
        ImageSingletons.add(ReflectionHostedSupport.class, reflectionData);

        /*
         * Querying Object members is allowed to enable these accesses on array classes, since those
         * don't define any additional members.
         */
        reflectionData.registerClassMetadata(ConfigurationCondition.alwaysTrue(), Object.class);
    }

    @Override
    public void duringSetup(DuringSetupAccess a) {
        DuringSetupAccessImpl access = (DuringSetupAccessImpl) a;
        aUniverse = access.getUniverse();
        var conditionResolver = new NativeImageConditionResolver(access.getImageClassLoader(), ClassInitializationSupport.singleton());
        reflectionData.duringSetup(access.getMetaAccess(), aUniverse);
        RuntimeProxyCreationSupport proxyRegistry = ImageSingletons.lookup(RuntimeProxyCreationSupport.class);
        RuntimeSerializationSupport<ConfigurationCondition> serializationSupport = RuntimeSerializationSupport.singleton();
        RuntimeJNIAccessSupport jniSupport = SubstrateOptions.JNI.getValue() ? ImageSingletons.lookup(RuntimeJNIAccessSupport.class) : null;
        ReflectionConfigurationParser<ConfigurationCondition, Class<?>> parser = ConfigurationParserUtils.create(ConfigurationFile.REFLECTION, true, conditionResolver, reflectionData, proxyRegistry,
                        serializationSupport, jniSupport, access.getImageClassLoader());
        loadedConfigurations = ConfigurationParserUtils.parseAndRegisterConfigurationsFromCombinedFile(parser, access.getImageClassLoader(), "reflection");
        ReflectionConfigurationParser<ConfigurationCondition, Class<?>> legacyParser = ConfigurationParserUtils.create(ConfigurationFile.REFLECTION, false, conditionResolver, reflectionData,
                        proxyRegistry, serializationSupport, jniSupport, access.getImageClassLoader());
        loadedConfigurations += ConfigurationParserUtils.parseAndRegisterConfigurations(legacyParser, access.getImageClassLoader(), "reflection",
                        ConfigurationFiles.Options.ReflectionConfigurationFiles, ConfigurationFiles.Options.ReflectionConfigurationResources,
                        ConfigurationFile.REFLECTION.getFileName());

        loader = access.getImageClassLoader();
        annotationSubstitutions = ((Inflation) access.getBigBang()).getAnnotationSubstitutionProcessor();

        /* Primitive classes cannot be accessed through Class.forName() */
        for (Class<?> primitiveClass : PRIMITIVE_CLASSES) {
            ClassForNameSupport.currentLayer().registerNegativeQuery(ConfigurationCondition.alwaysTrue(), primitiveClass.getName());
        }

        access.registerObjectReachableCallback(SubstrateAccessor.class, ReflectionFeature::onAccessorReachable);
    }

    private static void onAccessorReachable(DuringAnalysisAccess a, SubstrateAccessor accessor, ObjectScanner.ScanReason reason) {
        DuringAnalysisAccessImpl access = (DuringAnalysisAccessImpl) a;

        ResolvedJavaMethod expandSignatureMethod = accessor.getExpandSignatureMethod();
        access.registerAsRoot((AnalysisMethod) expandSignatureMethod, true, reason);

        ResolvedJavaMethod targetMethod = accessor.getTargetMethod();
        if (targetMethod != null) {
            if (!targetMethod.isAbstract()) {
                access.registerAsRoot((AnalysisMethod) targetMethod, true, reason);
            }
            /* If the accessor can be used for a virtual call, register virtual root method. */
            if (accessor instanceof SubstrateMethodAccessor mAccessor && mAccessor.getVTableIndex() != SubstrateMethodAccessor.VTABLE_INDEX_STATICALLY_BOUND) {
                access.registerAsRoot((AnalysisMethod) targetMethod, false, reason);
            }
            /* Register constructor factory method */
            if (accessor instanceof SubstrateConstructorAccessor cAccessor) {
                access.registerAsRoot((AnalysisMethod) cAccessor.getFactoryMethod(), false, reason);
            }
        }
    }

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        analysisAccess = (FeatureImpl.BeforeAnalysisAccessImpl) access;
        metaAccess = analysisAccess.getMetaAccess();
        reflectionData.beforeAnalysis(analysisAccess);
        /* duplicated to reduce the number of analysis iterations */
        reflectionData.setAnalysisAccess(access);

        /*
         * These transformers have to be registered before registering methods below which causes
         * the analysis to already see SubstrateMethodAccessor.vtableIndex.
         */
        access.registerFieldValueTransformer(ReflectionUtil.lookupField(SubstrateMethodAccessor.class, "vtableIndex"), new ComputeVTableIndex());
        access.registerFieldValueTransformer(ReflectionUtil.lookupField(SubstrateMethodAccessor.class, "interfaceTypeID"), new ComputeInterfaceTypeID());

        /* Make sure array classes don't need to be registered for reflection. */
        RuntimeReflection.register(Object.class.getDeclaredMethods());
    }

    @Override
    public void afterAnalysis(AfterAnalysisAccess access) {
        analysisAccess = null;
        reflectionData.afterAnalysis();
    }

    @Override
    public void beforeCompilation(BeforeCompilationAccess access) {
        metaAccess = ((BeforeCompilationAccessImpl) access).getMetaAccess();

        if (ImageSingletons.contains(FallbackFeature.class)) {
            FallbackFeature.FallbackImageRequest reflectionFallback = ImageSingletons.lookup(FallbackFeature.class).reflectionFallback;
            if (reflectionFallback != null && loadedConfigurations == 0) {
                throw reflectionFallback;
            }
        }
    }

    public HostedMetaAccess hostedMetaAccess() {
        return (HostedMetaAccess) metaAccess;
    }

    @Override
    public int getFieldOffset(Field field, boolean checkUnsafeAccessed) {
        VMError.guarantee(metaAccess instanceof HostedMetaAccess, "Field offsets are available only for compilation and afterwards.");

        /*
         * We have to use `optionalLookupJavaField` as fields are omitted when there is no
         * reflective access in an image.
         */
        HostedField hostedField = hostedMetaAccess().optionalLookupJavaField(field);
        if (hostedField == null || (checkUnsafeAccessed && !hostedField.wrapped.isUnsafeAccessed())) {
            return -1;
        }
        return hostedField.getLocation();
    }

    @Override
    public int getInstalledLayerNumber(Field field) {
        VMError.guarantee(metaAccess instanceof HostedMetaAccess, "Field offsets are available only for compilation and afterwards.");

        HostedField hostedField = hostedMetaAccess().lookupJavaField(field);
        return hostedField.getInstalledLayerNum();
    }

    @Override
    public String getDeletionReason(Field reflectionField) {
        ResolvedJavaField field = metaAccess.lookupJavaField(reflectionField);
        Delete annotation = AnnotationAccess.getAnnotation(field, Delete.class);
        return annotation != null ? annotation.value() : null;
    }

    @Override
    public void registerInvocationPlugins(Providers providers, Plugins plugins, ParsingReason reason) {
        FallbackFeature fallbackFeature = ImageSingletons.contains(FallbackFeature.class) ? ImageSingletons.lookup(FallbackFeature.class) : null;
        ReflectionPlugins.registerInvocationPlugins(loader, annotationSubstitutions,
                        plugins.getClassInitializationPlugin(), plugins.getInvocationPlugins(), aUniverse, reason, fallbackFeature);
    }
}

final class SignatureKey {
    final boolean isStatic;
    final Class<?>[] argTypes;
    final JavaKind returnKind;
    final boolean callerSensitiveAdapter;

    SignatureKey(Executable member, boolean callerSensitiveAdapter) {
        isStatic = member instanceof Constructor || Modifier.isStatic(member.getModifiers());
        Class<?>[] types = member.getParameterTypes();
        if (callerSensitiveAdapter) {
            /*
             * Drop the trailing Class parameter, it is provided explicitly when invoking the
             * expand-signature method.
             */
            assert types[types.length - 1] == Class.class;
            types = Arrays.copyOf(types, types.length - 1);
        }
        argTypes = types;
        if (member instanceof Method) {
            returnKind = JavaKind.fromJavaClass(((Method) member).getReturnType());
        } else {
            /* Constructor = factory method that returns the newly allocated object. */
            returnKind = JavaKind.Object;
        }
        this.callerSensitiveAdapter = callerSensitiveAdapter;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || obj.getClass() != SignatureKey.class) {
            return false;
        }
        SignatureKey other = (SignatureKey) obj;
        return isStatic == other.isStatic &&
                        Arrays.equals(argTypes, other.argTypes) &&
                        Objects.equals(returnKind, other.returnKind) &&
                        callerSensitiveAdapter == other.callerSensitiveAdapter;
    }

    @Override
    public int hashCode() {
        return Objects.hash(isStatic, Arrays.hashCode(argTypes), returnKind, callerSensitiveAdapter);
    }

    @Override
    public String toString() {
        StringBuilder fullName = new StringBuilder();
        fullName.append(isStatic);
        fullName.append("(");
        for (Class<?> c : argTypes) {
            fullName.append(c.getName()).append(",");
        }
        fullName.append(')');
        fullName.append(returnKind);
        if (callerSensitiveAdapter) {
            fullName.append(" CallerSensitiveAdapter");
        }
        return fullName.toString();
    }

    String uniqueShortName() {
        return Digest.digest(toString());
    }
}

final class ComputeVTableIndex implements FieldValueTransformerWithAvailability {
    @Override
    public boolean isAvailable() {
        return BuildPhaseProvider.isHostedUniverseBuilt();
    }

    @Override
    public Object transform(Object receiver, Object originalValue) {
        SubstrateMethodAccessor accessor = (SubstrateMethodAccessor) receiver;

        if (accessor.getVTableIndex() == SubstrateMethodAccessor.VTABLE_INDEX_NOT_YET_COMPUTED) {
            HostedMethod member = ImageSingletons.lookup(ReflectionFeature.class).hostedMetaAccess().lookupJavaMethod(accessor.getMember());
            if (member.canBeStaticallyBound()) {
                return SubstrateMethodAccessor.VTABLE_INDEX_STATICALLY_BOUND;
            }
            return member.getVTableIndex();
        } else {
            VMError.guarantee(accessor.getVTableIndex() == SubstrateMethodAccessor.VTABLE_INDEX_STATICALLY_BOUND);
            return accessor.getVTableIndex();
        }
    }
}

final class ComputeInterfaceTypeID implements FieldValueTransformerWithAvailability {
    @Override
    public boolean isAvailable() {
        return BuildPhaseProvider.isHostedUniverseBuilt();
    }

    @Override
    public Object transform(Object receiver, Object originalValue) {
        SubstrateMethodAccessor accessor = (SubstrateMethodAccessor) receiver;
        VMError.guarantee(accessor.getInterfaceTypeID() == SubstrateMethodAccessor.INTERFACE_TYPEID_NOT_YET_COMPUTED);
        if (SubstrateOptions.useClosedTypeWorldHubLayout()) {
            return SubstrateMethodAccessor.INTERFACE_TYPEID_UNNEEDED;
        }

        HostedMethod method = ImageSingletons.lookup(ReflectionFeature.class).hostedMetaAccess().lookupJavaMethod(accessor.getMember());
        HostedMethod indirectCallTarget = method.getIndirectCallTarget();
        if (indirectCallTarget.getDeclaringClass().isInterface()) {
            return indirectCallTarget.getDeclaringClass().getTypeID();
        }
        return SubstrateMethodAccessor.INTERFACE_TYPEID_CLASS_TABLE;
    }
}
