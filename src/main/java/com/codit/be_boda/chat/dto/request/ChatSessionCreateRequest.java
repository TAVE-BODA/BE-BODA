package com.codit.be_boda.chat.dto.request;

import lombok.Getter;
import lombok.NoArgsConstructor;

// 채팅방 생성 시 프론트가 보내는 값
@Getter
@NoArgsConstructor
public class ChatSessionCreateRequest {

    private Long analysisId;
    private Long termsDocumentId;
}