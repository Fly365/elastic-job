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

package com.dangdang.ddframe.job.cloud.scheduler.mesos;

import com.dangdang.ddframe.job.cloud.scheduler.config.CloudJobConfiguration;
import com.dangdang.ddframe.job.cloud.scheduler.context.ExecutionType;
import com.dangdang.ddframe.job.cloud.scheduler.context.JobContext;
import com.dangdang.ddframe.job.cloud.scheduler.context.TaskContext;
import com.dangdang.ddframe.job.executor.ShardingContexts;
import com.dangdang.ddframe.job.util.config.ShardingItemParameters;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.google.protobuf.ByteString;
import com.netflix.fenzo.TaskAssignmentResult;
import com.netflix.fenzo.TaskRequest;
import com.netflix.fenzo.TaskScheduler;
import com.netflix.fenzo.VMAssignmentResult;
import com.netflix.fenzo.VirtualMachineLease;
import com.netflix.fenzo.plugins.VMLeaseObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.mesos.Protos;
import org.apache.mesos.Scheduler;
import org.apache.mesos.SchedulerDriver;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * 作业云引擎.
 *
 * @author zhangliang
 */
@RequiredArgsConstructor
@Slf4j
public final class SchedulerEngine implements Scheduler {
    
    private final TaskScheduler taskScheduler;
    
    private final FacadeService facadeService;
    
    @Override
    public void registered(final SchedulerDriver schedulerDriver, final Protos.FrameworkID frameworkID, final Protos.MasterInfo masterInfo) {
        log.info("call registered");
        facadeService.start();
        taskScheduler.expireAllLeases();
    }
    
    @Override
    public void reregistered(final SchedulerDriver schedulerDriver, final Protos.MasterInfo masterInfo) {
        log.info("call reregistered");
        facadeService.start();
        taskScheduler.expireAllLeases();
    }
    
    @Override
    public void resourceOffers(final SchedulerDriver schedulerDriver, final List<Protos.Offer> offers) {
        List<VirtualMachineLease> leaseOffers = new ArrayList<>(offers.size());
        for (Protos.Offer each : offers) {
            leaseOffers.add(new VMLeaseObject(each));
        }
        Collection<JobContext> eligibleJobContexts =  facadeService.getEligibleJobContext();
        Map<String, Integer> jobShardingTotalCountMap = new HashMap<>(eligibleJobContexts.size(), 1);
        List<TaskRequest> pendingTasks = new ArrayList<>(eligibleJobContexts.size() * 10);
        for (JobContext each : eligibleJobContexts) {
            pendingTasks.addAll(getTaskRequests(each));
            if (ExecutionType.FAILOVER != each.getType()) {
                jobShardingTotalCountMap.put(each.getJobConfig().getJobName(), each.getJobConfig().getTypeConfig().getCoreConfig().getShardingTotalCount());
            }
        }
        Collection<VMAssignmentResult> vmAssignmentResults = taskScheduler.scheduleOnce(pendingTasks, leaseOffers).getResultMap().values();
        logUnassignedJobs(eligibleJobContexts, vmAssignmentResults);
        Collection<String> integrityViolationJobs = getIntegrityViolationJobs(jobShardingTotalCountMap, vmAssignmentResults);
        logIntegrityViolationJobs(integrityViolationJobs);
        for (VMAssignmentResult each: vmAssignmentResults) {
            List<VirtualMachineLease> leasesUsed = each.getLeasesUsed();
            List<Protos.TaskInfo> taskInfoList = new ArrayList<>(each.getTasksAssigned().size() * 10);
            taskInfoList.addAll(getTaskInfoList(integrityViolationJobs, each, leasesUsed.get(0).hostname(), leasesUsed.get(0).getOffer().getSlaveId()));
            schedulerDriver.launchTasks(getOfferIDs(leasesUsed), taskInfoList);
            facadeService.removeLaunchTasksFromQueue(Lists.transform(taskInfoList, new Function<Protos.TaskInfo, TaskContext>() {
            
                @Override
                public TaskContext apply(final Protos.TaskInfo input) {
                    return TaskContext.from(input.getTaskId().getValue());
                }
            }));
            for (Protos.TaskInfo taskInfo : taskInfoList) {
                facadeService.addRunning(TaskContext.from(taskInfo.getTaskId().getValue()));
            }
        }
    }
    
    private Collection<TaskRequest> getTaskRequests(final JobContext jobContext) {
        Collection<TaskRequest> result = new ArrayList<>(jobContext.getAssignedShardingItems().size());
        CloudJobConfiguration jobConfig = jobContext.getJobConfig();
        for (int each : jobContext.getAssignedShardingItems()) {
            result.add(new JobTaskRequest(new TaskContext(jobConfig.getJobName(), each, jobContext.getType(), "fake-slave"), jobConfig));
        }
        return result;
    }
    
