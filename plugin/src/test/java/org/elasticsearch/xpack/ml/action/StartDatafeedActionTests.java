/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.action;

import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.ml.MlMetadata;
import org.elasticsearch.xpack.ml.datafeed.DatafeedConfig;
import org.elasticsearch.xpack.ml.datafeed.DatafeedJobRunnerTests;
import org.elasticsearch.xpack.ml.datafeed.DatafeedState;
import org.elasticsearch.xpack.ml.job.config.Job;
import org.elasticsearch.xpack.ml.job.config.JobState;
import org.elasticsearch.xpack.persistent.PersistentTasksInProgress;
import org.elasticsearch.xpack.persistent.PersistentTasksInProgress.PersistentTaskInProgress;

import java.net.InetAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.elasticsearch.xpack.ml.action.OpenJobActionTests.createJobTask;
import static org.elasticsearch.xpack.ml.support.BaseMlIntegTestCase.createDatafeed;
import static org.elasticsearch.xpack.ml.support.BaseMlIntegTestCase.createScheduledJob;
import static org.hamcrest.Matchers.equalTo;

public class StartDatafeedActionTests extends ESTestCase {

    public void testSelectNode() throws Exception {
        MlMetadata.Builder mlMetadata = new MlMetadata.Builder();
        Job job = createScheduledJob("job_id").build();
        mlMetadata.putJob(job, false);
        mlMetadata.putDatafeed(createDatafeed("datafeed_id", job.getId(), Collections.singletonList("*")));

        JobState jobState = randomFrom(JobState.FAILED, JobState.CLOSED, JobState.CLOSING, JobState.OPENING);
        PersistentTaskInProgress<OpenJobAction.Request> task = createJobTask(0L, job.getId(), "node_id", jobState);
        PersistentTasksInProgress tasks = new PersistentTasksInProgress(1L, Collections.singletonMap(0L, task));

        DiscoveryNodes nodes = DiscoveryNodes.builder()
                .add(new DiscoveryNode("node_name", "node_id", new TransportAddress(InetAddress.getLoopbackAddress(), 9300),
                        Collections.emptyMap(), Collections.emptySet(), Version.CURRENT))
                .build();

        ClusterState.Builder cs = ClusterState.builder(new ClusterName("cluster_name"))
                .metaData(new MetaData.Builder().putCustom(MlMetadata.TYPE, mlMetadata.build())
                        .putCustom(PersistentTasksInProgress.TYPE, tasks))
                .nodes(nodes);

        StartDatafeedAction.Request request = new StartDatafeedAction.Request("datafeed_id", 0L);
        DiscoveryNode node = StartDatafeedAction.selectNode(logger, request, cs.build());
        assertNull(node);

        task = createJobTask(0L, job.getId(), "node_id", JobState.OPENED);
        tasks = new PersistentTasksInProgress(1L, Collections.singletonMap(0L, task));
        cs = ClusterState.builder(new ClusterName("cluster_name"))
                .metaData(new MetaData.Builder().putCustom(MlMetadata.TYPE, mlMetadata.build())
                        .putCustom(PersistentTasksInProgress.TYPE, tasks))
                .nodes(nodes);
        node = StartDatafeedAction.selectNode(logger, request, cs.build());
        assertEquals("node_id", node.getId());
    }

    public void testValidate() {
        Job job1 = DatafeedJobRunnerTests.createDatafeedJob().build();
        MlMetadata mlMetadata1 = new MlMetadata.Builder()
                .putJob(job1, false)
                .build();
        Exception e = expectThrows(ResourceNotFoundException.class,
                () -> StartDatafeedAction.validate("some-datafeed", mlMetadata1, null, null));
        assertThat(e.getMessage(), equalTo("No datafeed with id [some-datafeed] exists"));
    }

    public void testValidate_jobClosed() {
        Job job1 = DatafeedJobRunnerTests.createDatafeedJob().build();
        MlMetadata mlMetadata1 = new MlMetadata.Builder()
                .putJob(job1, false)
                .build();
        PersistentTaskInProgress<OpenJobAction.Request> task =
                new PersistentTaskInProgress<>(0L, OpenJobAction.NAME, new OpenJobAction.Request("foo"), false, true, null);
        PersistentTasksInProgress tasks = new PersistentTasksInProgress(0L, Collections.singletonMap(0L, task));
        DatafeedConfig datafeedConfig1 = DatafeedJobRunnerTests.createDatafeedConfig("foo-datafeed", "foo").build();
        MlMetadata mlMetadata2 = new MlMetadata.Builder(mlMetadata1)
                .putDatafeed(datafeedConfig1)
                .build();
        Exception e = expectThrows(ElasticsearchStatusException.class,
                () -> StartDatafeedAction.validate("foo-datafeed", mlMetadata2, tasks, null));
        assertThat(e.getMessage(), equalTo("cannot start datafeed, expected job state [opened], but got [closed]"));
    }

