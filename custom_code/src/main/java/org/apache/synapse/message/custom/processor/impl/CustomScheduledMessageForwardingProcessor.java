package org.apache.synapse.message.custom.processor.impl;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.SynapseException;
import org.apache.synapse.message.processor.MessageProcessorConstants;
import org.apache.synapse.message.processor.impl.forwarder.ScheduledMessageForwardingProcessor;
import org.quartz.*;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.quartz.SimpleScheduleBuilder.simpleSchedule;
import static org.quartz.TriggerBuilder.newTrigger;

public class CustomScheduledMessageForwardingProcessor extends ScheduledMessageForwardingProcessor {

    private AtomicBoolean isActivated = new AtomicBoolean(true);

    private static final Log logger = LogFactory.getLog(CustomScheduledMessageForwardingProcessor.class.getName());

    public boolean start() {

        System.out.println("CustomScheduledMessageForwardingProcessor has been invoked");


//        try {
//            if (isActivated.get()) {
//                setMessageConsumer(configuration.getMessageStore(messageStore).getConsumer());
//                scheduler.start();
//
//                if (logger.isDebugEnabled()) {
//                    logger.debug("Started custom message processor. [" + getName() + "].");
//                }
//            }
//        } catch (SchedulerException e) {
//            throw new SynapseException("Error starting the scheduler", e);
//        }
//
//        Trigger trigger;
//        TriggerBuilder<Trigger> triggerBuilder = newTrigger().withIdentity(name + "-trigger");
//
//        if (cronExpression == null || "".equals(cronExpression)) {
//            trigger = triggerBuilder
//                    .withSchedule(simpleSchedule()
//                            .withIntervalInMilliseconds(isThrottling(this.interval) ? 1000 : this.interval)
//                            .repeatForever()
//                            .withMisfireHandlingInstructionNextWithRemainingCount())
//                    .build();
//        } else {
//            trigger = triggerBuilder
//                    .startNow()
//                    .withSchedule(CronScheduleBuilder.cronSchedule(cronExpression)
//                            .withMisfireHandlingInstructionDoNothing())
//                    .build();
//        }
//
//        JobDataMap jobDataMap = getJobDataMap();
//        jobDataMap.put(MessageProcessorConstants.PARAMETERS, parameters);
//
//        JobBuilder jobBuilder = JobBuilder.newJob(CustomForwardingService.class);
//        jobBuilder.withIdentity(name + "-job", MessageProcessorConstants.SCHEDULED_MESSAGE_PROCESSOR_GROUP);
//        JobDetail jobDetail = jobBuilder.usingJobData(jobDataMap).build();
//
//        try {
//            scheduler.scheduleJob(jobDetail, trigger);
//        } catch (SchedulerException e) {
//            throw new SynapseException("Error scheduling job : " + jobDetail
//                    + " with trigger " + trigger, e);
//        }

        System.out.println("CustomScheduledMessageForwardingProcessor has been invoked");

        return true;
    }

    public void setParameters(Map<String, Object> parameters) {

        super.setParameters(parameters);

        if (parameters != null && !parameters.isEmpty()) {
            Object o = parameters.get(MessageProcessorConstants.IS_ACTIVATED);
            if (o != null) {
                isActivated.set(Boolean.valueOf(o.toString()));
            }
        }
    }
}
