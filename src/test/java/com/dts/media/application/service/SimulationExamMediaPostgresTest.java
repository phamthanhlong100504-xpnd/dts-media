package com.dts.media.application.service;

import com.dts.media.application.enums.MediaStatus;
import com.dts.media.domain.entity.Media;
import com.dts.media.domain.repository.MediaRepository;
import com.dts.media.domain.repository.StorageRepository;
import com.dts.media.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.*;
import okhttp3.Headers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(showSql=false)
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes=SimulationExamMediaPostgresTest.Config.class)
@Import({SimulationExamMediaService.class,SimulationExamObjectProbe.class,SimulationExamTicketVerifier.class,MediaService.class,MediaStorageGuard.class})
@TestPropertySource(properties="jwt.access-token.secret="+SimulationExamTestTokens.SECRET)
@Testcontainers
class SimulationExamMediaPostgresTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    @Configuration(proxyBeanMethods=false) @EntityScan(basePackageClasses=Media.class) @EnableJpaRepositories(basePackageClasses=MediaRepository.class)
    static class Config {
        @Bean @Primary MinioClient minioClient(){return mock(MinioClient.class);}
        @Bean MinioClient internalMinioClient(){return mock(MinioClient.class);}
        @Bean ObjectMapper objectMapper(){return new ObjectMapper();}
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);
        r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");r.add("spring.jpa.hibernate.ddl-auto",()->"validate");r.add("spring.jpa.properties.hibernate.default_schema",()->"public");
        r.add("spring.flyway.enabled",()->"true");r.add("spring.flyway.url",postgres::getJdbcUrl);r.add("spring.flyway.user",postgres::getUsername);r.add("spring.flyway.password",postgres::getPassword);
        r.add("spring.flyway.schemas",()->"public");r.add("spring.flyway.default-schema",()->"public");r.add("spring.flyway.target",()->"7");r.add("spring.flyway.baseline-on-migrate",()->"false");
        r.add("spring.flyway.validate-on-migrate",()->"true");r.add("spring.flyway.locations",()->"classpath:db/migration");
    }
    @Autowired SimulationExamMediaService service;@Autowired MediaRepository medias;@Autowired StorageRepository storages;@Autowired JdbcTemplate jdbc;
    @Autowired MinioClient minio;@Autowired @Qualifier("internalMinioClient")MinioClient internal;
    UUID user,id,session;Media media;
    String token(String kind){return SimulationExamTestTokens.ticket(kind,SimulationExamTestTokens.claims(kind,user,id,session));}
    @BeforeEach void fixture()throws Exception {
        reset(minio,internal);user=UUID.randomUUID();id=UUID.randomUUID();session=UUID.randomUUID();
        MediaTestActors.set(user,Set.of("ROLE_STUDENT"),"media:read","simulations:read","simulations:exam");
        var storage=storages.findAll().stream().filter(s->s.getBucket().equals("media")&&s.getDeletedAt()==null).findFirst().orElseThrow();
        media=Media.builder().id(id).storageId(storage.getId()).objectKey("uploads/2026/11/02/"+id+".mp4").originalFilename("fixture.mp4").extension("mp4")
                .mimeType("video/mp4").sizeBytes(1024L).mediaType("VIDEO").visibility("PRIVATE").uploadTargetType("SIMULATION_VIDEO").status(MediaStatus.READY)
                .metadata("{}").createdBy(UUID.randomUUID()).createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now()).build();medias.saveAndFlush(media);
        when(internal.getBucketPolicy(any())).thenReturn("");var stat=mock(StatObjectResponse.class);
        when(stat.size()).thenReturn(1024L);when(stat.contentType()).thenReturn("video/mp4");when(stat.etag()).thenReturn(SimulationExamTestTokens.ETAG);when(internal.statObject(any())).thenReturn(stat);
        when(internal.getObject(any())).thenAnswer(i->{GetObjectArgs a=i.getArgument(0);byte[]b=SimulationExamTestTokens.bytes();return new GetObjectResponse(new Headers.Builder().add("Content-Range","bytes 0-15/1024").build(),"media",null,"fixture",new ByteArrayInputStream(a.length()==null?b:Arrays.copyOf(b,16)));});
        when(minio.getPresignedObjectUrl(any())).thenReturn("http://localhost:9000/media/uploads/2026/11/02/"+id+".mp4?fixture-only");
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    Map<String,List<String>> rows() {
        var result=new TreeMap<String,List<String>>();
        for(String table:jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename",String.class)) {
            assertTrue(table.matches("[a-z_]+"));result.put(table,jdbc.queryForList("SELECT to_jsonb(t)::text FROM "+table+" t ORDER BY to_jsonb(t)::text",String.class));
        }
        return result;
    }
    @Test void actualMigratedMetadataCanBeReadWithoutChangingAnyBusinessRowsOrHistory() {
        var before=rows();var result=service.eligibility(token("eligibility"));assertTrue(result.items().getFirst().ready());assertEquals(before,rows());
        assertEquals(7L,jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version IS NOT NULL AND success",Long.class));
        verifyNoInteractions(minio);
    }
    @Test void currentAndReviewPlaybackUseReadOnlyTransactionsAndDoNotWriteMediaOrUploadState()throws Exception {
        TestTransaction.flagForCommit();TestTransaction.end();var before=rows();
        when(internal.getBucketPolicy(any())).thenAnswer(i->{assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());return "";});
        var current=service.playback(token("playback"),false);var review=service.playback(token("review"),true);
        assertEquals(id,current.mediaId());assertEquals("CURRENT",current.usage());assertEquals("REVIEW",review.usage());assertEquals(before,rows());
        verify(minio,times(2)).getPresignedObjectUrl(any());
    }
    @Test void softDeletedAndUnreadyRowsRemainUnchangedAndCannotBecomeEligible() {
        media.setDeletedAt(OffsetDateTime.now());medias.saveAndFlush(media);var before=rows();
        assertEquals("MISSING",service.eligibility(token("eligibility")).items().getFirst().reason());assertEquals(before,rows());verifyNoInteractions(internal,minio);
        media.setDeletedAt(null);media.setStatus(MediaStatus.UPLOADING);medias.saveAndFlush(media);before=rows();
        assertEquals("NOT_READY",service.eligibility(token("eligibility")).items().getFirst().reason());assertEquals(before,rows());
    }
    @Test void anotherActorCannotUseTheStoredAssetEvenWithAValidExamPermission() {
        String ticket=token("playback");var before=rows();MediaTestActors.set(UUID.randomUUID(),Set.of("ROLE_STUDENT"),"media:read","simulations:read","simulations:exam");
        assertEquals(403,assertThrows(ResponseStatusException.class,()->service.playback(ticket,false)).getStatusCode().value());assertEquals(before,rows());verifyNoInteractions(internal,minio);
    }
    @Test void changedObjectCannotIssueUrlOrChangeDatabaseRows()throws Exception {
        var stat=mock(StatObjectResponse.class);when(stat.size()).thenReturn(1024L);when(stat.contentType()).thenReturn("video/mp4");when(stat.etag()).thenReturn("differentetag");when(internal.statObject(any())).thenReturn(stat);
        var before=rows();assertEquals(409,assertThrows(ResponseStatusException.class,()->service.playback(token("playback"),false)).getStatusCode().value());assertEquals(before,rows());verifyNoInteractions(minio);
    }
    @Test void publicBucketPolicyFailsClosedWithoutUpdatingMediaOrOtherTables()throws Exception {
        when(internal.getBucketPolicy(any())).thenReturn("{\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":\"*\",\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}]}");
        var before=rows();assertEquals(503,assertThrows(ResponseStatusException.class,()->service.playback(token("playback"),false)).getStatusCode().value());assertEquals(before,rows());verifyNoInteractions(minio);
    }
}
