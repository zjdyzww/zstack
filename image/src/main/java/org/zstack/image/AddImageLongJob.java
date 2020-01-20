package org.zstack.image;

import org.springframework.beans.factory.annotation.Autowire;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;
import org.zstack.core.Platform;
import org.zstack.core.cloudbus.AutoOffEventCallback;
import org.zstack.core.cloudbus.CloudBus;
import org.zstack.core.cloudbus.CloudBusCallBack;
import org.zstack.core.cloudbus.EventFacade;
import org.zstack.core.db.DatabaseFacade;
import org.zstack.header.core.ReturnValueCompletion;
import org.zstack.header.errorcode.ErrorCode;
import org.zstack.header.image.*;
import org.zstack.header.longjob.*;
import org.zstack.header.message.APIEvent;
import org.zstack.header.message.MessageReply;
import org.zstack.longjob.LongJobGlobalConfig;
import org.zstack.longjob.LongJobUtils;
import org.zstack.utils.Utils;
import org.zstack.utils.gson.JSONObjectUtil;
import org.zstack.utils.logging.CLogger;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.zstack.longjob.LongJobUtils.*;


/**
 * Created by on camile 2018/2/2.
 */
@LongJobFor(APIAddImageMsg.class)
@Configurable(preConstruction = true, autowire = Autowire.BY_TYPE)
public class AddImageLongJob implements LongJob {
    private static final CLogger logger = Utils.getLogger(AddImageLongJob.class);

    @Autowired
    protected CloudBus bus;
    @Autowired
    protected DatabaseFacade dbf;
    @Autowired
    protected EventFacade evtf;

    protected String auditResourceUuid;

    class AddImageCompletion<T extends ImageInventory> extends ReturnValueCompletion<T> {
        APIAddImageEvent event;
        LongJobVO job;
        ReturnValueCompletion<APIEvent> completion;
        AtomicBoolean done = new AtomicBoolean(false);

        AddImageCompletion(APIAddImageEvent event, LongJobVO job, ReturnValueCompletion<APIEvent> completion) {
            super(completion);
            this.job = job;
            this.event = event;
            this. completion = completion;
        }

        @Override
        public void success(ImageInventory image) {
            if (done.compareAndSet(false, true)) {
                event.setInventory(image);
                job = updateByUuid(job.getUuid(), vo -> vo.setJobResult(JSONObjectUtil.toJsonString(event)));
                completion.success(event);
            }
        }

        @Override
        public void fail(ErrorCode err) {
            if (done.compareAndSet(false, true)) {
                job = updateByUuid(job.getUuid(), vo -> vo.setJobResult(wrapDefaultReuslt(vo, err)));
                completion.fail(err);
            }
        }

        public void track(ImageInventory inv) {
            if (!done.get()) {
                event.setInventory(inv);
                job = updateByUuid(job.getUuid(), vo -> vo.setJobResult(JSONObjectUtil.toJsonString(event)));
            }
        }

        void startTrack() {
            long offTime = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(LongJobGlobalConfig.LONG_JOB_DEFAULT_TIMEOUT.value(Long.class));
            evtf.on(ImageCanonicalEvents.IMAGE_TRACK_RESULT_PATH, new AutoOffEventCallback() {
                @Override
                protected boolean run(Map tokens, Object d) {
                    ImageCanonicalEvents.ImageTrackData data = (ImageCanonicalEvents.ImageTrackData) d;
                    if (data.getUuid().equals(job.getTargetResourceUuid())) {
                        handleResult(data);
                        return true;
                    } else if (offTime > System.currentTimeMillis()) {
                        return true;
                    }

                    return false;
                }

                private void handleResult(ImageCanonicalEvents.ImageTrackData data) {
                    if (data.isSuccess()) {
                        success(data.getInventory());
                    } else {
                        fail(data.getError());
                    }
                }
            });
        }
    }

