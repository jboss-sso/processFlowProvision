/*
 * Copyright 2012 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
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
package org.jboss.processFlow.knowledgeService;

import java.util.Collection;
import java.util.Date;

import javax.naming.Context;
import javax.naming.InitialContext;

import org.apache.commons.lang.StringUtils;
import org.drools.common.InternalWorkingMemory;
import org.drools.common.Scheduler.ActivationTimerJobContext;
import org.drools.impl.StatefulKnowledgeSessionImpl;
import org.drools.time.AcceptsTimerJobFactoryManager;
import org.drools.time.InternalSchedulerService;
import org.drools.time.Job;
import org.drools.time.JobContext;
import org.drools.time.JobHandle;
import org.drools.time.TimerService;
import org.drools.time.Trigger;
import org.drools.time.impl.CronExpression;
import org.drools.time.impl.CronTrigger;
import org.drools.time.impl.DefaultJobHandle;
import org.drools.time.impl.DefaultTimerJobFactoryManager;
import org.drools.time.impl.IntervalTrigger;
import org.drools.time.impl.TimerJobFactoryManager;
import org.drools.time.impl.TimerJobInstance;
import org.drools.time.SessionClock;
import org.jbpm.process.instance.timer.TimerManager.ProcessJobContext;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleTrigger;
import org.quartz.impl.StdSchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jboss.processFlow.knowledgeService.IBaseKnowledgeSession;
import org.jboss.processFlow.util.GlobalQuartzJobHandle;

/**
 * Quartz based <code>GlobalSchedulerService</code> that is configured according
 * to Quartz rules and allows to store jobs in data base. With that it survives 
 * server crashes and operates as soon as service is initialized without session 
 * being active.
 *
 */
public class QuartzSchedulerService implements TimerService, InternalSchedulerService, AcceptsTimerJobFactoryManager, SessionClock  {
    
    public static final String TIMER_JOB_HANDLE = "timerJobHandle";
    public static final String JOB_GROUP = "jbpm";
    public static final String PROCESS_JOB = "ProcessJob";
    public static final String ACTIVATION_TIMER_JOB = "ActivationTimerJob";
    public static final String RULE_NAME_START_PREFIX = "RuleFlow-Start-";
    
    private static final Logger log = LoggerFactory.getLogger(QuartzSchedulerService.class);

    // Quartz Scheduler
    private static Scheduler scheduler;
    
    // For purposes of PER_PROCESS_INSTANCE architecture, will never populate the drools TimerJobFactoryManager
    // subsequently, the drools session will no longer include the job trigger
    // the job trigger will always be managed by this implementation's Quartz scheduler
    private static TimerJobFactoryManager jobFactoryManager = DefaultTimerJobFactoryManager.instance;
   
    // used to signal the process instance
    private static IBaseKnowledgeSession kSessionProxy;
    
    public static void start() throws Exception{
        Context jndiContext;
        jndiContext = new InitialContext();
        kSessionProxy = (IBaseKnowledgeSession)jndiContext.lookup(IBaseKnowledgeSession.BASE_JNDI);
        scheduler = StdSchedulerFactory.getDefaultScheduler();            
        scheduler.start();
    }
    
    public static void stop() throws Exception {
        scheduler.shutdown();
        scheduler = null;
    }
    