    public void testValidate_dataFeedAlreadyStarted() {
        Job job1 = createScheduledJob("job_id").build();
        DatafeedConfig datafeedConfig = createDatafeed("datafeed_id", "job_id", Collections.singletonList("*"));
        MlMetadata mlMetadata1 = new MlMetadata.Builder()
                .putJob(job1, false)
                .putDatafeed(datafeedConfig)
                .build();
        DiscoveryNodes nodes = DiscoveryNodes.builder()
                .add(new DiscoveryNode("node_name", "node_id", new TransportAddress(InetAddress.getLoopbackAddress(), 9300),
                        Collections.emptyMap(), Collections.emptySet(), Version.CURRENT))
                .build();

        PersistentTaskInProgress<OpenJobAction.Request> jobTask = createJobTask(0L, "job_id", "node_id", JobState.OPENED);
        PersistentTaskInProgress<StartDatafeedAction.Request> datafeedTask =
                new PersistentTaskInProgress<>(0L, StartDatafeedAction.NAME, new StartDatafeedAction.Request("datafeed_id", 0L),
                        false, true, "node_id");
        datafeedTask = new PersistentTaskInProgress<>(datafeedTask, DatafeedState.STARTED);
        Map<Long, PersistentTaskInProgress<?>> taskMap = new HashMap<>();
        taskMap.put(0L, jobTask);
        taskMap.put(1L, datafeedTask);
        PersistentTasksInProgress tasks = new PersistentTasksInProgress(2L, taskMap);

        Exception e = expectThrows(ElasticsearchStatusException.class,
                () -> StartDatafeedAction.validate("datafeed_id", mlMetadata1, tasks, nodes));
        assertThat(e.getMessage(), equalTo("datafeed already started, expected datafeed state [stopped], but got [started]"));
    }

    public void testValidate_staleTask() {
        Job job1 = createScheduledJob("job_id").build();
        DatafeedConfig datafeedConfig = createDatafeed("datafeed_id", "job_id", Collections.singletonList("*"));
        MlMetadata mlMetadata1 = new MlMetadata.Builder()
                .putJob(job1, false)
                .putDatafeed(datafeedConfig)
                .build();
        DiscoveryNodes nodes = DiscoveryNodes.builder()
                .add(new DiscoveryNode("node_name", "node_id2", new TransportAddress(InetAddress.getLoopbackAddress(), 9300),
                        Collections.emptyMap(), Collections.emptySet(), Version.CURRENT))
                .build();

        PersistentTaskInProgress<OpenJobAction.Request> jobTask = createJobTask(0L, "job_id", "node_id2", JobState.OPENED);
        PersistentTaskInProgress<StartDatafeedAction.Request> datafeedTask =
                new PersistentTaskInProgress<>(0L, StartDatafeedAction.NAME, new StartDatafeedAction.Request("datafeed_id", 0L),
                        false, true, "node_id1");
        datafeedTask = new PersistentTaskInProgress<>(datafeedTask, DatafeedState.STARTED);
        Map<Long, PersistentTaskInProgress<?>> taskMap = new HashMap<>();
        taskMap.put(0L, jobTask);
        taskMap.put(1L, datafeedTask);
        PersistentTasksInProgress tasks = new PersistentTasksInProgress(2L, taskMap);
        StartDatafeedAction.validate("datafeed_id", mlMetadata1, tasks, nodes);

        datafeedTask = new PersistentTaskInProgress<>(0L, StartDatafeedAction.NAME, new StartDatafeedAction.Request("datafeed_id", 0L),
                        false, true, null);
        datafeedTask = new PersistentTaskInProgress<>(datafeedTask, DatafeedState.STARTED);
        taskMap.put(1L, datafeedTask);
        StartDatafeedAction.validate("datafeed_id", mlMetadata1, tasks, nodes);
    }

}
