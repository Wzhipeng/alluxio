/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.job.master;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import alluxio.Constants;
import alluxio.conf.PropertyKey;
import alluxio.exception.status.ResourceExhaustedException;
import alluxio.job.plan.PlanDefinitionRegistryRule;
import alluxio.job.SleepJobConfig;
import alluxio.job.plan.SleepPlanDefinition;
import alluxio.job.util.JobTestUtils;
import alluxio.job.wire.JobWorkerHealth;
import alluxio.job.wire.JobInfo;
import alluxio.job.wire.Status;
import alluxio.master.LocalAlluxioJobCluster;
import alluxio.master.job.JobMaster;
import alluxio.testutils.BaseIntegrationTest;
import alluxio.testutils.LocalAlluxioClusterResource;
import alluxio.util.CommonUtils;
import alluxio.util.WaitForOptions;
import alluxio.wire.WorkerInfo;
import alluxio.worker.JobWorkerProcess;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Integration tests for the job master.
 */
public final class JobMasterIntegrationTest extends BaseIntegrationTest {
  private static final long WORKER_TIMEOUT_MS = 500;
  private static final long LOST_WORKER_INTERVAL_MS = 500;
  private JobMaster mJobMaster;
  private JobWorkerProcess mJobWorker;
  private LocalAlluxioJobCluster mLocalAlluxioJobCluster;

  @Rule
  public LocalAlluxioClusterResource mLocalAlluxioClusterResource =
      new LocalAlluxioClusterResource.Builder()
          .setProperty(PropertyKey.JOB_MASTER_WORKER_HEARTBEAT_INTERVAL, 20)
          .setProperty(PropertyKey.JOB_MASTER_WORKER_TIMEOUT, WORKER_TIMEOUT_MS)
          .setProperty(PropertyKey.JOB_MASTER_LOST_WORKER_INTERVAL, LOST_WORKER_INTERVAL_MS)
          .build();

  @Rule
  public PlanDefinitionRegistryRule mJobRule =
      new PlanDefinitionRegistryRule(SleepJobConfig.class, new SleepPlanDefinition());

  @Before
  public void before() throws Exception {
    mLocalAlluxioJobCluster = new LocalAlluxioJobCluster();
    mLocalAlluxioJobCluster.start();
    mJobMaster = mLocalAlluxioJobCluster.getMaster().getJobMaster();
    mJobWorker = mLocalAlluxioJobCluster.getWorker();
  }

  @After
  public void after() throws Exception {
    mLocalAlluxioJobCluster.stop();
  }

  @Test
  public void multipleTasksPerWorker() throws Exception {
    long jobId = mJobMaster.run(new SleepJobConfig(1, 2));

    JobInfo jobStatus = mJobMaster.getStatus(jobId);
    assertEquals(2, jobStatus.getChildren().size());

    JobTestUtils.waitForJobStatus(mJobMaster, jobId, Status.COMPLETED);

    jobStatus = mJobMaster.getStatus(jobId);
    assertEquals(2, jobStatus.getChildren().size());
  }

  @Test
  @LocalAlluxioClusterResource.Config(confParams = {PropertyKey.Name.JOB_MASTER_JOB_CAPACITY, "1",
      PropertyKey.Name.JOB_MASTER_FINISHED_JOB_RETENTION_TIME, "0"})
  public void flowControl() throws Exception {
    for (int i = 0; i < 10; i++) {
      while (true) {
        try {
          mJobMaster.run(new SleepJobConfig(100));
          break;
        } catch (ResourceExhaustedException e) {
          // sleep for a little before retrying the job
          CommonUtils.sleepMs(100);
        }
      }
    }
  }

  @Test
  public void restartMasterAndLoseWorker() throws Exception {
    long jobId = mJobMaster.run(new SleepJobConfig(1));
    JobTestUtils.waitForJobStatus(mJobMaster, jobId, Status.COMPLETED);
    mJobMaster.stop();
    mJobMaster.start(true);
    CommonUtils.waitFor("Worker to register with restarted job master",
        () -> !mJobMaster.getWorkerInfoList().isEmpty(),
        WaitForOptions.defaults().setTimeoutMs(10 * Constants.SECOND_MS));
    mJobWorker.stop();
    CommonUtils.sleepMs(WORKER_TIMEOUT_MS + LOST_WORKER_INTERVAL_MS);
    assertTrue(mJobMaster.getWorkerInfoList().isEmpty());
  }

  @Test
  @LocalAlluxioClusterResource.Config(
      confParams = {PropertyKey.Name.JOB_MASTER_LOST_WORKER_INTERVAL, "10000000"})
  public void restartMasterAndReregisterWorker() throws Exception {
    long jobId = mJobMaster.run(new SleepJobConfig(1));
    JobTestUtils.waitForJobStatus(mJobMaster, jobId, Status.COMPLETED);
    mJobMaster.stop();
    mJobMaster.start(true);
    CommonUtils.waitFor("Worker to register with restarted job master",
        () -> !mJobMaster.getWorkerInfoList().isEmpty(),
        WaitForOptions.defaults().setTimeoutMs(10 * Constants.SECOND_MS));
    final long firstWorkerId = mJobMaster.getWorkerInfoList().get(0).getId();
    mLocalAlluxioJobCluster.restartWorker();
    CommonUtils.waitFor("Restarted worker to register with job master", () -> {
      List<WorkerInfo> workerInfo = mJobMaster.getWorkerInfoList();
      return !workerInfo.isEmpty() && workerInfo.get(0).getId() != firstWorkerId;
    }, WaitForOptions.defaults().setTimeoutMs(10 * Constants.SECOND_MS));
    // The restarted worker should replace the original worker since they have the same address.
    assertEquals(1, mJobMaster.getWorkerInfoList().size());
  }

  @Test
  public void getAllWorkerHealth() throws Exception {
    final AtomicReference<List<JobWorkerHealth>> singleton = new AtomicReference<>();
    CommonUtils.waitFor("allWorkerHealth", () -> {
      List<JobWorkerHealth> allWorkerHealth = mJobMaster.getAllWorkerHealth();
      singleton.set(allWorkerHealth);
      return allWorkerHealth.size() == 1;
    });
    List<JobWorkerHealth> allWorkerHealth = singleton.get();

    JobWorkerHealth workerHealth = allWorkerHealth.get(0);
    assertNotNull(workerHealth.getHostname());
    assertEquals(3, workerHealth.getLoadAverage().size());
  }
}
