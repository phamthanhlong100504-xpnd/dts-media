package com.dts.media.application.service;

import com.dts.media.domain.entity.Storage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.GetBucketPolicyArgs;
import io.minio.MinioClient;
import io.minio.errors.ErrorResponseException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class MediaStorageGuard {
    private final MinioClient minio;
    private final ObjectMapper json;
    public MediaStorageGuard(@Qualifier("internalMinioClient") MinioClient minio, ObjectMapper json) {
        this.minio = minio; this.json = json;
    }
    public void requirePrivate(Storage storage) {
        if (!"MINIO".equals(storage.getProvider())) throw unavailable();
        try {
            String policy = minio.getBucketPolicy(GetBucketPolicyArgs.builder().bucket(storage.getBucket()).build());
            if (policy == null) throw unavailable();
            // MinIO Java 8.5.6 maps the explicit NoSuchBucketPolicy response to "".
            // Other storage failures still throw; do not accept null/whitespace/malformed JSON.
            if (policy.isEmpty()) return;
            JsonNode root = json.readTree(policy);
            if (!root.isObject()) throw unavailable();
            JsonNode statements = root.get("Statement");
            if (statements == null) {
                if (root.isEmpty()) return;
                throw unavailable();
            }
            if (!statements.isArray()) throw unavailable();
            for (JsonNode statement : statements) {
                if (!statement.isObject()) throw unavailable();
                String effect = statement.path("Effect").asText();
                if (!effect.equals("Deny") && !effect.equals("Allow")) throw unavailable();
                if (effect.equals("Allow") && (statement.has("NotPrincipal") || !statement.has("Principal") || wildcard(statement.get("Principal"))))
                    throw unavailable();
            }
        } catch (ErrorResponseException ex) {
            // MinIO returns this only when the addressed bucket has no bucket policy.
            if (ex.errorResponse() != null && "NoSuchBucketPolicy".equals(ex.errorResponse().code())) return;
            throw unavailable();
        } catch (Exception ex) { throw unavailable(); }
    }
    private boolean wildcard(JsonNode node) {
        if (node.isTextual()) return node.textValue().contains("*");
        if (node.isArray() || node.isObject()) {
            for (JsonNode value : node) if (wildcard(value)) return true;
            return false;
        }
        throw unavailable();
    }
    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Private MinIO bucket policy must be verified before issuing a URL");
    }
}