    private void logUnassignedJobs(final Collection<JobContext> eligibleJobContexts, final Collection<VMAssignmentResult> vmAssignmentResults) {
        for (JobContext each : eligibleJobContexts) {
            if (!isAssigned(each, vmAssignmentResults) && !facadeService.isRunning(each.getJobConfig().getJobName())) {
                log.warn("Job {} is not assigned at this time, because resources not enough.", each.getJobConfig().getJobName());
            }
        }
    }
    
    private boolean isAssigned(final JobContext jobContext, final Collection<VMAssignmentResult> vmAssignmentResults) {
        for (VMAssignmentResult vmAssignmentResult: vmAssignmentResults) {
            for (TaskAssignmentResult taskAssignmentResult : vmAssignmentResult.getTasksAssigned()) {
                if (jobContext.getJobConfig().getJobName().equals(TaskContext.from(taskAssignmentResult.getTaskId()).getMetaInfo().getJobName())) {
                    return true;
                }
            }
        }
        return false;
    }
    
    private Collection<String> getIntegrityViolationJobs(final Map<String, Integer> jobShardingTotalCountMap, final Collection<VMAssignmentResult> vmAssignmentResults) {
        Map<String, Integer> assignedJobShardingTotalCountMap = new HashMap<>(jobShardingTotalCountMap.size(), 1);
        for (VMAssignmentResult vmAssignmentResult: vmAssignmentResults) {
            for (TaskAssignmentResult tasksAssigned: vmAssignmentResult.getTasksAssigned()) {
                String jobName = TaskContext.from(tasksAssigned.getTaskId()).getMetaInfo().getJobName();
                if (assignedJobShardingTotalCountMap.containsKey(jobName)) {
                    assignedJobShardingTotalCountMap.put(jobName, assignedJobShardingTotalCountMap.get(jobName) + 1);
                } else {
                    assignedJobShardingTotalCountMap.put(jobName, 1);
                }
            }
        }
        Collection<String> result = new HashSet<>(assignedJobShardingTotalCountMap.size(), 1);
        for (Map.Entry<String, Integer> entry : assignedJobShardingTotalCountMap.entrySet()) {
            if (jobShardingTotalCountMap.containsKey(entry.getKey()) && !entry.getValue().equals(jobShardingTotalCountMap.get(entry.getKey()))) {
                result.add(entry.getKey());
            }
        }
        return result;
    }
    
    private void logIntegrityViolationJobs(final Collection<String> integrityViolationJobs) {
        for (String each : integrityViolationJobs) {
            log.warn("Job {} is not assigned at this time, because resources not enough to run all sharding instances.", each);
        }
    }
    
    private List<Protos.TaskInfo> getTaskInfoList(final Collection<String> integrityViolationJobs, final VMAssignmentResult vmAssignmentResult, final String hostname, final Protos.SlaveID slaveId) {
        List<Protos.TaskInfo> result = new ArrayList<>(vmAssignmentResult.getTasksAssigned().size());
        for (TaskAssignmentResult each: vmAssignmentResult.getTasksAssigned()) {
            TaskContext taskContext = TaskContext.from(each.getTaskId());
            if (!integrityViolationJobs.contains(taskContext.getMetaInfo().getJobName()) && !facadeService.isRunning(taskContext)) {
                Protos.TaskInfo taskInfo = getTaskInfo(slaveId, each);
                if (null != taskInfo) {
                    result.add(getTaskInfo(slaveId, each));
                }
                taskScheduler.getTaskAssigner().call(each.getRequest(), hostname);
            }
        }
        return result;
    }
    
    private Protos.TaskInfo getTaskInfo(final Protos.SlaveID slaveID, final TaskAssignmentResult taskAssignmentResult) {
        TaskContext originalTaskContext = TaskContext.from(taskAssignmentResult.getTaskId());
        int shardingItem = originalTaskContext.getMetaInfo().getShardingItem();
        TaskContext taskContext = new TaskContext(originalTaskContext.getMetaInfo().getJobName(), shardingItem, originalTaskContext.getType(), slaveID.getValue());
        Optional<CloudJobConfiguration> jobConfigOptional = facadeService.load(taskContext.getMetaInfo().getJobName());
        if (!jobConfigOptional.isPresent()) {
            return null;
        }
        CloudJobConfiguration jobConfig = jobConfigOptional.get();
        Map<Integer, String> shardingItemParameters = new ShardingItemParameters(jobConfig.getTypeConfig().getCoreConfig().getShardingItemParameters()).getMap();
        Map<Integer, String> assignedShardingItemParameters = new HashMap<>(1, 1);
        assignedShardingItemParameters.put(shardingItem, shardingItemParameters.containsKey(shardingItem) ? shardingItemParameters.get(shardingItem) : "");
        ShardingContexts shardingContexts = new ShardingContexts(
                jobConfig.getJobName(), jobConfig.getTypeConfig().getCoreConfig().getShardingTotalCount(), jobConfig.getTypeConfig().getCoreConfig().getJobParameter(), assignedShardingItemParameters);
        // TODO 更改cache为elastic-job-cloud-scheduler.properties配置
        Protos.CommandInfo.URI uri = Protos.CommandInfo.URI.newBuilder().setValue(jobConfig.getAppURL()).setExtract(true).setCache(false).build();
        Protos.CommandInfo command = Protos.CommandInfo.newBuilder().addUris(uri).setShell(true).setValue(jobConfig.getBootstrapScript()).build();
        Protos.ExecutorInfo executorInfo =
                Protos.ExecutorInfo.newBuilder().setExecutorId(Protos.ExecutorID.newBuilder().setValue(taskContext.getExecutorId(jobConfig.getAppURL()))).setCommand(command).build();
        return Protos.TaskInfo.newBuilder()
                .setTaskId(Protos.TaskID.newBuilder().setValue(taskContext.getId()).build())
                .setName(taskContext.getTaskName())
                .setSlaveId(slaveID)
                .addResources(buildResource("cpus", jobConfig.getCpuCount()))
                .addResources(buildResource("mem", jobConfig.getMemoryMB()))
                .setExecutor(executorInfo)
                .setData(ByteString.copyFrom(new TaskInfoData(shardingContexts, jobConfig).serialize()))
                .build();
    }
    
