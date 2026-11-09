package com.dts.media.application.service;

import com.dts.media.api.response.SimulationExamMediaModel.Asset;
import com.dts.media.security.SimulationExamTestTokens;
import com.dts.media.domain.entity.Media;
import com.dts.media.domain.entity.Storage;
import com.dts.media.domain.repository.StorageRepository;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import okhttp3.Headers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SimulationExamObjectProbeTest {
    final StorageRepository storages=mock(StorageRepository.class);final MediaStorageGuard guard=mock(MediaStorageGuard.class);
    final MinioClient minio=mock(MinioClient.class);final SimulationExamObjectProbe probe=new SimulationExamObjectProbe(storages,guard,minio);
    final StatObjectResponse stat=mock(StatObjectResponse.class);final UUID id=UUID.randomUUID(),storageId=UUID.randomUUID();
    Media media;Storage storage;Asset asset;TrackingInputStream stream;final List<TrackingInputStream> opened=new ArrayList<>();
    static class TrackingInputStream extends ByteArrayInputStream {boolean closed;TrackingInputStream(byte[]b){super(b);}@Override public void close()throws IOException{closed=true;super.close();}}
    byte[] header(){return new byte[]{0,0,0,24,'f','t','y','p','i','s','o','m',0,0,0,0};}
    void content(byte[]bytes)throws Exception {
        doAnswer(i->{
            GetObjectArgs args=i.getArgument(0);boolean range=args.length()!=null;
            byte[] data=range?bytes:SimulationExamTestTokens.bytes();stream=new TrackingInputStream(data);opened.add(stream);
            var headers=new Headers.Builder().add("Content-Range","bytes 0-15/1024").build();
            return new GetObjectResponse(headers,"media",null,"fixture",stream);
        }).when(minio).getObject(any(GetObjectArgs.class));
    }
    @BeforeEach void fixture()throws Exception {
        media=Media.builder().id(id).storageId(storageId).objectKey("uploads/2026/11/02/"+id+".mp4").sizeBytes(1024L).build();
        storage=Storage.builder().id(storageId).bucket("media").provider("MINIO").status("ACTIVE").build();asset=new Asset(id,"fixture.mp4",SimulationExamTestTokens.sha(),27000,26800);
        when(storages.findById(storageId)).thenReturn(Optional.of(storage));when(minio.statObject(any(StatObjectArgs.class))).thenReturn(stat);
        when(stat.size()).thenReturn(1024L);when(stat.contentType()).thenReturn("video/mp4");when(stat.etag()).thenReturn("0123456789abcdef0123456789abcdef");content(header());
    }
    @Test void readsOnlyFirst16BytesWithRangeAndIfMatchAfterPrivatePolicyCheck()throws Exception {
        var result=probe.inspect(media,asset);assertTrue(result.ready());assertEquals(stat.etag(),result.etag());assertTrue(opened.stream().allMatch(i->i.closed));
        var order=inOrder(guard,minio);order.verify(guard).requirePrivate(storage);order.verify(minio).statObject(any());
        var args=ArgumentCaptor.forClass(GetObjectArgs.class);order.verify(minio,times(2)).getObject(args.capture());
        GetObjectArgs range=args.getAllValues().getFirst();
        assertEquals(0L,range.offset());assertEquals(16L,range.length());assertEquals(stat.etag(),range.matchETag());
        assertEquals("bytes=0-15",range.getHeaders().get("Range").iterator().next());
    }
    @Test void legacyZeroMetadataSizeUsesPositiveActualObjectSize(){media.setSizeBytes(0L);assertTrue(probe.inspect(media,asset).ready());assertEquals(0L,media.getSizeBytes());}
    @ParameterizedTest @ValueSource(strings={"size","missing-size","negative-size","short","mime"})
    void invalidSizeOrMimeCannotBeReady(String kind)throws Exception {
        switch(kind){case "size"->when(stat.size()).thenReturn(100L);case "missing-size"->media.setSizeBytes(null);case "negative-size"->media.setSizeBytes(-1L);
            case "short"->when(stat.size()).thenReturn(8L);case "mime"->when(stat.contentType()).thenReturn("text/html");}
        var result=probe.inspect(media,asset);assertFalse(result.ready());assertNull(result.etag());assertEquals("OBJECT_MISMATCH",result.reason());verify(minio,never()).getObject(any());
    }
    @ParameterizedTest @ValueSource(strings={"wrong-box","truncated"})
    void invalidMp4HeaderIsRejectedAndStreamClosed(String kind)throws Exception {
        byte[]b=header();if(kind.equals("wrong-box"))b[4]='x';else b=Arrays.copyOf(b,8);content(b);
        var result=probe.inspect(media,asset);assertFalse(result.ready());assertEquals("VIDEO_FORMAT_INVALID",result.reason());assertTrue(stream.closed);
    }
    @ParameterizedTest @ValueSource(strings={"","etag/invalid"})
    void unusableObjectIdentityFailsClosed(String etag){when(stat.etag()).thenReturn(etag);assertEquals(503,assertThrows(ResponseStatusException.class,()->probe.inspect(media,asset)).getStatusCode().value());}
    @ParameterizedTest @ValueSource(strings={"missing","inactive","deleted"})
    void inactiveOrMissingStorageCannotBeUsed(String kind) {
        if(kind.equals("missing"))when(storages.findById(storageId)).thenReturn(Optional.empty());
        if(kind.equals("inactive"))storage.setStatus("INACTIVE");if(kind.equals("deleted"))storage.setDeletedAt(OffsetDateTime.now());
        assertEquals(503,assertThrows(ResponseStatusException.class,()->probe.inspect(media,asset)).getStatusCode().value());verifyNoInteractions(guard,minio);
    }
    @Test void privatePolicyFailurePropagatesBeforeObjectRead(){doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Private policy unavailable")).when(guard).requirePrivate(storage);assertThrows(ResponseStatusException.class,()->probe.inspect(media,asset));verifyNoInteractions(minio);}
    @ParameterizedTest @ValueSource(strings={"NoSuchKey","PreconditionFailed"})
    void missingOrConcurrentObjectChangeDoesNotBecomeReady(String code)throws Exception {
        var error=mock(ErrorResponseException.class);var body=mock(ErrorResponse.class);when(body.code()).thenReturn(code);when(error.errorResponse()).thenReturn(body);
        doThrow(error).when(minio).getObject(any(GetObjectArgs.class));var result=probe.inspect(media,asset);
        assertFalse(result.ready());assertEquals(code.equals("NoSuchKey")?"OBJECT_MISSING":"OBJECT_CHANGED",result.reason());
    }
    @Test void transportFailureIsUnavailableRatherThanFakeUnreadyVideo()throws Exception {
        when(minio.statObject(any(StatObjectArgs.class))).thenThrow(new IOException("fixture network failure"));
        assertEquals(503,assertThrows(ResponseStatusException.class,()->probe.inspect(media,asset)).getStatusCode().value());
    }
    @Test void mismatchedPublishedShaCannotBecomeReady() {
        Asset wrong=new Asset(id,"fixture.mp4","f".repeat(64),27000,26800);
        var result=probe.inspect(media,wrong);assertFalse(result.ready());assertEquals("OBJECT_SHA_MISMATCH",result.reason());assertTrue(opened.stream().allMatch(i->i.closed));
    }
    @Test void verifiedEtagAndShaCacheAvoidsDownloadingWholeObjectOnUrlRenewal()throws Exception {
        assertTrue(probe.inspect(media,asset).ready());assertTrue(probe.inspect(media,asset).ready());
        var args=ArgumentCaptor.forClass(GetObjectArgs.class);verify(minio,times(3)).getObject(args.capture());
        assertEquals(1,args.getAllValues().stream().filter(a->a.length()==null).count());
        when(stat.etag()).thenReturn("newetag");assertTrue(probe.inspect(media,asset).ready());
        verify(minio,times(5)).getObject(any());
    }
    @Test void missingActualRangeHeaderIsNotAccepted()throws Exception {
        doReturn(new GetObjectResponse(new Headers.Builder().build(),"media",null,"fixture",new ByteArrayInputStream(header()))).when(minio).getObject(any(GetObjectArgs.class));
        assertEquals("RANGE_UNAVAILABLE",probe.inspect(media,asset).reason());
    }
}
