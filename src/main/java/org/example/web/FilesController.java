package org.example.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.FileSystemUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/files")
public class FilesController {
    // Base directory to store upload sessions and final files
    private static final Path BASE = Paths.get(System.getProperty("java.io.tmpdir"), "resumable-uploads");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // In-memory quick index to avoid reading metadata file every time (optional)
    private final Map<String, UploadMeta> metaCache = new ConcurrentHashMap<>();

    public FilesController() throws IOException {
        Files.createDirectories(BASE);
    }

    // 1) Initialize an upload session
    @PostMapping(path = "/init", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<InitResponse> init(@RequestBody InitRequest req) throws IOException {
        if (!StringUtils.hasText(req.getFileName()) || req.getTotalChunks() <= 0) {
            return ResponseEntity.badRequest().build();
        }
        String uploadId = UUID.randomUUID().toString();
        Path sessionDir = BASE.resolve(uploadId);
        Files.createDirectories(sessionDir);

        UploadMeta meta = new UploadMeta();
        meta.setUploadId(uploadId);
        meta.setFileName(req.getFileName());
        meta.setTotalChunks(req.getTotalChunks());
        meta.setReceivedChunks(new HashSet<>());

        writeMeta(sessionDir, meta);
        metaCache.put(uploadId, meta);

        InitResponse resp = new InitResponse(uploadId);
        return ResponseEntity.ok(resp);
    }

    // 2) Upload a chunk (multipart form file param named "file")
    @PostMapping(path = "/{uploadId}/chunk/{index}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadChunk(@PathVariable String uploadId,
                                         @PathVariable int index,
                                         @RequestParam("file") MultipartFile file) throws IOException {
        if (index < 0) return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid chunk index");
        UploadMeta meta = loadMeta(uploadId);
        if (meta == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Upload session not found");
        if (index >= meta.getTotalChunks()) return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Chunk index out of range");

        Path sessionDir = BASE.resolve(uploadId);
        Path chunkFile = sessionDir.resolve(chunkName(index));

        // Save chunk (overwrite if re-uploaded)
        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(chunkFile.toFile()))) {
            out.write(file.getBytes());
        }

        // update metadata
        synchronized (meta) {
            meta.getReceivedChunks().add(index);
            writeMeta(sessionDir, meta);
            metaCache.put(uploadId, meta);
        }

        return ResponseEntity.ok(new ChunkUploadResponse(index, true));
    }

    // 3) Query upload status
    @GetMapping(path = "/{uploadId}/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> status(@PathVariable String uploadId) throws IOException {
        UploadMeta meta = loadMeta(uploadId);
        if (meta == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Upload session not found");
        return ResponseEntity.ok(new StatusResponse(meta.getFileName(), meta.getTotalChunks(), meta.getReceivedChunks()));
    }

    // 4) Complete the upload: assemble chunks into final file
    @PostMapping(path = "/{uploadId}/complete", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> complete(@PathVariable String uploadId) throws IOException {
        UploadMeta meta = loadMeta(uploadId);
        if (meta == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Upload session not found");

        Path sessionDir = BASE.resolve(uploadId);
        // verify all chunks present
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < meta.getTotalChunks(); i++) {
            if (!meta.getReceivedChunks().contains(i)) missing.add(i);
        }
        if (!missing.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new CompleteResponse(false, "Missing chunks: " + missing));
        }

        Path finalDir = BASE.resolve("completed");
        Files.createDirectories(finalDir);
        Path finalFile = finalDir.resolve(sanitizeFileName(meta.getFileName()));

        // Assemble
        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(finalFile.toFile()))) {
            for (int i = 0; i < meta.getTotalChunks(); i++) {
                Path chunk = sessionDir.resolve(chunkName(i));
                byte[] bytes = Files.readAllBytes(chunk);
                out.write(bytes);
            }
            out.flush();
        }

        // Optionally, cleanup parts
        FileSystemUtils.deleteRecursively(sessionDir);
        metaCache.remove(uploadId);

        // Return a URL clients/templates can use to access the assembled file
        String encoded = URLEncoder.encode(finalFile.getFileName().toString(), StandardCharsets.UTF_8);
        String publicUrl = "/files/completed/" + encoded;

        return ResponseEntity.ok(new CompleteResponse(true, publicUrl));
    }

    // 5) Download assembled file so templates/clients can reference it by URL
    @GetMapping(path = "/completed/{fileName:.+}")
    public ResponseEntity<Resource> downloadCompleted(@PathVariable String fileName) throws IOException {
        Path finalDir = BASE.resolve("completed");
        Path file = finalDir.resolve(sanitizeFileName(fileName));
        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build();
        }
        Resource res = new org.springframework.core.io.UrlResource(file.toUri());
        String disp = "inline; filename=\"" + file.getFileName().toString() + "\"";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disp)
                .contentLength(Files.size(file))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(res);
    }

    // Helper methods
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

    // DTOs
    public static class InitRequest {
        private String fileName;
        private int totalChunks;

        public InitRequest() {
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
}
