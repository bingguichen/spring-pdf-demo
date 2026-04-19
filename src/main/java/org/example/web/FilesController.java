package org.example.web;

import org.example.service.FileService;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/files")
public class FilesController {

    private final FileService fileService;

    public FilesController(FileService fileService) {
        this.fileService = fileService;
    }

    @PostMapping(path = "/init", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FileService.InitResponse> init(@RequestBody FileService.InitRequest req) throws IOException {
        // delegate to service
        String uploadId = fileService.init(req.getFileName(), req.getTotalChunks());
        FileService.InitResponse resp = new FileService.InitResponse(uploadId);
        return ResponseEntity.ok(resp);
    }

    @PostMapping(path = "/{uploadId}/chunk/{index}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadChunk(@PathVariable String uploadId,
                                         @PathVariable int index,
                                         @RequestParam("file") MultipartFile file) throws IOException {
        FileService.ChunkUploadResponse resp = fileService.saveChunk(uploadId, index, file);
        if (!resp.isOk()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Upload session not found");
        return ResponseEntity.ok(resp);
    }

    @GetMapping(path = "/{uploadId}/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> status(@PathVariable String uploadId) throws IOException {
        FileService.StatusResponse resp = fileService.status(uploadId);
        if (resp == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Upload session not found");
        return ResponseEntity.ok(resp);
    }

    @PostMapping(path = "/{uploadId}/complete", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> complete(@PathVariable String uploadId) throws IOException {
        FileService.CompleteResponse resp = fileService.complete(uploadId);
        if (!resp.isSuccess()) return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(resp);
        return ResponseEntity.ok(resp);
    }

    @GetMapping(path = "/completed/{fileName:.+}")
    public ResponseEntity<Resource> downloadCompleted(@PathVariable String fileName) throws IOException {
        FileService.DownloadResult dr = fileService.loadCompleted(fileName);
        if (dr == null) return ResponseEntity.notFound().build();
        String disp = "inline; filename=\"" + dr.getFileName() + "\"";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disp)
                .contentLength(dr.getSize())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(dr.getResource());
    }
}
