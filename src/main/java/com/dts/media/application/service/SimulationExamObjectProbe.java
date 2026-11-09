package com.dts.media.application.service;

import com.dts.media.api.response.SimulationExamMediaModel.Asset;
import com.dts.media.domain.entity.Media;
import com.dts.media.domain.repository.StorageRepository;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;

/** Checks live object SHA against the published attestation. Duration/scoring remain Content Builder responsibilities. */
@Component
public class SimulationExamObjectProbe {
    private final StorageRepository storages;private final MediaStorageGuard guard;private final MinioClient minio;
    private record Proof(java.util.UUID storage,String object,String etag,long size,String sha256) {}
    private final ConcurrentHashMap<Proof,Instant> verified=new ConcurrentHashMap<>();
    public SimulationExamObjectProbe(StorageRepository storages,MediaStorageGuard guard,@Qualifier("internalMinioClient")MinioClient minio) {
        this.storages=storages;this.guard=guard;this.minio=minio;
    }
    public record Observation(boolean ready,String etag,String reason) {}
    public Observation inspect(Media media,Asset asset) {
        var storage=storages.findById(media.getStorageId()).orElseThrow(this::unavailable);
        if(storage.getDeletedAt()!=null||!"ACTIVE".equals(storage.getStatus()))throw unavailable();
        guard.requirePrivate(storage);
        try {
            var stat=minio.statObject(StatObjectArgs.builder().bucket(storage.getBucket()).object(media.getObjectKey()).build());
            if(stat.size()<16||media.getSizeBytes()==null||media.getSizeBytes()<0
                    ||(media.getSizeBytes()>0&&media.getSizeBytes()!=stat.size())||!"video/mp4".equals(stat.contentType()))
                return new Observation(false,null,"OBJECT_MISMATCH");
            String etag=stat.etag();if(etag==null||!etag.matches("[A-Za-z0-9_-]{1,128}"))throw unavailable();
            try(var in=minio.getObject(GetObjectArgs.builder().bucket(storage.getBucket()).object(media.getObjectKey()).offset(0L).length(16L).matchETag(etag).build())) {
                byte[] bytes=in.readNBytes(16);
                if(!("bytes 0-15/"+stat.size()).equals(in.headers().get("Content-Range")))return new Observation(false,null,"RANGE_UNAVAILABLE");
                if(bytes.length!=16||bytes[4]!='f'||bytes[5]!='t'||bytes[6]!='y'||bytes[7]!='p')return new Observation(false,null,"VIDEO_FORMAT_INVALID");
            }
            Proof proof=new Proof(storage.getId(),media.getObjectKey(),etag,stat.size(),asset.sha256());Instant expiry=verified.get(proof);
            if(expiry==null||!expiry.isAfter(Instant.now())) {
                MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0;
                try(var in=minio.getObject(GetObjectArgs.builder().bucket(storage.getBucket()).object(media.getObjectKey()).matchETag(etag).build())) {
                    byte[] buffer=new byte[65536];int count;
                    while((count=in.read(buffer))!=-1){total+=count;if(total>stat.size())return new Observation(false,null,"OBJECT_MISMATCH");digest.update(buffer,0,count);}
                }
                if(total!=stat.size()||!HexFormat.of().formatHex(digest.digest()).equals(asset.sha256()))return new Observation(false,null,"OBJECT_SHA_MISMATCH");
                synchronized(verified){if(verified.size()>=256)verified.clear();verified.put(proof,Instant.now().plusSeconds(600));}
            }
            return new Observation(true,etag,"READY");
        }catch(ErrorResponseException e) {
            if(e.errorResponse()!=null&&java.util.Set.of("NoSuchKey","NoSuchObject").contains(e.errorResponse().code()))return new Observation(false,null,"OBJECT_MISSING");
            if(e.errorResponse()!=null&&"PreconditionFailed".equals(e.errorResponse().code()))return new Observation(false,null,"OBJECT_CHANGED");
            throw unavailable();
        }catch(ResponseStatusException e){throw e;}
        catch(Exception e){throw unavailable();}
    }
    private ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Simulation exam storage unavailable");}
}
