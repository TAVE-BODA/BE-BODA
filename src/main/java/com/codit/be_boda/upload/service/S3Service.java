package com.codit.be_boda.upload.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.util.UUID;


@Slf4j
@Service
@RequiredArgsConstructor
public class S3Service {

    private final S3Client s3Client;

    @Value("${cloud.aws.s3.bucket}")
    private String bucket;

    public String uploadFile(MultipartFile file, String folder) {
        String s3Key = folder + "/" + UUID.randomUUID() + ".pdf";
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(s3Key)
                    .contentType(file.getContentType())
                    .contentLength(file.getSize())
                    .build();

            s3Client.putObject(request, RequestBody.fromInputStream(
                    file.getInputStream(), file.getSize()));

            log.info("[S3] 업로드 완료 | key={}", s3Key);
            return s3Key;

        } catch (IOException e) {
            log.error("[S3] 업로드 실패 | {}", e.getMessage());
            throw new RuntimeException("S3 업로드 실패", e);
        }
    }

    public void deleteFile(String s3Key) {
        if (s3Key == null) return;
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(s3Key)
                    .build());
            log.info("[S3] 원본 파기 완료 | key={}", s3Key);
        } catch (Exception e) {
            log.error("[S3] 파일 삭제 실패 | key={} | {}", s3Key, e.getMessage());
        }
    }
}
