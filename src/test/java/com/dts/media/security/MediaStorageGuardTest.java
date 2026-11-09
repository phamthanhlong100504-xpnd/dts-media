package com.dts.media.security;

import com.dts.media.application.service.MediaStorageGuard;
import com.dts.media.domain.entity.Storage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.minio.GetBucketPolicyArgs;
import io.minio.MinioClient;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MediaStorageGuardTest {
    final MinioClient minio=mock(MinioClient.class);
    final MediaStorageGuard guard=new MediaStorageGuard(minio,new ObjectMapper());
    final Storage storage=Storage.builder().provider("MINIO").bucket("fixture-private").build();
    @ParameterizedTest @ValueSource(strings={"{}","{\"Statement\":[]}","{\"Statement\":[{\"Effect\":\"Deny\",\"Principal\":\"*\"}]}"})
    void privatePolicyIsAccepted(String policy) throws Exception {
        when(minio.getBucketPolicy(any(GetBucketPolicyArgs.class))).thenReturn(policy);assertDoesNotThrow(()->guard.requirePrivate(storage));
    }
    @ParameterizedTest @ValueSource(strings={"{\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":\"*\"}]}","{\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":[\"*\"]}}]}","{\"Statement\":[{\"Effect\":\"Allow\",\"NotPrincipal\":\"fixture\"}]}","invalid-json","[]","{\"Statement\":{}}"})
    void publicOrUnknownPolicyFailsClosed(String policy) throws Exception {
        when(minio.getBucketPolicy(any(GetBucketPolicyArgs.class))).thenReturn(policy);
        assertEquals(503,assertThrows(ResponseStatusException.class,()->guard.requirePrivate(storage)).getStatusCode().value());
    }
    @Test void noSuchBucketPolicyIsTheExplicitPrivateBucketCase() throws Exception {
        var failure=mock(ErrorResponseException.class);var error=mock(ErrorResponse.class);
        when(error.code()).thenReturn("NoSuchBucketPolicy");when(failure.errorResponse()).thenReturn(error);
        when(minio.getBucketPolicy(any(GetBucketPolicyArgs.class))).thenThrow(failure);
        assertDoesNotThrow(()->guard.requirePrivate(storage));
    }
    @Test void sdkEmptyStringForNoPolicyIsAccepted() throws Exception {
        when(minio.getBucketPolicy(any(GetBucketPolicyArgs.class))).thenReturn("");
        assertDoesNotThrow(()->guard.requirePrivate(storage));
    }
    @ParameterizedTest @ValueSource(strings={" ","\n","null"})
    void whitespaceAndNullJsonAreNotTheSdkNoPolicySentinel(String policy) throws Exception {
        when(minio.getBucketPolicy(any(GetBucketPolicyArgs.class))).thenReturn(policy);
        assertEquals(503,assertThrows(ResponseStatusException.class,()->guard.requirePrivate(storage)).getStatusCode().value());
    }
    @Test void nullSdkResultIsNotAssumedPrivate() throws Exception {
        when(minio.getBucketPolicy(any(GetBucketPolicyArgs.class))).thenReturn(null);
        assertEquals(503,assertThrows(ResponseStatusException.class,()->guard.requirePrivate(storage)).getStatusCode().value());
    }
    @ParameterizedTest @ValueSource(strings={"AccessDenied","NoSuchBucket","InvalidAccessKeyId","SignatureDoesNotMatch"})
    void realS3ErrorCodesStillFailClosed(String code) throws Exception {
        var failure=mock(ErrorResponseException.class);var error=mock(ErrorResponse.class);
        when(error.code()).thenReturn(code);when(failure.errorResponse()).thenReturn(error);
        when(minio.getBucketPolicy(any(GetBucketPolicyArgs.class))).thenThrow(failure);
        assertEquals(503,assertThrows(ResponseStatusException.class,()->guard.requirePrivate(storage)).getStatusCode().value());
    }
    @Test void actualMinioSdkMapsNoSuchBucketPolicyToEmptyStringAndGuardAcceptsIt() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicInteger policyGets=new AtomicInteger();
        server.createContext("/fixture-private",exchange->{
            try {
                String query=exchange.getRequestURI().getRawQuery();
                boolean policyGet="GET".equals(exchange.getRequestMethod())
                        && ("policy=".equals(query) || "policy".equals(query));
                if (!policyGet) {
                    exchange.sendResponseHeaders(400,-1);
                    return;
                }
                policyGets.incrementAndGet();
                byte[] body=("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        +"<Error><Code>NoSuchBucketPolicy</Code><Message>The bucket policy does not exist</Message>"
                        +"<BucketName>fixture-private</BucketName><Resource>/fixture-private</Resource>"
                        +"<RequestId>fixture-request</RequestId><HostId>fixture-host</HostId></Error>")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/xml");
                exchange.sendResponseHeaders(404,body.length);
                exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });
        server.start();
        try {
            MinioClient actual=MinioClient.builder().endpoint("http://127.0.0.1:"+server.getAddress().getPort())
                    .region("us-east-1").credentials("fixture-access","fixture-secret").build();
            assertEquals("",actual.getBucketPolicy(GetBucketPolicyArgs.builder().bucket(storage.getBucket()).build()));
            assertDoesNotThrow(()->new MediaStorageGuard(actual,new ObjectMapper()).requirePrivate(storage));
            assertEquals(2,policyGets.get());
        } finally { server.stop(0); }
    }
    @Test void accessDeniedAndMissingBucketAreNotAssumedPrivate() throws Exception {
        when(minio.getBucketPolicy(any(GetBucketPolicyArgs.class))).thenThrow(new RuntimeException("fixture only"));
        assertThrows(ResponseStatusException.class,()->guard.requirePrivate(storage));
    }
}
