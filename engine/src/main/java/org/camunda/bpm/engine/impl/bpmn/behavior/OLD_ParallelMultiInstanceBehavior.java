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
package org.camunda.bpm.engine.impl.bpmn.behavior;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

import org.camunda.bpm.engine.impl.bpmn.parser.BpmnParse;
import org.camunda.bpm.engine.impl.context.Context;
import org.camunda.bpm.engine.impl.core.variable.scope.AbstractVariableScope;
import org.camunda.bpm.engine.impl.jobexecutor.TimerDeclarationImpl;
import org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.camunda.bpm.engine.impl.pvm.delegate.ActivityBehavior;
import org.camunda.bpm.engine.impl.pvm.delegate.ActivityExecution;
import org.camunda.bpm.engine.impl.pvm.process.ActivityImpl;

/**
 * @author Joram Barrez
 * @author Ronny Bräunlich
 */
public class OLD_ParallelMultiInstanceBehavior extends OLD_MultiInstanceActivityBehavior {

  public OLD_ParallelMultiInstanceBehavior(ActivityImpl activity, AbstractBpmnActivityBehavior originalActivityBehavior) {
    super(activity, originalActivityBehavior);
  }

  /**
   * Handles the parallel case of spawning the instances. Will create child
   * executions accordingly for every instance needed.
   */
  protected void createInstances(ActivityExecution execution, int nrOfInstances) throws Exception {

    setLoopVariable(execution, NUMBER_OF_INSTANCES, nrOfInstances);
    setLoopVariable(execution, NUMBER_OF_COMPLETED_INSTANCES, 0);
    setLoopVariable(execution, NUMBER_OF_ACTIVE_INSTANCES, nrOfInstances);

    fixMiRootActivityInstanceId(execution);

    List<ActivityExecution> concurrentExecutions = createConcurrentExecutions(execution, nrOfInstances);
    // Before the activities are executed, all executions MUST be created up front
    // Do not try to merge this loop with the one in createConcurrentExecutions(), as it will lead to bugs,
    // due to possible child execution pruning.
    for (int loopCounter = 0; loopCounter < nrOfInstances; loopCounter++) {
      ActivityExecution concurrentExecution = concurrentExecutions.get(loopCounter);
      // executions can be inactive, if instances are all automatics
      // (no-waitstate) and completionCondition has been met in the meantime
      if (concurrentExecution.isActive() && !concurrentExecution.isEnded()
          && concurrentExecution.getParent().isActive()
          && !concurrentExecution.getParent().isEnded()) {
        setLoopVariable(concurrentExecution, LOOP_COUNTER, loopCounter);
        executeOriginalBehavior(concurrentExecution, loopCounter);
      }
    }
    if (!concurrentExecutions.isEmpty()) {
      execution.inactivate();
    }
  }

  protected void doExecuteOriginalBehavior(ActivityExecution execution, int loopCounter) throws Exception {
    // If loopcounter == 0, then historic activity instance already created, no need to
    // pass through executeActivity again since it will create a new historic activity
    if (loopCounter == 0) {
      try {
        // we have to put the execution on the execution stack for the first iteration
        // because ActivityBehavior#execute won't execute an AtomicOperation
        // that does so for us
        Context.setExecutionContext((ExecutionEntity) execution);
        innerActivityBehavior.execute(execution);
      } finally {
        Context.removeExecutionContext();
      }
    } else {
      execution.executeActivity(activity);
    }
  }

  protected List<ActivityExecution> createConcurrentExecutions(ActivityExecution execution, int nrOfInstances) {
    List<ActivityExecution> concurrentExecutions = new ArrayList<ActivityExecution>();
    for (int loopCounter = 0; loopCounter < nrOfInstances; loopCounter++) {
      ActivityExecution concurrentExecution = execution.createExecution(loopCounter != 0);
      concurrentExecution.setActive(true);
      concurrentExecution.setConcurrent(true);
      concurrentExecution.setScope(false);

      // In case of an embedded subprocess, and extra child execution is required
      // Otherwise, all child executions would end up under the same parent,
      // without any differentiation to which embedded subprocess they belong
      if (isExtraScopeNeeded()) {
        ActivityExecution extraScopedExecution = concurrentExecution.createExecution();
        extraScopedExecution.setActive(true);
        extraScopedExecution.setConcurrent(false);
        extraScopedExecution.setScope(true);
        concurrentExecution = extraScopedExecution;
      }
//
//      // create event subscriptions for the concurrent execution
//      for (EventSubscriptionDeclaration declaration : EventSubscriptionDeclaration.getDeclarationsForScope(execution.getActivity())) {
//        declaration.createSubscriptionForParallelMultiInstance((ExecutionEntity) concurrentExecution);
//      }

      executeIoMapping((AbstractVariableScope) concurrentExecution);

      // create timer job for the current execution
      createTimerJobsForExecution(concurrentExecution);

      concurrentExecutions.add(concurrentExecution);
      logLoopDetails(concurrentExecution, "initialized", loopCounter, 0, nrOfInstances, nrOfInstances);
    }
    return concurrentExecutions;
  }

