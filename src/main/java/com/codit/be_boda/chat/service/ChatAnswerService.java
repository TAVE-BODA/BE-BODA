package com.codit.be_boda.chat.service;

import com.codit.be_boda.chat.dto.request.ChatMessageRequest;
import com.codit.be_boda.chat.dto.response.AmountGuideResponse;
import com.codit.be_boda.chat.dto.response.ClaimGuideResponse;
import com.codit.be_boda.chat.dto.response.DocumentGuideResponse;
import com.codit.be_boda.chat.entity.ChatSession;
import com.codit.be_boda.chat.entity.ChatSessionPolicy;
import com.codit.be_boda.chat.repository.ChatSessionPolicyRepository;
import com.codit.be_boda.chat.service.answer.AmountAnswerResult;
import com.codit.be_boda.chat.service.answer.CastAnswerGenerator;
import com.codit.be_boda.chat.service.answer.ClaimAnswerResult;
import com.codit.be_boda.chat.service.answer.DentalAnswerGenerator;
import com.codit.be_boda.chat.service.answer.DiagnosisAnswerGenerator;
import com.codit.be_boda.chat.service.answer.DisabilityAnswerGenerator;
import com.codit.be_boda.chat.service.answer.DocumentAnswerGenerator;
import com.codit.be_boda.chat.service.answer.DocumentAnswerResult;
import com.codit.be_boda.chat.service.answer.HospitalizationAnswerGenerator;
import com.codit.be_boda.chat.service.answer.OutpatientAnswerGenerator;
import com.codit.be_boda.chat.service.answer.SurgeryAnswerGenerator;
import com.codit.be_boda.chat.service.answer.ClaimEvidenceFinder;
import com.codit.be_boda.chat.type.QuestionType;
import com.codit.be_boda.chat.type.TreatmentType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ChatAnswerService {

    private final ChatSessionPolicyRepository chatSessionPolicyRepository;
    private final CastAnswerGenerator castAnswerGenerator;
    private final SurgeryAnswerGenerator surgeryAnswerGenerator;
    private final HospitalizationAnswerGenerator hospitalizationAnswerGenerator;
    private final DentalAnswerGenerator dentalAnswerGenerator;
    private final DiagnosisAnswerGenerator diagnosisAnswerGenerator;
    private final OutpatientAnswerGenerator outpatientAnswerGenerator;
    private final DisabilityAnswerGenerator disabilityAnswerGenerator;
    private final DocumentAnswerGenerator documentAnswerGenerator;
    private final ClaimEvidenceFinder claimEvidenceFinder;

    public String generateAnswer(
            ChatSession chatSession,
            ChatMessageRequest request
    ) {
        return generateAnswerResult(
                chatSession,
                request
        ).messageContent();
    }

    public ChatAnswerResult generateAnswerResult(
            ChatSession chatSession,
            ChatMessageRequest request
    ) {
        // 채팅방에 연결된 증권 ID 목록 조회
        List<Long> analysisIds =
                chatSessionPolicyRepository
                        .findByChatSessionId(
                                chatSession.getChatSessionId()
                        )
                        .stream()
                        .map(ChatSessionPolicy::getAnalysisId)
                        .toList();

        // TODO: 다중 증권 결과 병합 필요
        // 현재는 첫 번째 증권을 기준으로 처리
        Long analysisId =
                analysisIds.isEmpty()
                        ? null
                        : analysisIds.get(0);

        // 칩1: 청구 가능 여부
        if (request.getQuestionType()
                == QuestionType.CHIP_CLAIM) {

            ClaimAnswerResult result =
                    generateClaimAnswerResult(
                            analysisId,
                            request
                    );

            List<AnswerSource> sources =
                    claimEvidenceFinder.findSources(
                            chatSession.getTermsDocumentId(),
                            request
                    );

            ClaimGuideResponse claimGuideWithSources =
                    bindClaimSources(
                            result.claimGuide(),
                            sources
                    );

            return ChatAnswerResult.claim(
                    result.messageContent(),
                    claimGuideWithSources,
                    !sources.isEmpty(),
                    sources
            );
        }

        // 칩2: 예상 보험금
        if (request.getQuestionType()
                == QuestionType.CHIP_AMOUNT) {

            AmountAnswerResult result =
                    generateAmountAnswerResult(
                            analysisId,
                            request
                    );

            AmountSourceBindingResult bindingResult =
                    bindAmountSources(
                            chatSession.getTermsDocumentId(),
                            request,
                            result.amountGuide()
                    );

            return ChatAnswerResult.amount(
                    result.messageContent(),
                    bindingResult.amountGuide(),
                    !bindingResult.sources().isEmpty(),
                    bindingResult.sources()
            );
        }

        // 칩3: 필요 서류
        if (request.getQuestionType()
                == QuestionType.CHIP_DOCUMENTS) {

            DocumentAnswerResult result =
                    documentAnswerGenerator
                            .generateStructuredAnswer(
                                    chatSession.getTermsDocumentId(),
                                    request
                            );

            List<AnswerSource> sources =
                    result.sources() == null
                            ? List.of()
                            : result.sources();

            DocumentGuideResponse documentGuideWithSources =
                    bindDocumentSources(
                            result.documentGuide(),
                            sources
                    );

            return ChatAnswerResult.documents(
                    result.messageContent(),
                    documentGuideWithSources,
                    !sources.isEmpty(),
                    sources
            );
        }

        // 칩4: 보장 카드
        if (request.getQuestionType()
                == QuestionType.CHIP_OVERVIEW) {

            return ChatAnswerResult.text(
                    "가입하신 증권의 보장 항목은 보장 카드에서 확인할 수 있어요."
            );
        }

        // 직접 입력
        if (request.getQuestionType()
                == QuestionType.FREE_TEXT) {

            return ChatAnswerResult.text(
                    "직접 입력 질문은 이후 약관 기반 답변 기능에서 처리될 예정입니다."
            );
        }

        return ChatAnswerResult.text(
                "요청하신 내용을 확인했어요."
        );
    }

    // 청구 가능 여부 카드 기본값
    // 구조화하지 않은 치료 유형의 임시 응답으로만 사용
    private ClaimGuideResponse buildClaimGuide(
            ChatMessageRequest request
    ) {
        return ClaimGuideResponse.builder()
                .claimStatus("NEEDS_REVIEW")
                .summary("입력하신 조건 기준으로 청구 가능성 확인이 필요해요.")
                .reasons(List.of(
                        "입력하신 사고 및 치료 정보를 기준으로 보장 항목 매칭이 필요합니다.",
                        "현재는 증권 분석 결과와 사용자 조건을 함께 확인하는 단계입니다."
                ))
                .cautions(List.of(
                        "실제 지급 여부는 보험사 심사 결과 및 약관 조건에 따라 달라질 수 있어요."
                ))
                .build();
    }

    // 예상 보험금 카드 기본값
    private AmountGuideResponse buildAmountGuide(
            ChatMessageRequest request
    ) {
        return AmountGuideResponse.builder()
                .calculationAvailable(true)
                .estimatedItems(List.of(
                        AmountGuideResponse.EstimatedItem.builder()
                                .coverageName("보장 후보")
                                .amountText("가입금액 또는 약관 기준 확인 필요")
                                .reason("입력하신 치료 유형과 매칭되는 보장 항목 확인이 필요합니다.")
                                .build()
                ))
                .cautions(List.of(
                        "정확한 금액은 가입 특약, 한도, 감액 조건에 따라 달라질 수 있어요."
                ))
                .build();
    }

    // CHIP_CLAIM 구조화 응답 생성
    private ClaimAnswerResult generateClaimAnswerResult(
            Long analysisId,
            ChatMessageRequest request
    ) {
        List<TreatmentType> treatmentTypes =
                request.getTreatmentTypes();

        if (treatmentTypes == null || treatmentTypes.isEmpty()) {
            String messageContent =
                    generateClaimAnswer(analysisId, request);

            ClaimGuideResponse claimGuide =
                    buildClaimGuide(request);

            return new ClaimAnswerResult(
                    messageContent,
                    claimGuide
            );
        }

        // 단일 치료
        if (treatmentTypes.size() == 1) {
            return generateSingleClaimAnswerResult(
                    analysisId,
                    request,
                    treatmentTypes.get(0)
            );
        }

        // 복수 치료
        List<TreatmentClaimResult> results = treatmentTypes.stream()
                .map(treatmentType -> new TreatmentClaimResult(
                        treatmentType,
                        generateSingleClaimAnswerResult(
                                analysisId,
                                request,
                                treatmentType
                        )
                ))
                .toList();

        return mergeClaimResults(results);
    }

    // 치료 유형 하나에 대한 구조화 응답 생성
    private ClaimAnswerResult generateSingleClaimAnswerResult(
            Long analysisId,
            ChatMessageRequest request,
            TreatmentType treatmentType
    ) {
        return switch (treatmentType) {
            case CAST ->
                    castAnswerGenerator.generateStructuredClaimAnswer(
                            analysisId,
                            request
                    );

            case SURGERY ->
                    surgeryAnswerGenerator.generateStructuredClaimAnswer(
                            analysisId,
                            request
                    );

            case HOSPITALIZATION ->
                    hospitalizationAnswerGenerator.generateStructuredClaimAnswer(
                            analysisId,
                            request
                    );

            case DENTAL ->
                    dentalAnswerGenerator.generateStructuredClaimAnswer(
                            analysisId,
                            request
                    );

            case DIAGNOSIS_ONLY ->
                    diagnosisAnswerGenerator.generateStructuredClaimAnswer(
                            analysisId,
                            request
                    );

            case OUTPATIENT ->
                    outpatientAnswerGenerator.generateStructuredClaimAnswer(
                            analysisId,
                            request
                    );

            case DISABILITY ->
                    disabilityAnswerGenerator.generateStructuredClaimAnswer(
                            analysisId,
                            request
                    );
        };
    }

    // 여러 치료 유형의 결과를 하나로 병합
    private ClaimAnswerResult mergeClaimResults(
            List<TreatmentClaimResult> results
    ) {
        StringBuilder messageContent =
                new StringBuilder();

        List<String> reasons =
                new ArrayList<>();

        List<String> cautions =
                new ArrayList<>();

        List<String> statuses =
                new ArrayList<>();

        for (TreatmentClaimResult treatmentResult : results) {
            String treatmentLabel =
                    getTreatmentTypeLabel(
                            treatmentResult.treatmentType()
                    );

            ClaimAnswerResult result =
                    treatmentResult.result();

            messageContent.append("[")
                    .append(treatmentLabel)
                    .append("]\n")
                    .append(result.messageContent())
                    .append("\n\n");

            if (result.claimGuide() == null) {
                continue;
            }

            statuses.add(
                    result.claimGuide().getClaimStatus()
            );

            if (result.claimGuide().getReasons() != null) {
                for (String reason :
                        result.claimGuide().getReasons()) {

                    reasons.add(
                            "["
                                    + treatmentLabel
                                    + "] "
                                    + reason
                    );
                }
            }

            if (result.claimGuide().getCautions() != null) {
                for (String caution :
                        result.claimGuide().getCautions()) {

                    if (!cautions.contains(caution)) {
                        cautions.add(caution);
                    }
                }
            }
        }

        String mergedStatus =
                determineMergedClaimStatus(statuses);

        ClaimGuideResponse claimGuide =
                ClaimGuideResponse.builder()
                        .claimStatus(mergedStatus)
                        .summary(
                                buildMergedClaimSummary(
                                        mergedStatus
                                )
                        )
                        .reasons(reasons)
                        .cautions(cautions)
                        .build();

        return new ClaimAnswerResult(
                messageContent.toString().trim(),
                claimGuide
        );
    }

    // 복수 결과의 전체 상태 결정
    private String determineMergedClaimStatus(
            List<String> statuses
    ) {
        if (statuses.isEmpty()) {
            return "NEEDS_REVIEW";
        }

        boolean allPossible = statuses.stream()
                .allMatch("POSSIBLE"::equals);

        if (allPossible) {
            return "POSSIBLE";
        }

        boolean allNotAvailable = statuses.stream()
                .allMatch("NOT_AVAILABLE"::equals);

        if (allNotAvailable) {
            return "NOT_AVAILABLE";
        }

        return "NEEDS_REVIEW";
    }

    private String buildMergedClaimSummary(
            String claimStatus
    ) {
        return switch (claimStatus) {
            case "POSSIBLE" ->
                    "선택하신 치료에서 청구 가능성이 있는 보장 항목이 확인됐어요.";

            case "NOT_AVAILABLE" ->
                    "현재 증권 분석 결과에서 선택한 치료와 매칭되는 보장 항목을 찾지 못했어요.";

            default ->
                    "치료별 청구 가능성이 달라 추가 확인이 필요해요.";
        };
    }

    private String getTreatmentTypeLabel(
            TreatmentType treatmentType
    ) {
        return switch (treatmentType) {
            case CAST -> "깁스·고정 치료";
            case SURGERY -> "수술";
            case HOSPITALIZATION -> "입원";
            case DENTAL -> "치아 치료";
            case DIAGNOSIS_ONLY -> "진단";
            case OUTPATIENT -> "통원·외래";
            case DISABILITY -> "장해·후유장해";
        };
    }

    // CHIP_AMOUNT 구조화 응답 생성
    private AmountAnswerResult generateAmountAnswerResult(
            Long analysisId,
            ChatMessageRequest request
    ) {
        List<TreatmentType> treatmentTypes =
                request.getTreatmentTypes();

        if (treatmentTypes == null
                || treatmentTypes.isEmpty()) {

            String messageContent =
                    generateAmountAnswer(
                            analysisId,
                            request
                    );

            AmountGuideResponse amountGuide =
                    buildAmountGuide(request);

            return new AmountAnswerResult(
                    messageContent,
                    amountGuide
            );
        }

        // 단일 치료
        if (treatmentTypes.size() == 1) {
            return generateSingleAmountAnswerResult(
                    analysisId,
                    request,
                    treatmentTypes.get(0)
            );
        }

        // 복수 치료
        List<TreatmentAmountResult> results =
                treatmentTypes.stream()
                        .map(treatmentType ->
                                new TreatmentAmountResult(
                                        treatmentType,
                                        generateSingleAmountAnswerResult(
                                                analysisId,
                                                request,
                                                treatmentType
                                        )
                                )
                        )
                        .toList();

        return mergeAmountResults(results);
    }

    private AmountAnswerResult generateSingleAmountAnswerResult(
            Long analysisId,
            ChatMessageRequest request,
            TreatmentType treatmentType
    ) {
        return switch (treatmentType) {
            case CAST ->
                    castAnswerGenerator.generateStructuredAmountAnswer(
                            analysisId,
                            request
                    );

            case SURGERY ->
                    surgeryAnswerGenerator.generateStructuredAmountAnswer(
                            analysisId,
                            request
                    );

            case HOSPITALIZATION ->
                    hospitalizationAnswerGenerator.generateStructuredAmountAnswer(
                            analysisId,
                            request
                    );

            case DENTAL ->
                    dentalAnswerGenerator.generateStructuredAmountAnswer(
                            analysisId,
                            request
                    );

            case DIAGNOSIS_ONLY ->
                    diagnosisAnswerGenerator.generateStructuredAmountAnswer(
                            analysisId,
                            request
                    );

            case OUTPATIENT ->
                    outpatientAnswerGenerator.generateStructuredAmountAnswer(
                            analysisId,
                            request
                    );

            case DISABILITY ->
                    disabilityAnswerGenerator.generateStructuredAmountAnswer(
                            analysisId,
                            request
                    );
        };
    }

    // CHIP_CLAIM 문자열 응답 생성
    private String generateClaimAnswer(
            Long analysisId,
            ChatMessageRequest request
    ) {
        if (hasTreatmentType(request, TreatmentType.CAST)) {
            return castAnswerGenerator.generateClaimAnswer(
                    analysisId,
                    request
            );
        }

        if (hasTreatmentType(request, TreatmentType.SURGERY)) {
            return surgeryAnswerGenerator.generateClaimAnswer(
                    analysisId,
                    request
            );
        }

        if (hasTreatmentType(request, TreatmentType.HOSPITALIZATION)) {
            return hospitalizationAnswerGenerator.generateClaimAnswer(
                    analysisId,
                    request
            );
        }

        if (hasTreatmentType(request, TreatmentType.DENTAL)) {
            return dentalAnswerGenerator.generateClaimAnswer(
                    analysisId,
                    request
            );
        }

        if (hasTreatmentType(request, TreatmentType.DIAGNOSIS_ONLY)) {
            return diagnosisAnswerGenerator.generateClaimAnswer(
                    analysisId,
                    request
            );
        }

        if (hasTreatmentType(request, TreatmentType.OUTPATIENT)) {
            return outpatientAnswerGenerator.generateClaimAnswer(
                    analysisId,
                    request
            );
        }

        if (hasTreatmentType(request, TreatmentType.DISABILITY)) {
            return disabilityAnswerGenerator.generateClaimAnswer(
                    analysisId,
                    request
            );
        }

        return "입력하신 치료 항목에 대해 청구 가능 여부를 확인하려면 추가 보장 항목 매칭이 필요합니다.";
    }

    // CHIP_AMOUNT 문자열 응답 생성
    private String generateAmountAnswer(
            Long analysisId,
            ChatMessageRequest request
    ) {
        if (hasTreatmentType(request, TreatmentType.CAST)) {
            return castAnswerGenerator.generateAmountAnswer(
                    analysisId,
                    request
            );
        }

        if (hasTreatmentType(request, TreatmentType.SURGERY)) {
            return surgeryAnswerGenerator.generateAmountAnswer(
                    analysisId,
                    request
            );
        }

        if (hasTreatmentType(request, TreatmentType.HOSPITALIZATION)) {
            return hospitalizationAnswerGenerator.generateAmountAnswer(
                    analysisId,
                    request
            );
        }

        if (hasTreatmentType(request, TreatmentType.DENTAL)) {
            return dentalAnswerGenerator.generateAmountAnswer(
                    analysisId,
                    request
            );
        }

        if (hasTreatmentType(request, TreatmentType.DIAGNOSIS_ONLY)) {
            return diagnosisAnswerGenerator.generateAmountAnswer(
                    analysisId,
                    request
            );
        }

        if (hasTreatmentType(request, TreatmentType.OUTPATIENT)) {
            return outpatientAnswerGenerator.generateAmountAnswer(
                    analysisId,
                    request
            );
        }

        if (hasTreatmentType(request, TreatmentType.DISABILITY)) {
            return disabilityAnswerGenerator.generateAmountAnswer(
                    analysisId,
                    request
            );
        }

        return "입력하신 치료 항목에 대한 예상 보험금 계산은 아직 준비 중입니다.";
    }

    private AmountAnswerResult mergeAmountResults(
            List<TreatmentAmountResult> results
    ) {
        StringBuilder messageContent =
                new StringBuilder();

        List<AmountGuideResponse.EstimatedItem> estimatedItems =
                new ArrayList<>();

        List<String> cautions =
                new ArrayList<>();

        boolean anyCalculationAvailable = false;
        boolean allCalculationAvailable = true;

        for (TreatmentAmountResult treatmentResult : results) {
            String treatmentLabel =
                    getTreatmentTypeLabel(
                            treatmentResult.treatmentType()
                    );

            AmountAnswerResult result =
                    treatmentResult.result();

            messageContent.append("[")
                    .append(treatmentLabel)
                    .append("]\n")
                    .append(result.messageContent())
                    .append("\n\n");

            AmountGuideResponse amountGuide =
                    result.amountGuide();

            if (amountGuide == null) {
                allCalculationAvailable = false;
                continue;
            }

            boolean calculationAvailable =
                    Boolean.TRUE.equals(
                            amountGuide.getCalculationAvailable()
                    );

            if (calculationAvailable) {
                anyCalculationAvailable = true;
            } else {
                allCalculationAvailable = false;
            }

            if (amountGuide.getEstimatedItems() != null) {
                for (AmountGuideResponse.EstimatedItem item
                        : amountGuide.getEstimatedItems()) {

                    String reason = item.getReason();

                    estimatedItems.add(
                            AmountGuideResponse.EstimatedItem.builder()
                                    .coverageName(
                                            item.getCoverageName()
                                    )
                                    .amountText(
                                            item.getAmountText()
                                    )
                                    .reason(
                                            reason == null
                                                    || reason.isBlank()
                                                    ? "[" + treatmentLabel + "] 금액 후보예요."
                                                    : "[" + treatmentLabel + "] " + reason
                                    )
                                    .build()
                    );
                }
            }

            if (amountGuide.getCautions() != null) {
                for (String caution
                        : amountGuide.getCautions()) {

                    if (!cautions.contains(caution)) {
                        cautions.add(caution);
                    }
                }
            }
        }

        // 일부 치료만 계산 가능한 경우
        if (anyCalculationAvailable
                && !allCalculationAvailable) {

            cautions.add(
                    0,
                    "일부 치료 항목은 필요한 정보가 부족해 전체 예상 보험금을 계산하지 못했어요."
            );
        }

        AmountGuideResponse amountGuide =
                AmountGuideResponse.builder()
                        .calculationAvailable(
                                allCalculationAvailable
                        )
                        .estimatedItems(estimatedItems)
                        .cautions(cautions)
                        .build();

        return new AmountAnswerResult(
                messageContent.toString().trim(),
                amountGuide
        );
    }

    private boolean hasTreatmentType(
            ChatMessageRequest request,
            TreatmentType treatmentType
    ) {
        return request.getTreatmentTypes() != null
                && request.getTreatmentTypes().contains(
                treatmentType
        );
    }

    // CHIP_AMOUNT 카드별로 약관 근거 chunkId 연결
    private AmountSourceBindingResult bindAmountSources(
            Long termsDocumentId,
            ChatMessageRequest request,
            AmountGuideResponse amountGuide
    ) {
        if (amountGuide == null) {
            return new AmountSourceBindingResult(
                    null,
                    List.of()
            );
        }

        if (amountGuide.getEstimatedItems() == null
                || amountGuide.getEstimatedItems().isEmpty()) {

            AmountGuideResponse amountGuideWithoutSources =
                    AmountGuideResponse.builder()
                            .calculationAvailable(
                                    amountGuide.getCalculationAvailable()
                            )
                            .estimatedItems(
                                    amountGuide.getEstimatedItems() == null
                                            ? List.of()
                                            : amountGuide.getEstimatedItems()
                            )
                            .cautions(
                                    amountGuide.getCautions()
                            )
                            .hasSources(false)
                            .sourceChunkIds(List.of())
                            .build();

            return new AmountSourceBindingResult(
                    amountGuideWithoutSources,
                    List.of()
            );
        }

        List<AmountGuideResponse.EstimatedItem> itemsWithSources =
                new ArrayList<>();

        Map<Long, AnswerSource> uniqueSources =
                new LinkedHashMap<>();

        for (AmountGuideResponse.EstimatedItem item
                : amountGuide.getEstimatedItems()) {

            List<AnswerSource> itemSources =
                    claimEvidenceFinder
                            .findAmountSourcesForItem(
                                    termsDocumentId,
                                    request,
                                    item
                            );

            List<Long> itemSourceChunkIds =
                    extractSourceChunkIds(
                            itemSources
                    );

            AmountGuideResponse.EstimatedItem itemWithSources =
                    AmountGuideResponse.EstimatedItem.builder()
                            .coverageName(
                                    item.getCoverageName()
                            )
                            .amountText(
                                    item.getAmountText()
                            )
                            .reason(
                                    item.getReason()
                            )
                            .hasSources(
                                    !itemSourceChunkIds.isEmpty()
                            )
                            .sourceChunkIds(
                                    itemSourceChunkIds
                            )
                            .build();

            itemsWithSources.add(
                    itemWithSources
            );

            for (AnswerSource source : itemSources) {
                if (source != null
                        && source.chunkId() != null) {

                    uniqueSources.putIfAbsent(
                            source.chunkId(),
                            source
                    );
                }
            }
        }

        List<Long> allSourceChunkIds =
                List.copyOf(
                        uniqueSources.keySet()
                );

        AmountGuideResponse amountGuideWithSources =
                AmountGuideResponse.builder()
                        .calculationAvailable(
                                amountGuide.getCalculationAvailable()
                        )
                        .estimatedItems(
                                itemsWithSources
                        )
                        .cautions(
                                amountGuide.getCautions()
                        )
                        .hasSources(
                                !allSourceChunkIds.isEmpty()
                        )
                        .sourceChunkIds(
                                allSourceChunkIds
                        )
                        .build();

        return new AmountSourceBindingResult(
                amountGuideWithSources,
                List.copyOf(
                        uniqueSources.values()
                )
        );
    }

    // CHIP_CLAIM 결과에 약관 근거 chunkId 연결
    private ClaimGuideResponse bindClaimSources(
            ClaimGuideResponse claimGuide,
            List<AnswerSource> sources
    ) {
        if (claimGuide == null) {
            return null;
        }

        List<Long> sourceChunkIds =
                extractSourceChunkIds(
                        sources
                );

        return ClaimGuideResponse.builder()
                .claimStatus(
                        claimGuide.getClaimStatus()
                )
                .summary(
                        claimGuide.getSummary()
                )
                .reasons(
                        claimGuide.getReasons()
                )
                .cautions(
                        claimGuide.getCautions()
                )
                .hasSources(
                        !sourceChunkIds.isEmpty()
                )
                .sourceChunkIds(
                        sourceChunkIds
                )
                .build();
    }

    // CHIP_DOCUMENTS 결과에 약관 근거 chunkId 연결
    private DocumentGuideResponse bindDocumentSources(
            DocumentGuideResponse documentGuide,
            List<AnswerSource> sources
    ) {
        if (documentGuide == null) {
            return null;
        }

        List<Long> sourceChunkIds =
                extractSourceChunkIds(
                        sources
                );

        return DocumentGuideResponse.builder()
                .documents(
                        documentGuide.getDocuments()
                )
                .hasSources(
                        !sourceChunkIds.isEmpty()
                )
                .sourceChunkIds(
                        sourceChunkIds
                )
                .build();
    }

    // AnswerSource 목록에서 중복 없는 chunkId 추출
    private List<Long> extractSourceChunkIds(
            List<AnswerSource> sources
    ) {
        if (sources == null
                || sources.isEmpty()) {
            return List.of();
        }

        return sources.stream()
                .filter(source ->
                        source != null
                                && source.chunkId() != null
                )
                .map(AnswerSource::chunkId)
                .distinct()
                .toList();
    }

    private record TreatmentClaimResult(
            TreatmentType treatmentType,
            ClaimAnswerResult result
    ) {
    }

    private record TreatmentAmountResult(
            TreatmentType treatmentType,
            AmountAnswerResult result
    ) {
    }

    private record AmountSourceBindingResult(
            AmountGuideResponse amountGuide,
            List<AnswerSource> sources
    ) {
    }
}