    public JobHandle scheduleJob(Job job, JobContext ctx, Trigger droolsTrig) {
        GlobalQuartzJobHandle quartzJobHandle= null;
        if (ctx instanceof ProcessJobContext) {
            // seems to be used with timers using drools timer expression 
            ProcessJobContext processCtx = (ProcessJobContext) ctx;
            StatefulKnowledgeSessionImpl wM = (StatefulKnowledgeSessionImpl)processCtx.getKnowledgeRuntime();
            String jobname = PROCESS_JOB +"-"+processCtx.getProcessInstanceId() + "-" + processCtx.getTimer().getId();
            quartzJobHandle = new GlobalQuartzJobHandle(jobname, JOB_GROUP, wM.getId());
        } else if (ctx instanceof ActivationTimerJobContext) {
            // seems to be used with timers using cron expression
            InternalWorkingMemory wM =  (InternalWorkingMemory)((ActivationTimerJobContext)ctx).getAgenda().getWorkingMemory();
            //String processId = ((ActivationTimerJobContext) ctx).getScheduledAgendaItem().
            String ruleName = ((ActivationTimerJobContext)ctx).getScheduledAgendaItem().getRule().getName();
            String processId = StringUtils.substringAfter(ruleName, RULE_NAME_START_PREFIX);
            String jobname = ACTIVATION_TIMER_JOB +"-"+processId+"-"+wM.getId();
            quartzJobHandle = new GlobalQuartzJobHandle(jobname, JOB_GROUP, wM.getId());
        } else {
            throw new RuntimeException("scheduleJob() unknown jobContext = "+ctx);
        }
        
        try {
            org.quartz.Trigger triggerObj = configureJobHandleAndQuartzTrigger(quartzJobHandle, droolsTrig);
            
            // Define a quartz job detail instance and add jobHandle to its Map
            JobDetail jdetail = new JobDetail(quartzJobHandle.getJobName(), quartzJobHandle.getJobGroup(), QuartzJob.class);
            jdetail.getJobDataMap().put(TIMER_JOB_HANDLE, quartzJobHandle);
            
            if (null == scheduler.getJobDetail(quartzJobHandle.getJobName(), quartzJobHandle.getJobGroup())) {
                scheduler.scheduleJob(jdetail, triggerObj);
            } else {
                // need to add the job again to replace existing especially important if jobs are persisted in db
                log.warn("scheduleJob() uh-oh!!!! this job was already scheduled : "+quartzJobHandle.getJobName());
                scheduler.addJob(jdetail, true);
                triggerObj.setJobName(quartzJobHandle.getJobName());
                triggerObj.setJobGroup(quartzJobHandle.getJobGroup());
                scheduler.rescheduleJob(quartzJobHandle.getJobName()+"_trigger", quartzJobHandle.getJobGroup(), triggerObj);
            }

            // drools legacy:   triggers are not to be stored in drools sessions
            //TimerJobInstance jobInstance = this.jobFactoryManager.createTimerJobInstance( job, ctx, trigger, quartzJobHandle, this);
            //this.jobFactoryManager.addTimerJobInstance( timerJobInstance );
            
            // drools legacy:   return a DefaultJobHandle
            return new DefaultJobHandle(0L);
        } catch (Exception x) {
            throw new RuntimeException(x);
        }
    }
    
    private static org.quartz.Trigger configureJobHandleAndQuartzTrigger(GlobalQuartzJobHandle jHandle, org.drools.time.Trigger droolsTrig) throws Exception{
        org.quartz.Trigger quartzTrig = null;
        String jName = jHandle.getJobName()+"_trigger";
        String jGroup = jHandle.getJobGroup();
        if(droolsTrig instanceof IntervalTrigger){
            IntervalTrigger iTrig = (IntervalTrigger)droolsTrig;
            Date firstFire = iTrig.hasNextFireTime();
            
            // drools repeatLimit = quartz repeatCount
            int repeatCount = iTrig.getRepeatLimit();
            
            long interval = iTrig.getPeriod();
            if(interval <= 0)
                repeatCount = 0;
            quartzTrig = new SimpleTrigger(jName, jGroup, firstFire, iTrig.getEndTime(), repeatCount, interval);
            jHandle.setInterval(interval);
            jHandle.setTimerExpression(iTrig.toString());
        }else if(droolsTrig instanceof CronTrigger){
            CronTrigger cTrigger = (CronTrigger)droolsTrig;
            CronExpression cExpression = cTrigger.getCronEx();
            //Date onlyFire = cTrigger.getNextFireTime();
            //quartzTrig = new SimpleTrigger(jName, jGroup, onlyFire);  // fires one time and does not repeat
            quartzTrig = new org.quartz.CronTrigger(jName, jGroup, cExpression.getCronExpression());
        }else {
            throw new RuntimeException("configureJobHandleAndQuartzTrigger() need to implement appropriate handling of the following type of trigger : "+droolsTrig.getClass().toString());
        }
        return quartzTrig;
    }

