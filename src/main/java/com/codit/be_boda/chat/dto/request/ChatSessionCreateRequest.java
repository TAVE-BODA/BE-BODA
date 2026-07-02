package com.codit.be_boda.chat.dto.request;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ChatSessionCreateRequest {

    private Long analysisId;
    private Long termsDocumentId;
}