package com.codit.be_boda.chat.controller;

import com.codit.be_boda.chat.dto.request.ChatMessageRequest;
import com.codit.be_boda.chat.dto.request.ChatSessionCreateRequest;
import com.codit.be_boda.chat.dto.response.ChatMessagePairResponse;
import com.codit.be_boda.chat.dto.response.ChatMessageResponse;
import com.codit.be_boda.chat.dto.response.ChatSessionResponse;
import com.codit.be_boda.chat.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping("/sessions")
    public ResponseEntity<ChatSessionResponse> createSession(
            @RequestBody ChatSessionCreateRequest request
    ) {
        ChatSessionResponse response = chatService.createSession(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/sessions/{chatSessionId}/messages")
    public ResponseEntity<ChatMessagePairResponse> sendMessage(
            @PathVariable Long chatSessionId,
            @RequestBody ChatMessageRequest request
    ) {
        ChatMessagePairResponse response = chatService.sendMessage(chatSessionId, request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/sessions/{chatSessionId}/messages")
    public ResponseEntity<List<ChatMessageResponse>> getMessages(
            @PathVariable Long chatSessionId
    ) {
        List<ChatMessageResponse> response = chatService.getMessages(chatSessionId);
        return ResponseEntity.ok(response);
    }
}