    public static boolean deleteJob(GlobalQuartzJobHandle jobHandle) {
        try {
            return scheduler.deleteJob(jobHandle.getJobName(), jobHandle.getJobGroup());            
        } catch (Exception x) {
            throw new RuntimeException(x);
        }
    }
    
    public static String getCurrentTimerJobsAsJson(String jobGroup) throws SchedulerException{
        StringBuilder sBuilder = new StringBuilder("{\n\t\"jobs\":[\n\t");
        String[] jobNames = scheduler.getJobNames(jobGroup);
        if(jobNames.length > 0){
            int x = 0;
            for(String name : jobNames){
                if(x>0)
                    sBuilder.append(",\n\t");
                // show the initial jobDetail for now.  could expand by also listing current triggers
                JobDetail jDetail = scheduler.getJobDetail(name, jobGroup);
                GlobalQuartzJobHandle jHandle = (GlobalQuartzJobHandle) jDetail.getJobDataMap().get(TIMER_JOB_HANDLE);
                sBuilder.append("\t{\"jName\":\"");
                sBuilder.append(name);
                sBuilder.append("\":\"timer\":\"");
                sBuilder.append(jHandle.getTimerExpression());
                sBuilder.append("\"");
                x++;
            }
        }
        sBuilder.append("\n\t]\n}");
        return sBuilder.toString();
    }
    
    public static int purgeCurrentTimerJobs(String jobGroup) throws SchedulerException{
        String[] jobNames = scheduler.getJobNames(jobGroup);
        for(String name     : jobNames){
            scheduler.deleteJob(name, jobGroup);
        }
        return jobNames.length;
    }
    
    public static class QuartzJob implements org.quartz.Job {

        public QuartzJob() {}

        public void execute(JobExecutionContext qContext) throws JobExecutionException {
            GlobalQuartzJobHandle jHandle = (GlobalQuartzJobHandle)(qContext.getMergedJobDataMap().get(QuartzSchedulerService.TIMER_JOB_HANDLE));
            if(qContext.getNextFireTime() == null)
                jHandle.setInterval(0L);
            int pState = kSessionProxy.processJobExecutionContext(qContext);
            /*  could delete job in quartz scheduler;  but does not seem to be a use case where this is required.
             *  quartz will flush its scheduler of a job once that job no longer has a nextFireTime
            if(ProcessInstance.STATE_COMPLETED == pState || jHandle.getInterval() == 0){
                boolean dSuccess = deleteJob(jHandle);
                log.info("execute() just signaled.  pState = "+pState+" : jobName = "+jHandle.getJobName()+" : successful deletion = "+dSuccess);
            }else {
                log.info("execute() just signaled.  pState = "+pState+" : jobName = "+jHandle.getJobName());
            }
            */
        }
    }
   
    
/*  ***********************                    LEGACY  FUNCTIONS        *******************************/
    public boolean removeJob(JobHandle jobHandle) {
        /*  NOTE:  BRMS5.3.1, as part of disposing of a session, this function will be invoked.
        Subsequently, timer will never fire.
        Will move this functionality to a 'deleteJob' function that is invoked when needed
         */ 
        return true;
    }
    public void shutdown() { 
        //log.warn("shutdown() function not valid");
    }
    public void internalSchedule(TimerJobInstance timerJobInstance) {
        // not needed, all scheduling functionality is handled in:  scheduleJob(...)
    }
    public void forceShutdown() {
        //log.warn("forceShutdown() function not valid");
    }
    public synchronized void initScheduler() {
        // will handle this in start() function
    }
   
    /* 
    public JobHandle buildJobHandleForContext(NamedJobContext ctx) {
        return null;
    }
    */

    @Override
    public long getCurrentTime() {
        return System.currentTimeMillis();
    }

    @Override
    public long getTimeToNextJob() {
        return 0;
    }

    @Override
    public Collection<TimerJobInstance> getTimerJobInstances() {
        return jobFactoryManager.getTimerJobInstances();
    }

    @Override
    public TimerJobFactoryManager getTimerJobFactoryManager() {
        return this.jobFactoryManager;
    }

    @Override
    public void setTimerJobFactoryManager(TimerJobFactoryManager arg0) {
        this.jobFactoryManager= arg0;
    }
}
