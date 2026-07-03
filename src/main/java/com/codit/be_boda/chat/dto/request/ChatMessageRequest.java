package com.codit.be_boda.chat.dto.request;

import com.codit.be_boda.chat.type.IncidentType;
import com.codit.be_boda.chat.type.QuestionType;
import com.codit.be_boda.chat.type.TreatmentStartDateType;
import com.codit.be_boda.chat.type.TreatmentType;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

// 질문 보낼 때 들어오는 핵심 DTO
@Getter
@NoArgsConstructor
public class ChatMessageRequest {

    private QuestionType questionType;
    private String message;

    private IncidentType incidentType;
    private List<TreatmentType> treatmentTypes;

    private HospitalizationInfoRequest hospitalizationInfo;
    private CastInfoRequest castInfo;
    private DentalInfoRequest dentalInfo;

    private TreatmentStartDateType treatmentStartDateType;
    private LocalDate treatmentStartDate;
    private Integer treatmentStartYear;
    private Integer treatmentStartMonth;
}