package com.codit.be_boda.chat.dto.request;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

// 이미 분석이 끝난 증권을 기존 채팅방에 추가로 연결할 때 사용
// (새 채팅방 생성은 ChatSessionCreateRequest 사용)
@Getter
@NoArgsConstructor
public class ChatSessionPolicyLinkRequest {
    private List<Long> analysisIds;
}