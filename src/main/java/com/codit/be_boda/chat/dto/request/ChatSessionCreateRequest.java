package com.codit.be_boda.chat.dto.request;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
public class ChatSessionCreateRequest {

    // case2: 기존 증권 재사용 시 포함, case3: 비워서 전송
    private List<Long> analysisIds;

    // 약관 선택 (선택사항)
    private Long termsDocumentId;
}
