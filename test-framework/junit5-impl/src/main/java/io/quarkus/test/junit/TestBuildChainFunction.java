package io.quarkus.test.junit;

import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.FieldInfo;
import org.jboss.jandex.Index;
import org.jboss.jandex.Type;

import io.quarkus.bootstrap.classloading.ClassPathElement;
import io.quarkus.bootstrap.classloading.QuarkusClassLoader;
import io.quarkus.builder.BuildChainBuilder;
import io.quarkus.builder.BuildContext;
import io.quarkus.builder.BuildStep;
import io.quarkus.builder.item.BuildItem;
import io.quarkus.deployment.builditem.ApplicationClassPredicateBuildItem;
import io.quarkus.deployment.builditem.TestAnnotationBuildItem;
import io.quarkus.deployment.builditem.TestClassBeanBuildItem;
import io.quarkus.deployment.builditem.TestClassPredicateBuildItem;
import io.quarkus.test.common.PathTestHelper;
import io.quarkus.test.common.TestClassIndexer;
import io.quarkus.test.junit.buildchain.TestBuildChainCustomizerProducer;

public class TestBuildChainFunction implements Function<Map<String, Object>, List<Consumer<BuildChainBuilder>>> {

    @Override
    public List<Consumer<BuildChainBuilder>> apply(Map<String, Object> stringObjectMap) {
        Path testLocation = (Path) stringObjectMap.get(Constants.TEST_LOCATION);
        // the index was written by the extension
        Index testClassesIndex = TestClassIndexer.readIndex((Class<?>) stringObjectMap.get(Constants.TEST_CLASS));

        List<Consumer<BuildChainBuilder>> allCustomizers = new ArrayList<>(1);

        final ClassLoader targetCl = Thread.currentThread().getContextClassLoader();

        final Class<? extends BuildItem> testClassPredicateBuildItemClass;
        final Class<? extends BuildItem> applicationClassPredicateBuildItemClass;
        final Class<? extends BuildItem> testAnnotationBuildItemClass;
        final Class<? extends BuildItem> testClassBeanBuildItemClass;
        try {
            testClassPredicateBuildItemClass = (Class<? extends BuildItem>) targetCl
                    .loadClass(TestClassPredicateBuildItem.class.getName());
            applicationClassPredicateBuildItemClass = (Class<? extends BuildItem>) targetCl
                    .loadClass(ApplicationClassPredicateBuildItem.class.getName());
            testAnnotationBuildItemClass = (Class<? extends BuildItem>) targetCl
                    .loadClass(TestAnnotationBuildItem.class.getName());
            testClassBeanBuildItemClass = (Class<? extends BuildItem>) targetCl
                    .loadClass(TestClassBeanBuildItem.class.getName());
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }

        Consumer<BuildChainBuilder> defaultCustomizer = new Consumer<BuildChainBuilder>() {

            @Override
            public void accept(BuildChainBuilder buildChainBuilder) {
                buildChainBuilder.addBuildStep(new BuildStep() {
                    @Override
                    public void execute(BuildContext context) {
                        try {
                            context.produce(testClassPredicateBuildItemClass.getConstructor(Predicate.class)
                                    .newInstance(new Predicate<String>() {
                                        @Override
                                        public boolean test(String className) {
                                            return PathTestHelper.isTestClass(className,
                                                    targetCl, testLocation);
                                        }
                                    }));
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                }).produces(testClassPredicateBuildItemClass).build();
                buildChainBuilder.addBuildStep(new BuildStep() {
                    @Override
                    public void execute(BuildContext context) {
                        // we need to make sure all hot reloadable classes are application classes
                        try {
                            context.produce(applicationClassPredicateBuildItemClass.getConstructor(Predicate.class)
                                    .newInstance(new Predicate<String>() {
                                        @Override
                                        public boolean test(String s) {
                                            QuarkusClassLoader cl = (QuarkusClassLoader) targetCl;
                                            // if the class file is present in this (and not the parent) CL then it is an
                                            // application class
                                            List<ClassPathElement> res = cl
                                                    .getElementsWithResource(s.replace(".", "/") + ".class", true);
                                            return !res.isEmpty();
                                        }
                                    }));
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                }).produces(applicationClassPredicateBuildItemClass).build();
                buildChainBuilder.addBuildStep(new BuildStep() {
                    @Override
                    public void execute(BuildContext context) {
                        try {
                            context.produce(testAnnotationBuildItemClass.getConstructor(String.class)
                                    .newInstance(QuarkusTest.class.getName()));
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                }).produces(testAnnotationBuildItemClass).build();

                List<String> testClassBeans = new ArrayList<>();

                List<AnnotationInstance> extendWith = testClassesIndex.getAnnotations(DotNames.EXTEND_WITH);
                for (AnnotationInstance annotationInstance : extendWith) {
                    if (annotationInstance.target().kind() != AnnotationTarget.Kind.CLASS) {
                        continue;
                    }
                    ClassInfo classInfo = annotationInstance.target().asClass();
                    if (classInfo.isAnnotation()) {
                        continue;
                    }
                    Type[] extendsWithTypes = annotationInstance.value().asClassArray();
                    for (Type type : extendsWithTypes) {
                        if (DotNames.QUARKUS_TEST_EXTENSION.equals(type.name())) {
                            testClassBeans.add(classInfo.name().toString());
                        }
                    }
                }

                List<AnnotationInstance> registerExtension = testClassesIndex
                        .getAnnotations(DotNames.REGISTER_EXTENSION);
                for (AnnotationInstance annotationInstance : registerExtension) {
                    if (annotationInstance.target().kind() != AnnotationTarget.Kind.FIELD) {
                        continue;
                    }
                    FieldInfo fieldInfo = annotationInstance.target().asField();
                    if (DotNames.QUARKUS_TEST_EXTENSION.equals(fieldInfo.type().name())) {
                        testClassBeans.add(fieldInfo.declaringClass().name().toString());
                    }
                }

                if (!testClassBeans.isEmpty()) {
                    Constructor<? extends BuildItem> ctor;
                    try {
                        ctor = testClassBeanBuildItemClass.getConstructor(String.class);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    buildChainBuilder.addBuildStep(new BuildStep() {
                        @Override
                        public void execute(BuildContext context) {
                            for (String quarkusExtendWithTestClass : testClassBeans) {
                                try {
                                    context.produce(ctor.newInstance(quarkusExtendWithTestClass));
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        }
                    }).produces(testClassBeanBuildItemClass).build();
                }

            }
        };
        allCustomizers.add(defaultCustomizer);

        // give other extensions the ability to customize the build chain
        for (TestBuildChainCustomizerProducer testBuildChainCustomizerProducer : ServiceLoader
                .load(TestBuildChainCustomizerProducer.class, this.getClass().getClassLoader())) {
            allCustomizers.add(testBuildChainCustomizerProducer.produce(testClassesIndex));
        }

        return allCustomizers;
    }
}
