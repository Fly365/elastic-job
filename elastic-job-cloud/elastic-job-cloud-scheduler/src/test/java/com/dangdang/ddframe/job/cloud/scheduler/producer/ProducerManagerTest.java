/*
 * Copyright 1999-2015 dangdang.com.
 * <p>
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
 * </p>
 */

package com.dangdang.ddframe.job.cloud.scheduler.producer;

import com.dangdang.ddframe.job.cloud.scheduler.config.CloudJobConfiguration;
import com.dangdang.ddframe.job.cloud.scheduler.config.ConfigurationService;
import com.dangdang.ddframe.job.cloud.scheduler.config.JobExecutionType;
import com.dangdang.ddframe.job.cloud.scheduler.context.TaskContext;
import com.dangdang.ddframe.job.cloud.scheduler.fixture.CloudJobConfigurationBuilder;
import com.dangdang.ddframe.job.cloud.scheduler.lifecycle.LifecycleService;
import com.dangdang.ddframe.job.cloud.scheduler.state.ready.ReadyService;
import com.dangdang.ddframe.job.cloud.scheduler.state.running.RunningService;
import com.dangdang.ddframe.job.exception.JobConfigurationException;
import com.dangdang.ddframe.job.reg.base.CoordinatorRegistryCenter;
import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import org.apache.mesos.SchedulerDriver;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import org.unitils.util.ReflectionUtils;

import java.util.Arrays;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public final class ProducerManagerTest {
    
    @Mock
    private SchedulerDriver schedulerDriver;
    
    @Mock
    private CoordinatorRegistryCenter regCenter;
    
    @Mock
    private ConfigurationService configService;
   
    @Mock
    private ReadyService readyService;
    
    @Mock
    private RunningService runningService;
    
    @Mock
    private TransientProducerScheduler transientProducerScheduler;
    
    @Mock
    private LifecycleService lifecycleService;
    
    private ProducerManager producerManager;
    
    private final CloudJobConfiguration transientJobConfig = CloudJobConfigurationBuilder.createCloudJobConfiguration("transient_test_job");
    
    private final CloudJobConfiguration daemonJobConfig = CloudJobConfigurationBuilder.createCloudJobConfiguration("daemon_test_job", JobExecutionType.DAEMON);
    
    @Before
    public void setUp() throws NoSuchFieldException {
        producerManager = ProducerManagerFactory.getInstance(schedulerDriver, regCenter);
        ReflectionUtils.setFieldValue(producerManager, "configService", configService);
        ReflectionUtils.setFieldValue(producerManager, "readyService", readyService);
        ReflectionUtils.setFieldValue(producerManager, "runningService", runningService);
        ReflectionUtils.setFieldValue(producerManager, "transientProducerScheduler", transientProducerScheduler);
        ReflectionUtils.setFieldValue(producerManager, "lifecycleService", lifecycleService);
    }
    
    @Test
    public void assertStartup() {
        when(configService.loadAll()).thenReturn(Arrays.asList(transientJobConfig, daemonJobConfig));
        producerManager.startup();
        verify(configService).loadAll();
        verify(transientProducerScheduler).register(transientJobConfig);
        verify(readyService).addDaemon("daemon_test_job");
    }
    
    @Test(expected = JobConfigurationException.class)
    public void assertRegisterExisted() {
        when(configService.load("transient_test_job")).thenReturn(Optional.of(transientJobConfig));
        producerManager.register(transientJobConfig);
    }
    
    @Test
    public void assertRegisterTransientJob() {
        when(configService.load("transient_test_job")).thenReturn(Optional.<CloudJobConfiguration>absent());
        producerManager.register(transientJobConfig);
        verify(configService).add(transientJobConfig);
        verify(transientProducerScheduler).register(transientJobConfig);
    }
    
    @Test
    public void assertRegisterDaemonJob() {
        when(configService.load("daemon_test_job")).thenReturn(Optional.<CloudJobConfiguration>absent());
        producerManager.register(daemonJobConfig);
        verify(configService).add(daemonJobConfig);
        verify(readyService).addDaemon("daemon_test_job");
    }
    
    @Test(expected = JobConfigurationException.class)
    public void assertUpdateNotExisted() {
        when(configService.load("transient_test_job")).thenReturn(Optional.<CloudJobConfiguration>absent());
        producerManager.update(transientJobConfig);
    }
    
    @Test
    public void assertUpdateExisted() {
        when(configService.load("transient_test_job")).thenReturn(Optional.of(transientJobConfig));
        when(runningService.getRunningTasks("transient_test_job")).thenReturn(Arrays.asList(
                TaskContext.from("transient_test_job@-@0@-@READY@-@SLAVE-S0@-@UUID"), TaskContext.from("transient_test_job@-@1@-@READY@-@SLAVE-S0@-@UUID")));
        producerManager.update(transientJobConfig);
        verify(configService).update(transientJobConfig);
        verify(lifecycleService).killJob("transient_test_job");
        verify(runningService).remove(TaskContext.MetaInfo.from("transient_test_job@-@0"));
        verify(runningService).remove(TaskContext.MetaInfo.from("transient_test_job@-@1"));
        verify(readyService).remove(Lists.newArrayList("transient_test_job"));
    }
    
    @Test
    public void assertDeregisterNotExisted() {
        when(configService.load("transient_test_job")).thenReturn(Optional.<CloudJobConfiguration>absent());
        producerManager.deregister("transient_test_job");
        verify(configService, times(0)).remove("transient_test_job");
    }
    
    @Test
    public void assertDeregisterExisted() {
        when(configService.load("transient_test_job")).thenReturn(Optional.of(transientJobConfig));
        when(runningService.getRunningTasks("transient_test_job")).thenReturn(Arrays.asList(
                TaskContext.from("transient_test_job@-@0@-@READY@-@SLAVE-S0@-@UUID"), TaskContext.from("transient_test_job@-@1@-@READY@-@SLAVE-S0@-@UUID")));
        producerManager.deregister("transient_test_job");
        verify(configService).remove("transient_test_job");
        verify(lifecycleService).killJob("transient_test_job");
        verify(runningService).remove(TaskContext.MetaInfo.from("transient_test_job@-@0"));
        verify(runningService).remove(TaskContext.MetaInfo.from("transient_test_job@-@1"));
        verify(readyService).remove(Lists.newArrayList("transient_test_job"));
    }
    
    @Test
    public void assertShutdown() {
        producerManager.shutdown();
        verify(transientProducerScheduler).shutdown();
    }
}
