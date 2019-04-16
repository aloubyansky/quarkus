/*
 * Copyright 2018 Red Hat, Inc.
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

package io.quarkus.smallrye.opentracing.deployment;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import javax.enterprise.inject.spi.ObserverMethod;
import javax.servlet.DispatcherType;

import io.opentracing.contrib.interceptors.OpenTracingInterceptor;
import io.opentracing.contrib.jaxrs2.server.SpanFinishingFilter;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.substrate.ReflectiveMethodBuildItem;
import io.quarkus.resteasy.common.deployment.ResteasyJaxrsProviderBuildItem;
import io.quarkus.servlet.container.integration.QuarkusServletFilterBuildItem;
import io.quarkus.servlet.container.integration.QuarkusServletFilterMappingInfo;
import io.quarkus.servlet.container.integration.QuarkusServletFilterMappingType;
import io.quarkus.smallrye.opentracing.runtime.QuarkusSmallRyeTracingDynamicFeature;
import io.quarkus.smallrye.opentracing.runtime.TracerProducer;

public class SmallRyeOpenTracingProcessor {

    @BuildStep
    AdditionalBeanBuildItem registerBeans() {
        return new AdditionalBeanBuildItem(OpenTracingInterceptor.class, TracerProducer.class);
    }

    @BuildStep
    ReflectiveMethodBuildItem registerMethod() throws Exception {
        Method isAsync = ObserverMethod.class.getMethod("isAsync");
        return new ReflectiveMethodBuildItem(isAsync);
    }

    @BuildStep
    void setupFilter(BuildProducer<ResteasyJaxrsProviderBuildItem> providers,
            BuildProducer<QuarkusServletFilterBuildItem> filterProducer,
            BuildProducer<FeatureBuildItem> feature) {

        feature.produce(new FeatureBuildItem(FeatureBuildItem.SMALLRYE_OPENTRACING));

        providers.produce(new ResteasyJaxrsProviderBuildItem(QuarkusSmallRyeTracingDynamicFeature.class.getName()));

        final List<QuarkusServletFilterMappingInfo> mappings = new ArrayList<>();
        mappings.add(new QuarkusServletFilterMappingInfo(QuarkusServletFilterMappingType.URL, "*", DispatcherType.FORWARD));
        mappings.add(new QuarkusServletFilterMappingInfo(QuarkusServletFilterMappingType.URL, "*", DispatcherType.INCLUDE));
        mappings.add(new QuarkusServletFilterMappingInfo(QuarkusServletFilterMappingType.URL, "*", DispatcherType.REQUEST));
        mappings.add(new QuarkusServletFilterMappingInfo(QuarkusServletFilterMappingType.URL, "*", DispatcherType.ASYNC));
        mappings.add(new QuarkusServletFilterMappingInfo(QuarkusServletFilterMappingType.URL, "*", DispatcherType.ERROR));

        QuarkusServletFilterBuildItem filterInfo = new QuarkusServletFilterBuildItem("tracingFilter",
                SpanFinishingFilter.class.getName(), 0, true, mappings, new HashMap<>());
        filterProducer.produce(filterInfo);
    }

}
