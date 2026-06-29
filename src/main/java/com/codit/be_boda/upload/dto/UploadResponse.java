package com.codit.be_boda.upload.dto;

public record UploadResponse(
        String status,
        Long id,
        String message
) {}
