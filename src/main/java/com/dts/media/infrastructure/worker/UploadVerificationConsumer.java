package com.dts.media.infrastructure.worker;

import com.dts.media.application.dto.UploadVerificationMessage;
import com.dts.media.application.service.UploadService;
import com.dts.media.infrastructure.configuration.KafkaConfiguration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Lazy(false)
@RequiredArgsConstructor
public class UploadVerificationConsumer {

    private final UploadService uploadService;

    @KafkaListener(
            topics = KafkaConfiguration.TOPIC_UPLOAD_VERIFICATION,
            groupId = "media-upload-worker",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(UploadVerificationMessage message) {
        log.info("Received verification message for sessionId: {}, mediaId: {}", message.getSessionId(), message.getMediaId());
        try {
            uploadService.verifyUpload(message.getSessionId(), message.getMediaId());
            log.info("Finished verification for sessionId: {}", message.getSessionId());
        } catch (Exception e) {
            log.error("Failed to verify upload for sessionId: {}", message.getSessionId(), e);
        }
    }
}
