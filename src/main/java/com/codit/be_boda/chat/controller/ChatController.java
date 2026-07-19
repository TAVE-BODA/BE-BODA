package com.codit.be_boda.chat.controller;

import com.codit.be_boda.chat.dto.request.ChatMessageRequest;
import com.codit.be_boda.chat.dto.request.ChatSessionCreateRequest;
import com.codit.be_boda.chat.dto.response.ChatMessagePairResponse;
import com.codit.be_boda.chat.dto.response.ChatMessageResponse;
import com.codit.be_boda.chat.dto.response.ChatSessionResponse;
import com.codit.be_boda.chat.dto.response.ChatMessageSourceResponse;
import com.codit.be_boda.chat.service.ChatService;
import com.codit.be_boda.global.exception.BusinessException;
import com.codit.be_boda.global.exception.ErrorCode;
import com.codit.be_boda.auth.dto.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
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
            description = """
                    채팅방을 생성합니다.
                    
                    case2 (기존 증권/약관 재사용): analysisIds + termsDocumentId 포함
                    case3 (완전 새 채팅방): 빈 바디 또는 {} 전송
                    """
    )
    @ApiResponse(responseCode = "200", description = "채팅방 생성 성공")
    @ApiResponse(responseCode = "401", description = "로그인 필요")
    @ApiResponse(responseCode = "404", description = "존재하지 않는 증권 ID")
    @ApiResponse(responseCode = "409", description = "증권 분석 미완료")
    @PostMapping("/sessions")
    public ResponseEntity<ChatSessionResponse> createSession(
            @RequestBody(required = false) ChatSessionCreateRequest request,
            HttpSession session
    ) {
        Long userId = getUserId(session);
        ChatSessionCreateRequest req = request != null ? request : new ChatSessionCreateRequest();
        ChatSessionResponse response = chatService.createSession(req, userId);
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "채팅방 삭제",
            description = """
                    채팅방과 대화 이력(메시지·답변 근거), 채팅방-증권 연결, 대시보드를 삭제합니다.
                    증권과 약관 자체는 삭제되지 않습니다 (다른 채팅방에서 재사용될 수 있음).
                    """
    )
    @ApiResponse(responseCode = "200", description = "채팅방 삭제 성공")
    @ApiResponse(responseCode = "401", description = "로그인 필요")
    @ApiResponse(responseCode = "403", description = "본인의 채팅방이 아님")
    @ApiResponse(responseCode = "404", description = "존재하지 않는 채팅방")
    @DeleteMapping("/sessions/{chatSessionId}")
    public ResponseEntity<Void> deleteSession(
            @PathVariable Long chatSessionId,
            HttpSession session
    ) {
        Long userId = getUserId(session);
        chatService.deleteSession(chatSessionId, userId);
        return ResponseEntity.noContent().build();
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

    @Operation(
            summary = "AI 답변 약관 근거 조회",
            description = "AI 답변 메시지 ID 기준으로 약관 근거를 조회합니다. 약관이 업로드되지 않았거나 근거가 없는 경우 상태값과 안내 문구를 반환합니다."
    )
    @ApiResponse(responseCode = "200", description = "약관 근거 조회 성공")
    @ApiResponse(responseCode = "400", description = "잘못된 요청 또는 USER 메시지 ID")
    @ApiResponse(responseCode = "404", description = "존재하지 않는 메시지 또는 채팅방")
    @GetMapping("/messages/{messageId}/sources")
    public ResponseEntity<ChatMessageSourceResponse> getMessageSources(
            @PathVariable Long messageId
    ) {
        ChatMessageSourceResponse response = chatService.getMessageSources(messageId);
        return ResponseEntity.ok(response);
    }

    // 로그인 필터 구현 전 임시 userId 추출
    // LoginCheckFilter 완성 후 제거 예정
    private Long getUserId(HttpSession session) {
        LoginUser loginUser = (LoginUser) session.getAttribute("loginUser");
        if (loginUser == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return loginUser.id();
    }
}