  @SuppressWarnings("unchecked")
  protected void createTimerJobsForExecution(ActivityExecution execution) {
    List<TimerDeclarationImpl> timerDeclarations = (List<TimerDeclarationImpl>) execution.getActivity().getProperty(BpmnParse.PROPERTYNAME_TIMER_DECLARATION);
    if (timerDeclarations != null) {
      for (TimerDeclarationImpl timerDeclaration : timerDeclarations) {
        timerDeclaration.createTimerInstanceForParallelMultiInstance((ExecutionEntity) execution);
      }
    }
  }

  /**
   * Called when the wrapped {@link ActivityBehavior} calls the
   * {@link AbstractBpmnActivityBehavior#leave(ActivityExecution)} method.
   * Handles the completion of one of the parallel instances
   */
  public void leave(ActivityExecution execution) {

    if (!isExtraScopeNeeded() && !execution.getActivityInstanceId().equals(execution.getParent().getActivityInstanceId())) {
      callActivityEndListeners(execution);
    }

    int loopCounter = getLoopVariable(execution, LOOP_COUNTER);
    int nrOfInstances = getLoopVariable(execution, NUMBER_OF_INSTANCES);
    int nrOfCompletedInstances = getLoopVariable(execution, NUMBER_OF_COMPLETED_INSTANCES) + 1;
    int nrOfActiveInstances = getLoopVariable(execution, NUMBER_OF_ACTIVE_INSTANCES) - 1;

    if (isExtraScopeNeeded()) {
      resetMiRootActivityInstanceId(execution);
      // In case an extra scope was created, it must be destroyed first before going further
      ExecutionEntity extraScope = (ExecutionEntity) execution;
      execution = execution.getParent();
      extraScope.remove();
    }

    setLoopVariable(execution.getParent(), NUMBER_OF_COMPLETED_INSTANCES, nrOfCompletedInstances);
    setLoopVariable(execution.getParent(), NUMBER_OF_ACTIVE_INSTANCES, nrOfActiveInstances);
    logLoopDetails(execution, "instance completed", loopCounter, nrOfCompletedInstances, nrOfActiveInstances, nrOfInstances);

    ExecutionEntity executionEntity = (ExecutionEntity) execution;

    // remove event subscriptions that separately created for multi instance
    executionEntity.removeEventSubscriptions();
    executionEntity.inactivate();
    executionEntity.getParent().forceUpdate();

    List<ActivityExecution> joinedExecutions = executionEntity.findInactiveConcurrentExecutions(execution.getActivity());
    if (joinedExecutions.size() == nrOfInstances || completionConditionSatisfied(execution)) {

      resetMiRootActivityInstanceId(execution);

      // Removing all active child executions (ie because completionCondition is true)
      List<ExecutionEntity> executionsToRemove = new ArrayList<ExecutionEntity>();
      for (ActivityExecution childExecution : executionEntity.getParent().getExecutions()) {
        if (childExecution.isActive()) {
          executionsToRemove.add((ExecutionEntity) childExecution);
        }
      }
      for (ExecutionEntity executionToRemove : executionsToRemove) {
        if (LOGGER.isLoggable(Level.FINE)) {
          LOGGER.fine("Execution " + executionToRemove + " still active, " + "but multi-instance is completed. Removing this execution.");
        }
        executionToRemove.inactivate();
        executionToRemove.deleteCascade("multi-instance completed");
      }

      executionEntity.takeAll(activity.getOutgoingTransitions(), joinedExecutions);
    } else {
      if (isExtraScopeNeeded()) {
        callActivityEndListeners(execution);
      } else {
        executionEntity.setActivityInstanceId(null);
      }
    }
  }

  protected void fixMiRootActivityInstanceId(ActivityExecution execution) {
    ActivityExecution miRoot = execution.getParent();
    miRoot.setActivityInstanceId(miRoot.getParentActivityInstanceId());
  }

  protected void resetMiRootActivityInstanceId(ActivityExecution execution) {
    ActivityExecution miEnteringExecution = execution.getParent();
    ActivityExecution miRoot = miEnteringExecution.getParent();
    miRoot.setActivityInstanceId(miEnteringExecution.getActivityInstanceId());
  }

  /* (non-Javadoc)
   * @see org.camunda.bpm.engine.impl.pvm.delegate.CompositeActivityBehavior#concurrentChildExecutionEnded(org.camunda.bpm.engine.impl.pvm.delegate.ActivityExecution, org.camunda.bpm.engine.impl.pvm.delegate.ActivityExecution)
   */
  @Override
  public void concurrentChildExecutionEnded(ActivityExecution scopeExecution, ActivityExecution endedExecution) {
    // TODO Auto-generated method stub

  }

  /* (non-Javadoc)
   * @see org.camunda.bpm.engine.impl.pvm.delegate.CompositeActivityBehavior#complete(org.camunda.bpm.engine.impl.pvm.delegate.ActivityExecution)
   */
  @Override
  public void complete(ActivityExecution scopeExecution) {
    // TODO Auto-generated method stub

  }

}