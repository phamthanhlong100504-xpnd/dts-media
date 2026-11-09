package com.dts.media.security;

import com.dts.media.api.form.InitializeUploadForm;
import com.dts.media.application.dto.UploadVerificationMessage;
import com.dts.media.application.dto.UploadVerificationEvent;
import com.dts.media.application.enums.UploadSessionStatus;
import com.dts.media.application.service.UploadService;
import com.dts.media.application.service.MediaStorageGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.GetBucketPolicyArgs;
import org.springframework.beans.factory.annotation.Qualifier;
import com.dts.media.domain.entity.Media;
import com.dts.media.domain.repository.*;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.*;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DataJpaTest(showSql=false)
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes=MediaUploadPostgresTest.Config.class)
@Import({UploadService.class,MediaStorageGuard.class})
@Testcontainers
@RecordApplicationEvents
class MediaUploadPostgresTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    @Configuration(proxyBeanMethods=false)
    @EntityScan(basePackageClasses=Media.class)
    @EnableJpaRepositories(basePackageClasses=MediaRepository.class)
    static class Config {
        @Bean @Primary MinioClient minioClient() {return mock(MinioClient.class);}
        @Bean MinioClient internalMinioClient() {return mock(MinioClient.class);}
        @Bean ObjectMapper objectMapper() {return new ObjectMapper();}
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);
        r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");r.add("spring.jpa.hibernate.ddl-auto",()->"validate");
        r.add("spring.jpa.properties.hibernate.default_schema",()->"public");r.add("spring.flyway.enabled",()->"true");
        r.add("spring.flyway.url",postgres::getJdbcUrl);r.add("spring.flyway.user",postgres::getUsername);r.add("spring.flyway.password",postgres::getPassword);
        r.add("spring.flyway.schemas",()->"public");r.add("spring.flyway.default-schema",()->"public");r.add("spring.flyway.target",()->"7");
        r.add("spring.flyway.baseline-on-migrate",()->"false");r.add("spring.flyway.validate-on-migrate",()->"true");r.add("spring.flyway.locations",()->"classpath:db/migration");
    }
    @Autowired UploadService uploads;
    @Autowired MediaRepository medias;
    @Autowired UploadSessionRepository sessions;
    @Autowired StorageRepository storages;
    @Autowired EntityManager entities;
    @Autowired ApplicationEvents events;
    @Autowired MinioClient minio;
    @Autowired @Qualifier("internalMinioClient") MinioClient internal;
    @MockBean KafkaTemplate<String,UploadVerificationMessage> kafka;
    UUID owner;
    @BeforeEach void actor() throws Exception {
        owner=UUID.randomUUID();MediaTestActors.set(owner,Set.of("ROLE_STUDENT"),"media:upload");
        when(minio.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class))).thenReturn("http://localhost:9000/fixture-only");
        when(internal.getBucketPolicy(any(GetBucketPolicyArgs.class))).thenReturn("{}");
    }
    @AfterEach void clear() {SecurityContextHolder.clearContext();}
    InitializeUploadForm form() {
        var f=new InitializeUploadForm();f.setFileName("fixture.png");f.setMimeType("image/png");f.setSizeBytes(123L);f.setTargetType("USER");f.setVisibility("PRIVATE");return f;
    }
    @Test void initializePersistsOwnerTargetAndPrivateVisibilityThroughTheMigratedJpaMapping() {
        var receipt=uploads.initializeUpload(form(),owner.toString());
        var media=medias.findById(UUID.fromString(receipt.getMediaId())).orElseThrow();
        assertEquals(owner,media.getCreatedBy());assertEquals("USER",media.getUploadTargetType());assertEquals("PRIVATE",media.getVisibility());
        assertEquals("media-private",storages.findById(media.getStorageId()).orElseThrow().getBucket());
        var session=sessions.findById(UUID.fromString(receipt.getSessionId())).orElseThrow();
        assertEquals(owner,session.getUploaderId());assertEquals(media.getId(),session.getMediaId());assertEquals(UploadSessionStatus.PENDING,session.getStatus());
    }
    @Test void realPessimisticLockQuerySupportsIdempotentConfirmationAndPublishesOnlyAfterCommit() {
        var receipt=uploads.initializeUpload(form(),owner.toString());UUID session=UUID.fromString(receipt.getSessionId());
        var first=uploads.confirmUpload(session,owner.toString());var second=uploads.confirmUpload(session,owner.toString());
        assertEquals(first.getMediaId(),second.getMediaId());assertEquals(UploadSessionStatus.UPLOADING,sessions.findById(session).orElseThrow().getStatus());
        assertEquals(1L, events.stream(UploadVerificationEvent.class).count());
        // Spring may call KafkaTemplate lifecycle methods while creating the bean.
        // Check the business side effect: no send before commit, exactly one after.
        verify(kafka, never()).send(anyString(), anyString(), any(UploadVerificationMessage.class));
        TestTransaction.flagForCommit();
        TestTransaction.end();
        var message = org.mockito.ArgumentCaptor.forClass(UploadVerificationMessage.class);
        verify(kafka, times(1)).send(anyString(), eq(session.toString()), message.capture());
        assertEquals(session, message.getValue().getSessionId());
        assertEquals(UUID.fromString(receipt.getMediaId()), message.getValue().getMediaId());
    }
    @Test void otherOwnerCannotConfirmExistingDatabaseSessionOrChangeItsState() {
        var receipt=uploads.initializeUpload(form(),owner.toString());UUID session=UUID.fromString(receipt.getSessionId()),other=UUID.randomUUID();
        MediaTestActors.set(other,Set.of("ROLE_STUDENT"),"media:upload");
        assertThrows(SecurityException.class,()->uploads.confirmUpload(session,other.toString()));
        assertEquals(UploadSessionStatus.PENDING,sessions.findById(session).orElseThrow().getStatus());
    }
    @Test void sharedTargetCannotBeInsertedByLearnerEvenWithAValidPolicyInPostgres() {
        long before=medias.count();var f=form();f.setTargetType("SIMULATION_VIDEO");f.setFileName("fixture.mp4");f.setMimeType("video/mp4");
        assertThrows(SecurityException.class,()->uploads.initializeUpload(f,owner.toString()));assertEquals(before,medias.count());
    }
    @ParameterizedTest
    @EnumSource(value = UploadSessionStatus.class, names = {"FAILED", "CANCELLED", "EXPIRED"})
    void terminalStatesRoundTripThroughPostgresAndCannotBeReconfirmed(UploadSessionStatus status) {
        var receipt = uploads.initializeUpload(form(), owner.toString());
        UUID id = UUID.fromString(receipt.getSessionId());
        var session = sessions.findById(id).orElseThrow();
        session.setStatus(status);
        sessions.saveAndFlush(session);
        entities.clear();
        assertEquals(status, sessions.findById(id).orElseThrow().getStatus());
        var failure = assertThrows(ResponseStatusException.class, () -> uploads.confirmUpload(id, owner.toString()));
        assertEquals(409, failure.getStatusCode().value());
        assertEquals(0L, events.stream(UploadVerificationEvent.class).count());
        verify(kafka, never()).send(anyString(), anyString(), any(UploadVerificationMessage.class));
    }
}
