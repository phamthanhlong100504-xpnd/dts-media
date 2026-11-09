package com.dts.media.security;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.DriverManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class MediaMigrationPostgresTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    record Database(String url,JdbcTemplate jdbc) {
        Flyway flyway(String target) {return Flyway.configure().dataSource(url,postgres.getUsername(),postgres.getPassword())
                .locations("classpath:db/migration").schemas("public").defaultSchema("public").target(target)
                .baselineOnMigrate(false).validateOnMigrate(true).outOfOrder(false).cleanDisabled(true).load();}
        void upgrade() {flyway("7").migrate();flyway("7").validate();}
    }
    Database database(String target) throws Exception {
        String name="media_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());var statement=c.createStatement()) {
            statement.execute("CREATE DATABASE "+name);
        }
        String url=postgres.getJdbcUrl().replace("/"+postgres.getDatabaseName(),"/"+name);
        Database db=new Database(url,new JdbcTemplate(new DriverManagerDataSource(url,postgres.getUsername(),postgres.getPassword())));
        db.flyway(target).migrate();return db;
    }
    UUID media(Database db,String visibility) {
        UUID id=UUID.randomUUID();
        db.jdbc().update("INSERT INTO medias(id,storage_id,object_key,original_filename,extension,mime_type,size_bytes,media_type,visibility,status,created_by) "+
                "SELECT ?,id,?,'legacy.mp4','mp4','video/mp4',123,'VIDEO',?,'READY',? FROM storages WHERE is_default=true",
                id,"fixture/"+id+".mp4",visibility,UUID.randomUUID());
        return id;
    }
    Map<String,List<String>> snapshot(Database db) {
        Map<String,List<String>> result=new TreeMap<>();
        for(String table:List.of("medias","storages","upload_policies","upload_sessions","flyway_schema_history")) {
            String row=table.equals("medias")?"to_jsonb(t)-'upload_target_type'":"to_jsonb(t)";
            result.put(table,db.jdbc().queryForList("SELECT ("+row+")::text FROM "+table+" t ORDER BY ("+row+")::text",String.class));
        }
        return result;
    }
    @Test void upgradeV6AddsNullableTargetAndPreservesExistingMediaPoliciesSessionsAndHistory() throws Exception {
        Database db=database("6");UUID privateId=media(db,"PRIVATE");media(db,"PUBLIC");
        db.jdbc().update("INSERT INTO upload_sessions(id,media_id,uploader_id,uploader_type,status,created_by) VALUES(?,?,?,'USER','PENDING',?)",
                UUID.randomUUID(),privateId,UUID.randomUUID(),UUID.randomUUID());
        var before=snapshot(db);db.upgrade();var after=snapshot(db);
        before.forEach((table,rows)->{
            if(table.equals("flyway_schema_history") || table.equals("storages")) assertTrue(after.get(table).containsAll(rows));
            else assertEquals(rows,after.get(table),table);
        });
        assertEquals(2L,db.jdbc().queryForObject("SELECT count(*) FROM medias WHERE upload_target_type IS NULL",Long.class));
        assertEquals(1L,db.jdbc().queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='7' AND success",Long.class));
        assertEquals(1L,db.jdbc().queryForObject("SELECT count(*) FROM storages WHERE name='private-minio' AND bucket='media-private' AND is_default=false",Long.class));
    }
    @Test void emptyDatabaseRunsAllSevenMigrationsAndKeepsSimulationPoliciesPrivate() throws Exception {
        Database db=database("7");
        assertEquals(7L,db.jdbc().queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success",Long.class));
        assertEquals(List.of("PRIVATE","PRIVATE"),db.jdbc().queryForList("SELECT visibility FROM upload_policies WHERE target_type IN ('SIMULATION_VIDEO','SIMULATION_THUMBNAIL') ORDER BY target_type",String.class));
    }
    @Test void repeatedFlywayDoesNotModifyBusinessRowsOrHistory() throws Exception {
        Database db=database("7");media(db,"PRIVATE");var before=snapshot(db);db.upgrade();assertEquals(before,snapshot(db));
    }
    @Test void targetConstraintAcceptsKnownTypesAndRejectsUnreviewedTargets() throws Exception {
        Database db=database("7");UUID id=media(db,"PRIVATE");
        for(String target:List.of("USER","QUESTION","EXAM","TRAFFIC_SIGN","SIMULATION_VIDEO","SIMULATION_THUMBNAIL"))
            assertEquals(1,db.jdbc().update("UPDATE medias SET upload_target_type=? WHERE id=?",target,id));
        assertThrows(DataIntegrityViolationException.class,()->db.jdbc().update("UPDATE medias SET upload_target_type='UNREVIEWED' WHERE id=?",id));
        assertEquals(1,db.jdbc().update("UPDATE medias SET upload_target_type=NULL WHERE id=?",id));
    }
}
