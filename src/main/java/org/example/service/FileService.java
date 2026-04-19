package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class FileService {
    private static final Path BASE = Paths.get(System.getProperty("java.io.tmpdir"), "resumable-uploads");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // cleanup threshold (ms) - default 48 hours
    private static final long CLEANUP_THRESHOLD_MS = 48L * 60L * 60L * 1000L;

    private final Map<String, UploadMeta> metaCache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "file-service-cleanup");
        t.setDaemon(true);
        return t;
    });

    public FileService() throws IOException {
        Files.createDirectories(BASE);
        // schedule cleanup: first run after 1 hour, then every 24 hours
        scheduler.scheduleAtFixedRate(this::cleanupExpiredSessions, 1, 24, TimeUnit.HOURS);
    }

    public String init(String fileName, int totalChunks) throws IOException {
        if (!StringUtils.hasText(fileName) || totalChunks <= 0) throw new IllegalArgumentException("invalid args");
        String uploadId = UUID.randomUUID().toString();
        Path sessionDir = BASE.resolve(uploadId);
        Files.createDirectories(sessionDir);

        UploadMeta meta = new UploadMeta();
        meta.setUploadId(uploadId);
        meta.setFileName(fileName);
        meta.setTotalChunks(totalChunks);
        meta.setReceivedChunks(new HashSet<>());
        meta.setChunkMd5s(new HashMap<>());
        meta.setCreatedAt(Instant.now().toEpochMilli());

        writeMeta(sessionDir, meta);
        metaCache.put(uploadId, meta);
        return uploadId;
    }

    public ChunkUploadResponse saveChunk(String uploadId, int index, MultipartFile file) throws IOException {
        if (index < 0) throw new IllegalArgumentException("Invalid chunk index");
        UploadMeta meta = loadMeta(uploadId);
        if (meta == null) return new ChunkUploadResponse(index, false);
        if (index >= meta.getTotalChunks()) throw new IllegalArgumentException("Chunk index out of range");

        Path sessionDir = BASE.resolve(uploadId);
        Path chunkFile = sessionDir.resolve(chunkName(index));

        // Stream the incoming chunk to disk while computing MD5
        String md5Hex;
        try (InputStream in = new BufferedInputStream(file.getInputStream())) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            try (DigestInputStream dis = new DigestInputStream(in, md)) {
                // write to temp file first
                Path tmp = sessionDir.resolve(chunkName(index) + ".tmp");
                Files.copy(dis, tmp, StandardCopyOption.REPLACE_EXISTING);
                // move into place
                Files.move(tmp, chunkFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                md5Hex = bytesToHex(md.digest());
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("MD5 algorithm not available", e);
        }

        synchronized (meta) {
            // if existing md5 equals new md5, it's idempotent
            String existing = meta.getChunkMd5s() == null ? null : meta.getChunkMd5s().get(index);
            if (existing != null && existing.equals(md5Hex)) {
                // nothing to change
            } else {
                meta.getReceivedChunks().add(index);
                if (meta.getChunkMd5s() == null) meta.setChunkMd5s(new HashMap<>());
                meta.getChunkMd5s().put(index, md5Hex);
                writeMeta(sessionDir, meta);
                metaCache.put(uploadId, meta);
            }
        }

        return new ChunkUploadResponse(index, true);
    }

    public StatusResponse status(String uploadId) throws IOException {
        UploadMeta meta = loadMeta(uploadId);
        if (meta == null) return null;
        return new StatusResponse(meta.getFileName(), meta.getTotalChunks(), meta.getReceivedChunks());
    }

    public CompleteResponse complete(String uploadId) throws IOException {
        UploadMeta meta = loadMeta(uploadId);
        if (meta == null) return new CompleteResponse(false, "Upload session not found");

        Path sessionDir = BASE.resolve(uploadId);
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < meta.getTotalChunks(); i++) {
            if (!meta.getReceivedChunks().contains(i)) missing.add(i);
        }
        if (!missing.isEmpty()) {
            return new CompleteResponse(false, "Missing chunks: " + missing);
        }

        Path finalDir = BASE.resolve("completed");
        Files.createDirectories(finalDir);
        Path finalFile = finalDir.resolve(sanitizeFileName(meta.getFileName()));

        // Assemble using streaming and verify chunk md5s
        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(finalFile.toFile()))) {
            for (int i = 0; i < meta.getTotalChunks(); i++) {
                Path chunk = sessionDir.resolve(chunkName(i));
                if (!Files.exists(chunk)) {
                    return new CompleteResponse(false, "Missing chunk file: " + i);
                }
                // verify md5 if present
                String expected = meta.getChunkMd5s() == null ? null : meta.getChunkMd5s().get(i);
                if (expected != null) {
                    String actual = computeFileMd5(chunk);
                    if (!expected.equals(actual)) {
                        return new CompleteResponse(false, "Checksum mismatch for chunk " + i);
                    }
                }

                // stream copy
                try (InputStream in = Files.newInputStream(chunk)) {
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = in.read(buf)) != -1) {
                        out.write(buf, 0, r);
                    }
                }
            }
            out.flush();
        }

        FileSystemUtils.deleteRecursively(sessionDir);
        metaCache.remove(uploadId);

        String encoded = URLEncoder.encode(finalFile.getFileName().toString(), StandardCharsets.UTF_8);
        String publicUrl = "/files/completed/" + encoded;
        return new CompleteResponse(true, publicUrl);
    }

    public DownloadResult loadCompleted(String fileName) throws IOException {
        Path finalDir = BASE.resolve("completed");
        Path file = finalDir.resolve(sanitizeFileName(fileName));
        if (!Files.exists(file) || !Files.isRegularFile(file)) return null;
        Resource res = new org.springframework.core.io.UrlResource(file.toUri());
        long size = Files.size(file);
        return new DownloadResult(res, size, file.getFileName().toString());
    }

    // Cleanup expired sessions
    private void cleanupExpiredSessions() {
        try {
            long now = Instant.now().toEpochMilli();
            Files.list(BASE).filter(Files::isDirectory).forEach(dir -> {
                try {
                    String name = dir.getFileName().toString();
                    UploadMeta meta = metaCache.get(name);
                    long created = -1;
                    if (meta != null && meta.getCreatedAt() > 0) created = meta.getCreatedAt();
                    else {
                        try {
                            created = Files.getLastModifiedTime(dir).toMillis();
                        } catch (IOException e) {
                            created = now;
                        }
                    }
                    if (created > 0 && now - created > CLEANUP_THRESHOLD_MS) {
                        FileSystemUtils.deleteRecursively(dir);
                        metaCache.remove(name);
                    }
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    // internal helpers
    private static String chunkName(int index) {
        return String.format("chunk-%05d.part", index);
    }

    private static String sanitizeFileName(String name) {
        return name.replaceAll("[\\/:*?\"<>|]", "_");
    }

    private void writeMeta(Path sessionDir, UploadMeta meta) throws IOException {
        Path metaFile = sessionDir.resolve("meta.json");
        MAPPER.writeValue(metaFile.toFile(), meta);
    }

    private UploadMeta loadMeta(String uploadId) throws IOException {
        UploadMeta meta = metaCache.get(uploadId);
        if (meta != null) return meta;
        Path sessionDir = BASE.resolve(uploadId);
        Path metaFile = sessionDir.resolve("meta.json");
        if (!Files.exists(metaFile)) return null;
        meta = MAPPER.readValue(metaFile.toFile(), UploadMeta.class);
        metaCache.put(uploadId, meta);
        return meta;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String computeFileMd5(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) != -1) md.update(buf, 0, r);
            }
            return bytesToHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("MD5 algorithm not available", e);
        }
    }

    // DTOs reused by controller
    public static class InitRequest {
        private String fileName;
        private int totalChunks;

        public InitRequest() {
        }

        public InitRequest(String fileName, int totalChunks) {
            this.fileName = fileName;
            this.totalChunks = totalChunks;
        }

        public String getFileName() {
            return fileName;
        }

        public void setFileName(String fileName) {
            this.fileName = fileName;
        }

        public int getTotalChunks() {
            return totalChunks;
        }

        public void setTotalChunks(int totalChunks) {
            this.totalChunks = totalChunks;
        }
    }

    public static class InitResponse {
        private String uploadId;

        public InitResponse() {
        }

        public InitResponse(String uploadId) {
            this.uploadId = uploadId;
        }

        public String getUploadId() {
            return uploadId;
        }

        public void setUploadId(String uploadId) {
            this.uploadId = uploadId;
        }
    }

    public static class UploadMeta {
        private String uploadId;
        private String fileName;
        private int totalChunks;
        private Set<Integer> receivedChunks = new HashSet<>();
        private Map<Integer, String> chunkMd5s = new HashMap<>();
        private long createdAt;

        public UploadMeta() {
        }

        public String getUploadId() {
            return uploadId;
        }

        public void setUploadId(String uploadId) {
            this.uploadId = uploadId;
        }

        public String getFileName() {
            return fileName;
        }

        public void setFileName(String fileName) {
            this.fileName = fileName;
        }

        public int getTotalChunks() {
            return totalChunks;
        }

        public void setTotalChunks(int totalChunks) {
            this.totalChunks = totalChunks;
        }

        public Set<Integer> getReceivedChunks() {
            return receivedChunks;
        }

        public void setReceivedChunks(Set<Integer> receivedChunks) {
            this.receivedChunks = receivedChunks;
        }

        public Map<Integer, String> getChunkMd5s() {
            return chunkMd5s;
        }

        public void setChunkMd5s(Map<Integer, String> chunkMd5s) {
            this.chunkMd5s = chunkMd5s;
        }

        public long getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(long createdAt) {
            this.createdAt = createdAt;
        }
    }

    public static class ChunkUploadResponse {
        private int index;
        private boolean ok;

        public ChunkUploadResponse() {
        }

        public ChunkUploadResponse(int index, boolean ok) {
            this.index = index;
            this.ok = ok;
        }

        public int getIndex() {
            return index;
        }

        public void setIndex(int index) {
            this.index = index;
        }

        public boolean isOk() {
            return ok;
        }

        public void setOk(boolean ok) {
            this.ok = ok;
        }
    }

    public static class StatusResponse {
        private String fileName;
        private int totalChunks;
        private Set<Integer> received;

        public StatusResponse() {
        }

        public StatusResponse(String fileName, int totalChunks, Set<Integer> received) {
            this.fileName = fileName;
            this.totalChunks = totalChunks;
            this.received = received;
        }

        public String getFileName() {
            return fileName;
        }

        public void setFileName(String fileName) {
            this.fileName = fileName;
        }

        public int getTotalChunks() {
            return totalChunks;
        }

        public void setTotalChunks(int totalChunks) {
            this.totalChunks = totalChunks;
        }

        public Set<Integer> getReceived() {
            return received;
        }

        public void setReceived(Set<Integer> received) {
            this.received = received;
        }
    }

    public static class CompleteResponse {
        private boolean success;
        private String messageOrPath;

        public CompleteResponse() {
        }

        public CompleteResponse(boolean success, String messageOrPath) {
            this.success = success;
            this.messageOrPath = messageOrPath;
        }

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public String getMessageOrPath() {
            return messageOrPath;
        }

        public void setMessageOrPath(String messageOrPath) {
            this.messageOrPath = messageOrPath;
        }
    }

    public static class DownloadResult {
        private final Resource resource;
        private final long size;
        private final String fileName;

        public DownloadResult(Resource resource, long size, String fileName) {
            this.resource = resource;
            this.size = size;
            this.fileName = fileName;
        }

        public Resource getResource() {
            return resource;
        }

        public long getSize() {
            return size;
        }

        public String getFileName() {
            return fileName;
        }
    }
}
