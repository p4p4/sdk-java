/*
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package com.uber.cadence.internal.replay;

import com.uber.cadence.Decision;
import com.uber.cadence.PollForDecisionTaskResponse;
import com.uber.cadence.QueryTaskCompletedType;
import com.uber.cadence.RespondDecisionTaskCompletedRequest;
import com.uber.cadence.RespondDecisionTaskFailedRequest;
import com.uber.cadence.RespondQueryTaskCompletedRequest;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowType;
import com.uber.cadence.internal.worker.DecisionTaskHandler;
import com.uber.cadence.internal.worker.DecisionTaskWithHistoryIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class ReplayDecisionTaskHandler implements DecisionTaskHandler {

    private static final Logger log = LoggerFactory.getLogger(ReplayDecisionTaskHandler.class);

    private final ReplayWorkflowFactory workflowFactory;
    private final String domain;

    public ReplayDecisionTaskHandler(String domain, ReplayWorkflowFactory asyncWorkflowFactory) {
        this.domain = domain;
        this.workflowFactory = asyncWorkflowFactory;
    }

    @Override
    public DecisionTaskHandler.Result handleDecisionTask(DecisionTaskWithHistoryIterator decisionTaskIterator) {
        try {
            return handleDecisionTaskImpl(decisionTaskIterator);
        } catch (Throwable e) {
            PollForDecisionTaskResponse decisionTask = decisionTaskIterator.getDecisionTask();
            if (log.isErrorEnabled()) {
                WorkflowExecution execution = decisionTask.getWorkflowExecution();
                log.error("Workflow task failure. startedEventId=" + decisionTask.getStartedEventId()
                        + ", WorkflowID=" + execution.getWorkflowId()
                        + ", RunID=" + execution.getRunId()
                        + ". If see continuously the workflow might be stuck.", e);
            }
            RespondDecisionTaskFailedRequest failedRequest = new RespondDecisionTaskFailedRequest();
            failedRequest.setTaskToken(decisionTask.getTaskToken());
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            String stackTrace = sw.toString();
            failedRequest.setDetails(stackTrace.getBytes(StandardCharsets.UTF_8));
            return new DecisionTaskHandler.Result(null, failedRequest, null, null);
        }
    }

    private Result handleDecisionTaskImpl(DecisionTaskWithHistoryIterator decisionTaskIterator) throws Throwable {
        HistoryHelper historyHelper = new HistoryHelper(decisionTaskIterator);
        ReplayDecider decider = createDecider(historyHelper);
        PollForDecisionTaskResponse decisionTask = historyHelper.getDecisionTask();
        if (decisionTask.isSetQuery()) {
            RespondQueryTaskCompletedRequest queryCompletedRequest = new RespondQueryTaskCompletedRequest();
            queryCompletedRequest.setTaskToken(decisionTask.getTaskToken());
            try {
                byte[] queryResult = decider.query(decisionTask.getQuery());
                queryCompletedRequest.setQueryResult(queryResult);
                queryCompletedRequest.setCompletedType(QueryTaskCompletedType.COMPLETED);
            } catch (Throwable e) {
                // TODO: Appropriate exception serialization.
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                e.printStackTrace(pw);
                queryCompletedRequest.setErrorMessage(sw.toString());
                queryCompletedRequest.setCompletedType(QueryTaskCompletedType.FAILED);
            }
            return new DecisionTaskHandler.Result(null, null, queryCompletedRequest, null);
        } else {
            decider.decide();
            DecisionsHelper decisionsHelper = decider.getDecisionsHelper();
            List<Decision> decisions = decisionsHelper.getDecisions();
            byte[] context = decisionsHelper.getWorkflowContextDataToReturn();
            if (log.isDebugEnabled()) {
                WorkflowExecution execution = decisionTask.getWorkflowExecution();
                log.debug("WorkflowTask startedEventId=" + decisionTask.getStartedEventId()
                        + ", WorkflowID=" + execution.getWorkflowId()
                        + ", RunID=" + execution.getRunId()
                        + " completed with " + decisions.size() + " new decisions");
            }
            RespondDecisionTaskCompletedRequest completedRequest = new RespondDecisionTaskCompletedRequest();
            completedRequest.setTaskToken(decisionTask.getTaskToken());
            completedRequest.setDecisions(decisions);
            completedRequest.setExecutionContext(context);
            return new DecisionTaskHandler.Result(completedRequest, null, null, null);
        }
    }

    @Override
    public boolean isAnyTypeSupported() {
        return workflowFactory.isAnyTypeSupported();
    }

    private ReplayDecider createDecider(HistoryHelper historyHelper) throws Exception {
        PollForDecisionTaskResponse decisionTask = historyHelper.getDecisionTask();
        WorkflowType workflowType = decisionTask.getWorkflowType();
        DecisionsHelper decisionsHelper = new DecisionsHelper(decisionTask);
        return new ReplayDecider(domain, workflowFactory.getWorkflow(workflowType), historyHelper, decisionsHelper);
    }
}