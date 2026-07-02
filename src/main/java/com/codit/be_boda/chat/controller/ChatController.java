package com.codit.be_boda.chat.controller;

import com.codit.be_boda.chat.dto.request.ChatMessageRequest;
import com.codit.be_boda.chat.dto.request.ChatSessionCreateRequest;
import com.codit.be_boda.chat.dto.response.ChatMessagePairResponse;
import com.codit.be_boda.chat.dto.response.ChatMessageResponse;
import com.codit.be_boda.chat.dto.response.ChatSessionResponse;
import com.codit.be_boda.chat.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Chat", description = "보험 상담 챗봇 API")
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @Operation(
            summary = "채팅방 생성",
            description = "증권 분석 ID 기준으로 채팅방을 생성합니다. analysis_status가 DONE인 경우에만 생성 가능합니다."
    )
    @ApiResponse(responseCode = "200", description = "채팅방 생성 성공")
    @ApiResponse(responseCode = "404", description = "존재하지 않는 증권 분석 ID")
    @ApiResponse(responseCode = "409", description = "증권 분석 미완료")
    @PostMapping("/sessions")
    public ResponseEntity<ChatSessionResponse> createSession(
            @RequestBody ChatSessionCreateRequest request
    ) {
        ChatSessionResponse response = chatService.createSession(request);
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "챗봇 질문/조건 전송",
            description = "사용자 질문과 보험 상담 조건을 저장하고, USER 메시지와 AI 응답 메시지를 한 쌍으로 반환합니다."
    )
    @ApiResponse(responseCode = "200", description = "메시지 전송 성공")
    @ApiResponse(responseCode = "400", description = "잘못된 요청")
    @ApiResponse(responseCode = "404", description = "존재하지 않는 채팅방")
    @PostMapping("/sessions/{chatSessionId}/messages")
    public ResponseEntity<ChatMessagePairResponse> sendMessage(
            @PathVariable Long chatSessionId,
            @RequestBody ChatMessageRequest request
    ) {
        ChatMessagePairResponse response = chatService.sendMessage(chatSessionId, request);
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "대화 히스토리 조회",
            description = "특정 채팅방의 USER/AI 메시지를 생성 시간순으로 조회합니다."
    )
    @ApiResponse(responseCode = "200", description = "대화 히스토리 조회 성공")
    @ApiResponse(responseCode = "404", description = "존재하지 않는 채팅방")
    @GetMapping("/sessions/{chatSessionId}/messages")
    public ResponseEntity<List<ChatMessageResponse>> getMessages(
            @PathVariable Long chatSessionId
    ) {
        List<ChatMessageResponse> response = chatService.getMessages(chatSessionId);
        return ResponseEntity.ok(response);
    }
}