package com.codit.be_boda.chat.type;

// 2번 질문: 어떤 치료를 받았는지 (중복)
public enum TreatmentType {
    DIAGNOSIS_ONLY,    // 진단만 받았어요
    SURGERY,           // 수술받았어요
    HOSPITALIZATION,   // 입원했어요
    OUTPATIENT,        // 통원, 외래 치료만 받았어요
    CAST,              // 깁스, 고정 치료받았어요
    DENTAL,            // 치아 치료받았어요
    DISABILITY         // 장해, 후유장해 진단받았어요
}