    private Protos.Resource.Builder buildResource(final String type, final double resourceValue) {
        return Protos.Resource.newBuilder().setName(type).setType(Protos.Value.Type.SCALAR).setScalar(Protos.Value.Scalar.newBuilder().setValue(resourceValue));
    }
    
    private List<Protos.OfferID> getOfferIDs(final List<VirtualMachineLease> leasesUsed) {
        List<Protos.OfferID> offerIDs = new ArrayList<>();
        for (VirtualMachineLease virtualMachineLease: leasesUsed) {
            offerIDs.add(virtualMachineLease.getOffer().getId());
        }
        return offerIDs;
    }
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    @Override
    public void offerRescinded(final SchedulerDriver schedulerDriver, final Protos.OfferID offerID) {
        log.trace("call offerRescinded: {}", offerID);
        taskScheduler.expireLease(offerID.getValue());
    }
    
    @Override
    public void statusUpdate(final SchedulerDriver schedulerDriver, final Protos.TaskStatus taskStatus) {
        String taskId = taskStatus.getTaskId().getValue();
        TaskContext taskContext = TaskContext.from(taskId);
        log.trace("call statusUpdate task state is: {}, task id is: {}", taskStatus.getState(), taskId);
        switch (taskStatus.getState()) {
            case TASK_RUNNING:
                if ("BEGIN".equals(taskStatus.getMessage())) {
                    facadeService.updateDaemonStatus(taskContext, false);
                } else if ("COMPLETE".equals(taskStatus.getMessage())) {
                    facadeService.updateDaemonStatus(taskContext, true);
                }
                break;
            case TASK_FINISHED:
                facadeService.removeRunning(taskContext.getMetaInfo());
                break;
            case TASK_KILLED:
                facadeService.removeRunning(taskContext.getMetaInfo());
                facadeService.addDaemonJobToReadyQueue(taskContext.getMetaInfo().getJobName());
                break;
            case TASK_LOST:
            case TASK_FAILED:
            case TASK_ERROR:
                log.warn("task id is: {}, status is: {}, message is: {}, source is: {}", taskId, taskStatus.getState(), taskStatus.getMessage(), taskStatus.getSource());
                facadeService.removeRunning(taskContext.getMetaInfo());
                facadeService.recordFailoverTask(taskContext);
                facadeService.addDaemonJobToReadyQueue(taskContext.getMetaInfo().getJobName());
                break;
            default:
                break;
        }
    }
    
    @Override
    public void frameworkMessage(final SchedulerDriver schedulerDriver, final Protos.ExecutorID executorID, final Protos.SlaveID slaveID, final byte[] bytes) {
        log.trace("call frameworkMessage slaveID: {}, bytes: {}", slaveID, new String(bytes));
    }
    
    @Override
    public void disconnected(final SchedulerDriver schedulerDriver) {
        log.warn("call disconnected");
        facadeService.stop();
    }
    
    @Override
    public void slaveLost(final SchedulerDriver schedulerDriver, final Protos.SlaveID slaveID) {
        log.warn("call slaveLost slaveID is: {}", slaveID);
        taskScheduler.expireAllLeasesByVMId(slaveID.getValue());
    }
    
    @Override
    public void executorLost(final SchedulerDriver schedulerDriver, final Protos.ExecutorID executorID, final Protos.SlaveID slaveID, final int i) {
        log.debug("call executorLost slaveID is: {}, executorID is: {}", slaveID, executorID);
    }
    
    @Override
    public void error(final SchedulerDriver schedulerDriver, final String message) {
        log.error("call error, message is: {}", message);
    }
}
