/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.camunda.bpm.engine.impl.jobexecutor;

import org.camunda.bpm.engine.OptimisticLockingException;
import org.camunda.bpm.engine.impl.ProcessEngineLogger;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.cmd.ExecuteJobsCmd;
import org.camunda.bpm.engine.impl.context.Context;
import org.camunda.bpm.engine.impl.interceptor.Command;
import org.camunda.bpm.engine.impl.interceptor.CommandExecutor;

public class ExecuteJobHelper {

  private static final JobExecutorLogger LOG = ProcessEngineLogger.JOB_EXECUTOR_LOGGER;

  public static void executeJob(String jobId, CommandExecutor commandExecutor) {

    JobFailureCollector jobFailureCollector = new JobFailureCollector(jobId);

    ExecuteJobHelper.executeJob(jobId, commandExecutor, jobFailureCollector, new ExecuteJobsCmd(jobId, jobFailureCollector));

  }

  public static void executeJob(String nextJobId, CommandExecutor commandExecutor, JobFailureCollector jobFailureCollector, Command<Void> cmd) {
    try {

      commandExecutor.execute(cmd);

    } catch (RuntimeException exception) {
      ExecuteJobHelper.handleJobFailure(nextJobId, jobFailureCollector, exception);
      // throw the original exception to indicate the ExecuteJobCmd failed
      throw exception;

    } catch (Throwable exception) {
      ExecuteJobHelper.handleJobFailure(nextJobId, jobFailureCollector, exception);
      // wrap the exception and throw it to indicate the ExecuteJobCmd failed
      throw LOG.wrapJobExecutionFailure(jobFailureCollector, exception);

    } finally {
      ExecuteJobHelper.invokeJobListener(commandExecutor, jobFailureCollector);
    }

  }

  protected static void invokeJobListener(CommandExecutor commandExecutor, JobFailureCollector jobFailureCollector) {
    if(jobFailureCollector.getJobId() != null) {
      if (jobFailureCollector.getFailure() != null) {
        // the failed job listener is responsible for decrementing the retries and logging the exception to the DB.

        FailedJobListener failedJobListener = createFailedJobListener(commandExecutor, jobFailureCollector.getFailure(), jobFailureCollector.getJobId());

        //in case of OptimisticLockException we retry the FailedJobListener for configured amount of times
        boolean succeeded = false;
        OptimisticLockingException exception = null;
        int failedJobListenerMaxRetries = getFailedJobListenerMaxRetries();
        while (!succeeded && failedJobListener.getCountRetries() < failedJobListenerMaxRetries) {
          try {
            commandExecutor.execute(failedJobListener);
            succeeded = true;
          } catch (OptimisticLockingException ex) {
            failedJobListener.incrementCountRetries();
            exception = ex;
          }
        }
        if (!succeeded) {
          throw exception;
        }
      } else {
        SuccessfulJobListener successListener = createSuccessfulJobListener(commandExecutor);
        commandExecutor.execute(successListener);
      }
    }
  }

  private static int getFailedJobListenerMaxRetries() {
    if (Context.getJobExecutorContext() != null) {
      return Context.getJobExecutorContext().getFailedJobListenerMaxRetries();
    } else {
      return ProcessEngineConfigurationImpl.DEFAULT_FAILED_JOB_LISTENER_MAX_RETRIES;
    }
  }

  protected static void handleJobFailure(final String nextJobId, final JobFailureCollector jobFailureCollector, Throwable exception) {
    LOG.exceptionWhileExecutingJob(nextJobId, exception);

    jobFailureCollector.setFailure(exception);
  }


  protected static FailedJobListener createFailedJobListener(CommandExecutor commandExecutor, Throwable exception, String jobId) {
    return new FailedJobListener(commandExecutor, jobId, exception);
  }

  protected static SuccessfulJobListener createSuccessfulJobListener(CommandExecutor commandExecutor) {
    return new SuccessfulJobListener();
  }

}
