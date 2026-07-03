package com.codit.be_boda.chat.type;

// 질문 종류
public enum QuestionType {
    CHIP_CLAIM,      // 청구 가능 여부
    CHIP_AMOUNT,     // 예상 보험금
    CHIP_DOCUMENTS,  // 필요 서류
    CHIP_OVERVIEW,   // 전체 보장 내역
    FREE_TEXT        // 직접 입력
}