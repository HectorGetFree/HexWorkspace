package com.hector.trigger.mq;

import com.hector.domain.document.service.IDocumentUploadTaskService;
import jakarta.annotation.Resource;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/** 文档上传任务消费者。 */
@Component
public class DocumentUploadTaskListener {

    @Resource
    private IDocumentUploadTaskService uploadTaskService;

    @RabbitListener(queues = "${hexworkspace.mq.document-upload.queue}")
    public void consume(String taskCode) {
        uploadTaskService.execute(taskCode);
    }
}
