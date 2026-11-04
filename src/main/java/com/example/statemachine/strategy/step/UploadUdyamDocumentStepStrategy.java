package com.example.statemachine.strategy.step;

import com.example.statemachine.constants.WorkflowBehaviorConstants;
import com.example.statemachine.dto.workflow.WorkflowStepContext;
import com.example.statemachine.enums.LogValueSanitizerFunction;
import com.example.statemachine.strategy.WorkflowStepStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Business integration placeholder; currently permits workflow testing without an external call. */
@Component
@Slf4j
public class UploadUdyamDocumentStepStrategy implements WorkflowStepStrategy {
    @Override
    public String actionName() {
        return WorkflowBehaviorConstants.UPLOAD_UDYAM_DOCUMENT;
    }

    @Override
    public void execute(WorkflowStepContext context) {
        log.info("workflow_step_started action={} workflowKey={} definitionVersion={} requestId={} referenceId={} event={}",
                actionName(),
                LogValueSanitizerFunction.SANITIZE.apply(context.workflowKey()),
                context.definitionVersion(),
                LogValueSanitizerFunction.SANITIZE.apply(context.metadata().requestId()),
                LogValueSanitizerFunction.SANITIZE.apply(context.metadata().referenceId()),
                LogValueSanitizerFunction.SANITIZE.apply(context.metadata().event()));

        // TODO: Call the document service to upload and validate the Udyam document.
        // TODO: Define required step inputs and pass the idempotency key to the external service.
        // Throw on failure; never return successfully until the external task has succeeded.
        log.warn("workflow_step_placeholder action={} task=\"Dummy Udyam document upload\" externalCallImplemented=false", actionName());
    }
}
