package com.dts.media.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;

final class UploadChecks {
    private static final ObjectMapper JSON = new ObjectMapper();
    private UploadChecks() {}
    static void filename(String name) {
        if (name == null || name.isBlank() || name.length() > 500 || name.chars().anyMatch(c -> c < 32 || c == '/' || c == '\\'))
            throw new IllegalArgumentException("Invalid filename");
    }
    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!extension.matches("[a-z0-9]{0,20}")) throw new IllegalArgumentException("Invalid file extension");
        return extension;
    }
    static void simulationFormat(String target, String filename, String mime) {
        String extension = extension(filename);
        if (("SIMULATION_VIDEO".equals(target) && !("mp4".equals(extension) && "video/mp4".equals(mime)))
                || ("SIMULATION_THUMBNAIL".equals(target) && !("webp".equals(extension) && "image/webp".equals(mime))))
            throw new IllegalArgumentException("Simulation MIME/extension does not match target");
    }
    static boolean containsMime(String json, String mime) {
        if (json == null) throw new IllegalStateException("Upload policy MIME list missing");
        try {
            JsonNode values = JSON.readTree(json);
            if (!values.isArray()) throw new IllegalArgumentException();
            boolean found = false;
            for (JsonNode value : values) {
                if (!value.isTextual()) throw new IllegalArgumentException();
                found |= value.textValue().equals(mime);
            }
            return found;
        } catch (Exception ex) { throw new IllegalStateException("Invalid upload policy MIME list"); }
    }
    static boolean emptyMimeList(String json) {
        containsMime(json, "");
        try { return JSON.readTree(json).isEmpty(); }
        catch (Exception e) { throw new IllegalStateException("Invalid upload policy MIME list"); }
    }
}