    @Override
    public void start(LongJobVO job, ReturnValueCompletion<APIEvent> compl) {
        AddImageMsg msg = JSONObjectUtil.toObject(job.getJobData(), AddImageMsg.class);
        if (msg.getResourceUuid() == null) {
            msg.setResourceUuid(Platform.getUuid());
            job.setJobData(JSONObjectUtil.toJsonString(msg));
            job.setTargetResourceUuid(msg.getResourceUuid());
            dbf.updateAndRefresh(job);
        }

        APIAddImageEvent evt = new APIAddImageEvent(job.getApiId());

        AddImageCompletion completion = new AddImageCompletion(evt, job, compl);
        if (msg.needTrack()) {
            completion.startTrack();
        }

        bus.makeTargetServiceIdByResourceUuid(msg, ImageConstant.SERVICE_ID, msg.getResourceUuid());
        bus.send(msg, new CloudBusCallBack(completion) {
            @Override
            public void run(MessageReply reply) {
                if (reply.isSuccess()) {
                    handleSuccess(reply);
                } else {
                    auditResourceUuid = msg.getResourceUuid();
                    completion.fail(reply.getError());
                }
            }

            private void handleSuccess(MessageReply reply) {
                AddImageReply r = reply.castReply();

                auditResourceUuid = r.getInventory().getUuid();
                if (jobCanceled(job.getUuid())) {
                    cleanImage(msg, completion, cancelErr(job.getUuid()));
                } else if (msg.needTrack()) {
                    completion.track(r.getInventory());
                } else {
                    completion.success(r.getInventory());
                }
            }
        });
    }

    @Override
    public void resume(LongJobVO job) {
        AddImageMsg msg = JSONObjectUtil.toObject(job.getJobData(), AddImageMsg.class);
        ImageDeletionMsg dmsg = buildDeletionMsg(msg);
        bus.send(dmsg, new CloudBusCallBack(null) {
            @Override
            public void run(MessageReply reply) {
                if (!reply.isSuccess()) {
                    logger.warn(String.format("delete image [%s] failed after management node restarted", msg.getResourceUuid()));
                }

                LongJobUtils.changeState(job.getUuid(), LongJobStateEvent.fail,
                        vo -> vo.setJobResult("Failed because management node restarted."));
            }
        });
    }

    private void cleanImage(AddImageMsg msg, AddImageCompletion completion, ErrorCode err) {
        ImageDeletionMsg dmsg = buildDeletionMsg(msg);
        bus.send(dmsg, new CloudBusCallBack(completion) {
            @Override
            public void run(MessageReply reply) {
                completion.fail(err);
            }
        });
    }

    @Override
    public void cancel(LongJobVO job, ReturnValueCompletion<Boolean> completion) {
        CancelAddImageMsg msg = new CancelAddImageMsg();
        AddImageMsg amsg = JSONObjectUtil.toObject(job.getJobData(), AddImageMsg.class);
        msg.setMsg(amsg);
        msg.setImageUuid(amsg.getResourceUuid());
        msg.setCancellationApiId(job.getApiId());
        bus.makeTargetServiceIdByResourceUuid(msg, ImageConstant.SERVICE_ID, msg.getImageUuid());
        bus.send(msg, new CloudBusCallBack(completion) {
            @Override
            public void run(MessageReply reply) {
                if (reply.isSuccess()) {
                    completion.success(false);
                } else {
                    completion.fail(reply.getError());
                }
            }
        });
    }

    @Override
    public Class getAuditType() {
        return ImageVO.class;
    }

    @Override
    public String getAuditResourceUuid() {
        return auditResourceUuid;
    }

    private ImageDeletionMsg buildDeletionMsg(AddImageMsg msg) {
        ImageDeletionMsg dmsg = new ImageDeletionMsg();
        dmsg.setImageUuid(msg.getResourceUuid());
        dmsg.setForceDelete(true);
        dmsg.setBackupStorageUuids(msg.getBackupStorageUuids());
        dmsg.setDeletionPolicy(ImageDeletionPolicyManager.ImageDeletionPolicy.Direct.toString());
        bus.makeTargetServiceIdByResourceUuid(dmsg, ImageConstant.SERVICE_ID, dmsg.getImageUuid());
        return dmsg;
    }
}
