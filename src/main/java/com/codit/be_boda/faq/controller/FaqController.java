package com.codit.be_boda.faq.controller;

import com.codit.be_boda.faq.dto.FaqResponse;
import com.codit.be_boda.faq.service.FaqService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class FaqController {

    private final FaqService faqService;

    @Operation(
            description = "FAQ 목록을 조회합니다."
    )
    @GetMapping("/faq")
    public ResponseEntity<List<FaqResponse>> getFaqList() {
        return ResponseEntity.ok(faqService.getFaqList());
    }
}