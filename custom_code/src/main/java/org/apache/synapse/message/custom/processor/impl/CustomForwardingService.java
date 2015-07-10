package org.apache.synapse.message.custom.processor.impl;

import org.apache.axiom.om.OMElement;
import org.apache.axiom.soap.SOAPEnvelope;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseConstants;
import org.apache.synapse.SynapseException;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.endpoints.Endpoint;
import org.apache.synapse.message.MessageConsumer;
import org.apache.synapse.message.processor.MessageProcessor;
import org.apache.synapse.message.processor.MessageProcessorConstants;
import org.apache.synapse.message.processor.impl.forwarder.ForwardingProcessorConstants;
import org.apache.synapse.message.processor.impl.forwarder.ForwardingService;
import org.apache.synapse.message.processor.impl.forwarder.ScheduledMessageForwardingProcessor;
import org.apache.synapse.message.senders.blocking.BlockingMsgSender;
import org.apache.synapse.util.MessageHelper;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class CustomForwardingService extends ForwardingService {

    private static final Log log = LogFactory.getLog(CustomForwardingService.class);

    private String targetEndpoint = null;

    /**
     * These two maintain the state of service. For each iteration these should be reset
     */
    private boolean isSuccessful = false;
    private volatile boolean isTerminated = false;

    /** Owner of the this job */
    private MessageProcessor messageProcessor;

    /** The consumer that is associated with the particular message store */
    private MessageConsumer messageConsumer;

    /**
     * This is specially used for REST scenarios where http status codes can take semantics in a RESTful architecture.
     */
    private String[] nonRetryStatusCodes = null;

    /** This is the client which sends messages to the end point */
    private BlockingMsgSender sender;

    /** Number of retries before shutting-down the processor. -1 default value indicates that
     * retry should happen forever */
    private int maxDeliverAttempts = -1;
    private int attemptCount = 0;

    /** Configuration to continue the message processor even without stopping
     * the message processor after maximum number of delivery */
    private boolean isMaxDeliveryAttemptDropEnabled = false;

    /** Interval between two retries to the client. This only come to affect only if the client is un-reachable */
    private int retryInterval = 1000;



    /**
     * Though it says init() it does not instantiate objects every time the service is running. This simply
     * initialize the local variables with pre-instantiated objects.
     * @param jobExecutionContext is the Quartz context
     * @return true if it is successfully executed.
     */
    public boolean init(JobExecutionContext jobExecutionContext) {

        JobDataMap jdm = jobExecutionContext.getMergedJobDataMap();
        Map<String, Object> parameters = (Map<String, Object>) jdm.get(MessageProcessorConstants.PARAMETERS);

        super.init(jobExecutionContext);

        if (jdm.get(ForwardingProcessorConstants.TARGET_ENDPOINT) != null) {
            targetEndpoint = (String) jdm.get(ForwardingProcessorConstants.TARGET_ENDPOINT);
        }

        messageProcessor = (MessageProcessor)jdm.get(MessageProcessorConstants.PROCESSOR_INSTANCE);
        messageConsumer = messageProcessor.getMessageConsumer();

        if (jdm.get(ForwardingProcessorConstants.NON_RETRY_STATUS_CODES) != null) {
            nonRetryStatusCodes = (String []) jdm.get(ForwardingProcessorConstants.NON_RETRY_STATUS_CODES);
        }

        sender = (BlockingMsgSender) jdm.get(ScheduledMessageForwardingProcessor.BLOCKING_SENDER);

        String mdaParam = (String) parameters.get(MessageProcessorConstants.MAX_DELIVER_ATTEMPTS);
        if (mdaParam != null) {
            try {
                maxDeliverAttempts = Integer.parseInt(mdaParam);
            } catch (NumberFormatException nfe) {
                parameters.remove(MessageProcessorConstants.MAX_DELIVER_ATTEMPTS);
                log.error("Invalid value for max delivery attempts switching back to default value", nfe);
            }
        }

        if (parameters.get(ForwardingProcessorConstants.MAX_DELIVERY_DROP)!=null) {
            if ((parameters.get(ForwardingProcessorConstants.MAX_DELIVERY_DROP)).toString().equals("Enabled")) {
                if (this.maxDeliverAttempts>0) {
                    isMaxDeliveryAttemptDropEnabled = true;
                }
            }
        }

        String ri = (String) parameters.get(MessageProcessorConstants.RETRY_INTERVAL);
        if (ri != null) {
            try {
                retryInterval = Integer.parseInt(ri);
            } catch (NumberFormatException nfe) {
                parameters.remove(MessageProcessorConstants.RETRY_INTERVAL);
                log.error("Invalid value for retry interval switching back to default value", nfe);
            }
        }

        return true;
    }



    public boolean dispatch(MessageContext messageContext) {

        if (log.isDebugEnabled()) {
            log.debug("Sending the message to client with message processor [" + messageProcessor.getName() + "]");
        }

        // The below code is just for keeping the backward compatibility with the old code.
        if (targetEndpoint == null) {
            targetEndpoint = (String) messageContext.getProperty(ForwardingProcessorConstants.TARGET_ENDPOINT);
        }

        MessageContext outCtx = null;
        SOAPEnvelope originalEnvelop = messageContext.getEnvelope();

        if (targetEndpoint != null) {
            Endpoint ep = messageContext.getEndpoint(targetEndpoint);

            try {

                // Send message to the client
                while (!isSuccessful && !isTerminated) {
                    try {
                        // For each retry we need to have a fresh copy of the actual message. otherwise retry may not
                        // work as expected.
                        messageContext.setEnvelope(MessageHelper.cloneSOAPEnvelope(originalEnvelop));

                        OMElement firstChild = null; //
                        org.apache.axis2.context.MessageContext origAxis2Ctx = ((Axis2MessageContext) messageContext).getAxis2MessageContext();

                        if (JsonUtil.hasAJsonPayload(origAxis2Ctx)) {
                            firstChild = origAxis2Ctx.getEnvelope().getBody().getFirstElement();
                        } // Had to do this because MessageHelper#cloneSOAPEnvelope does not clone OMSourcedElemImpl correctly.

                        if (JsonUtil.hasAJsonPayload(firstChild)) { //
                            OMElement clonedFirstElement = messageContext.getEnvelope().getBody().getFirstElement();
                            if (clonedFirstElement != null) {
                                clonedFirstElement.detach();
                                messageContext.getEnvelope().getBody().addChild(firstChild);
                            }
                        }// Had to do this because MessageHelper#cloneSOAPEnvelope does not clone OMSourcedElemImpl correctly.
                        // Fixing ESBJAVA-3178
                        origAxis2Ctx.setProperty("non.error.http.status.codes", getNonRetryStatusCodes());
                        outCtx = sender.send(ep, messageContext);
                       
                        isSuccessful = true; // isSuccessfull is true even session is not available because of avoiding the unwanted retries

                    } catch (Exception e) {

                        // this means send has failed due to some reason so we have to retry it
                        if (e instanceof SynapseException) {
                            isSuccessful = isNonRetryErrorCode(e.getCause().getMessage());
                        }
                        if (!isSuccessful) {
                            log.error("BlockingMessageSender of message processor ["+ this.messageProcessor.getName()
                                    + "] failed to send message to the endpoint");
                        }
                    }

                    if (isSuccessful) {
                        if (outCtx != null) {
                            if ("true".equals(outCtx.
                                    getProperty(ForwardingProcessorConstants.BLOCKING_SENDER_ERROR))) {

                                // this means send has failed due to some reason so we have to retry it
                                isSuccessful = isNonRetryErrorCode(
                                        (String) outCtx.getProperty(SynapseConstants.ERROR_MESSAGE));

                                if (isSuccessful) {
                                    sendThroughReplySeq(outCtx);
                                } else {
                                    // This means some error has occurred so must try to send down the fault sequence.
                                    log.error("BlockingMessageSender of message processor ["+ this.messageProcessor.getName()
                                            + "] failed to send message to the endpoint");
                                    sendThroughFaultSeq(outCtx);
                                }
                            }
                            else {
                                // Send the message down the reply sequence if there is one
                                sendThroughReplySeq(outCtx);
                                messageConsumer.ack();
                                attemptCount = 0;
                                isSuccessful = true;

                                if (log.isDebugEnabled()) {
                                    log.debug("Successfully sent the message to endpoint [" + ep.getName() +"]"
                                            + " with message processor [" + messageProcessor.getName() + "]");
                                }
                            }
                        }
                        else {
                            // This Means we have invoked an out only operation
                            // remove the message and reset the count
                            messageConsumer.ack();
                            attemptCount = 0;
                            isSuccessful = true;

                            if (log.isDebugEnabled()) {
                                log.debug("Successfully sent the message to endpoint [" + ep.getName() +"]"
                                        + " with message processor [" + messageProcessor.getName() + "]");
                            }
                        }
                    }

                    if (!isSuccessful) {
                            prepareToRetry(messageContext);
                    }
                    else {
                        if (messageProcessor.isPaused()) {
                            this.messageProcessor.resumeService();
                            log.info("Resuming the service of message processor [" + messageProcessor.getName() + "]");
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Message processor [" + messageProcessor.getName() + "] failed to send the message to" +
                        " client", e);
            }
        }
        else {
            //No Target Endpoint defined for the Message
            //So we do not have a place to deliver.
            //Here we log a warning and remove the message
            //todo: we can improve this by implementing a target inferring mechanism

            log.warn("Property " + ForwardingProcessorConstants.TARGET_ENDPOINT +
                    " not found in the message context , Hence removing the message ");

            messageConsumer.ack();
        }

        return true;
    }


    private Set<Integer> getNonRetryStatusCodes() {
        Set<Integer>nonRetryCodes = new HashSet<Integer>();
        if(nonRetryStatusCodes != null){
            for (String code : nonRetryStatusCodes) {
                try {
                    int codeI = Integer.parseInt(code);
                    nonRetryCodes.add(codeI);
                } catch (NumberFormatException e) {} // ignore the invalid status code
            }
        }
        return nonRetryCodes;
    }

    private boolean isNonRetryErrorCode(String errorMsg) {
        boolean isSuccess = false;
        if (nonRetryStatusCodes != null) {
            for (String code : nonRetryStatusCodes) {
                if (errorMsg != null && errorMsg.contains(code)) {
                    isSuccess = true;
                    break;
                }
            }
        }

        return isSuccess;
    }

    private void prepareToRetry(MessageContext messageContext) {
        if (!isTerminated) {
            // First stop the processor since no point in re-triggering jobs if the we can't send
            // it to the client
            if (!messageProcessor.isPaused()) {
                this.messageProcessor.pauseService();

                log.info("Pausing the service of message processor [" + messageProcessor.getName() + "]");
            }

            checkAndDeactivateProcessor(attemptCount, maxDeliverAttempts, messageContext);

            if (log.isDebugEnabled()) {
                log.debug("Failed to send to client retrying after " + retryInterval +
                        "s with attempt count - " + attemptCount);
            }

            try {
                // wait for some time before retrying
                Thread.sleep(retryInterval);
            } catch (InterruptedException ignore) {
                // No harm even it gets interrupted. So nothing to handle.
            }
        }
    }

    private void checkAndDeactivateProcessor(int attemptCount, int maxAttempts, MessageContext messageContext) {
        if (maxAttempts > 0) {
            this.attemptCount++;

            if (attemptCount >= maxAttempts) {

                if (this.isMaxDeliveryAttemptDropEnabled) {
                    continueMessageProcessor();
                    if (log.isDebugEnabled()) {
                        log.debug("Message processor ["
                                + messageProcessor.getName()
                                + "] Dropped the failed message and continue due to reach of max attempts");
                    }
                }  else  {
                        sendThroughReplySeq(messageContext);
                        continueMessageProcessor();
                    if (log.isDebugEnabled()) {
                        log.debug("Message processor ["
                                + messageProcessor.getName()
                                + "] stopped due to reach of max attempts");
                    }
                    }
                }
            }
    }

    private void continueMessageProcessor(){
        messageConsumer.ack();
        attemptCount = 0;
        isSuccessful = true;
        if (this.messageProcessor.isPaused()) {
            this.messageProcessor.resumeService();
        }
        log.info("Removed failed message and continue the message processor [" +this.messageProcessor.getName()+"]");
    }

}
