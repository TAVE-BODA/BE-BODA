package com.codit.be_boda.chat.dto.request;

import com.codit.be_boda.chat.type.DentalTreatmentType;
import com.codit.be_boda.chat.type.DentalTreatmentCountType;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

// 치아 치료 선택 시 들어오는 추가 정보
@Getter
@NoArgsConstructor
public class DentalInfoRequest {

    private List<DentalTreatmentType> dentalTreatmentTypes;

    private DentalTreatmentCountType dentalTreatmentCountType;
    private Integer dentalTreatmentCount;
}