package com.codit.be_boda.chat.golden;

import com.codit.be_boda.chat.dto.request.ChatMessageRequest;
import com.codit.be_boda.chat.dto.response.ChatMessagePairResponse;
import com.codit.be_boda.chat.entity.ChatSession;
import com.codit.be_boda.chat.entity.ChatSessionPolicy;
import com.codit.be_boda.chat.repository.ChatSessionPolicyRepository;
import com.codit.be_boda.chat.repository.ChatSessionRepository;
import com.codit.be_boda.chat.service.ChatService;
import com.codit.be_boda.chat.type.QuestionType;
import com.codit.be_boda.global.exception.BusinessException;
import com.codit.be_boda.global.exception.ErrorCode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

/**
 * 채팅 칩 답변 회귀 테스트 (골든 마스터).
 *
 * <p>리팩토링 전 코드의 응답을 스냅샷으로 저장해 두고, 이후 결과가 한 글자라도 달라지면 실패한다.
 * "지금 동작이 옳다"를 검증하는 테스트가 아니라 "리팩토링이 동작을 바꾸지 않았다"를 보장하는 안전망이다.
 *
 * <ul>
 *   <li>입력 데이터: fixtures/seed-data.sql (시연용 증권 1건 + 약관 1건, 개인정보 제거)</li>
 *   <li>시나리오: golden/scenarios/*.json × 칩 3종(CLAIM, AMOUNT, DOCUMENTS)</li>
 *   <li>스냅샷: snapshots/chat-answer/{시나리오}__{칩}.json</li>
 * </ul>
 *
 * <p>스냅샷 기록/갱신: 환경변수 UPDATE_SNAPSHOTS=true 로 실행.
 * 스냅샷이 없는데 갱신 모드가 아니면 실패한다. (CI에서 조용히 새로 기록되는 것을 막기 위함)
 */
@SpringBootTest
@ActiveProfiles("golden")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChatAnswerGoldenMasterTest {

    private static final Long FIXTURE_USER_ID = 2L;
    private static final Long FIXTURE_ANALYSIS_ID = 1L;
    private static final Long FIXTURE_TERMS_DOCUMENT_ID = 1L;

    private static final Path SCENARIO_DIR = Path.of("src/test/resources/golden/scenarios");
    private static final Path SNAPSHOT_DIR = Path.of("src/test/resources/snapshots/chat-answer");
    private static final boolean UPDATE_SNAPSHOTS = "true".equalsIgnoreCase(System.getenv("UPDATE_SNAPSHOTS"));

    private static final List<QuestionType> CHIPS = List.of(
            QuestionType.CHIP_CLAIM,
            QuestionType.CHIP_AMOUNT,
            QuestionType.CHIP_DOCUMENTS
    );

    // 실행할 때마다 달라지는 값은 비교에서 제외한다. (chunkId는 픽스처로 고정되므로 비교 대상)
    private static final Set<String> VOLATILE_FIELDS = Set.of(
            "messageId", "chatSessionId", "createdAt", "sourceId"
    );

    // PER_CLASS 생명주기에서는 Spring 컨텍스트가 @Testcontainers 확장보다 먼저 뜨기 때문에
    // 컨테이너를 클래스 로딩 시점에 직접 시작한다. (종료는 Testcontainers의 Ryuk가 정리)
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")
    );

    static {
        postgres.start();
    }

    // 칩 답변은 LLM을 쓰지 않는다. 실수로 호출되더라도 외부 API로 나가지 않도록 차단한다.
    @MockBean
    private OpenAiChatModel chatModel;

    @Autowired
    private ChatService chatService;
    @Autowired
    private ChatSessionRepository chatSessionRepository;
    @Autowired
    private ChatSessionPolicyRepository chatSessionPolicyRepository;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private DataSource dataSource;

    private ObjectMapper strictMapper;
    private ObjectWriter snapshotWriter;

    @BeforeAll
    void setUp() {
        new ResourceDatabasePopulator(
                false, false, "UTF-8",
                new ClassPathResource("fixtures/seed-data.sql")
        ).execute(dataSource);

        // 시나리오 JSON의 필드명 오타가 조용히 무시되지 않도록 알 수 없는 필드는 실패시킨다.
        strictMapper = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        snapshotWriter = objectMapper.writerWithDefaultPrettyPrinter();
    }

    Stream<Arguments> cases() throws IOException {
        List<String> scenarios;
        try (Stream<Path> files = Files.list(SCENARIO_DIR)) {
            scenarios = files
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".json"))
                    .map(name -> name.substring(0, name.length() - ".json".length()))
                    .sorted()
                    .toList();
        }

        return scenarios.stream()
                .flatMap(scenario -> CHIPS.stream()
                        .map(chip -> Arguments.of(scenario, chip)));
    }

    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("cases")
    void chipAnswerMatchesSnapshot(String scenario, QuestionType chip) throws IOException {
        ObjectNode input = (ObjectNode) objectMapper.readTree(
                SCENARIO_DIR.resolve(scenario + ".json").toFile()
        );
        input.put("questionType", chip.name());
        ChatMessageRequest request = strictMapper.treeToValue(input, ChatMessageRequest.class);

        ObjectNode actual = objectMapper.createObjectNode();
        actual.set("request", input);

        try {
            ChatMessagePairResponse response = chatService.sendMessage(newChatSession(), FIXTURE_USER_ID, request);
            actual.set("response", withoutVolatileFields(objectMapper.valueToTree(response)));

            Long aiMessageId = response.getAiMessage().getMessageId();
            actual.set("sources", withoutVolatileFields(
                    objectMapper.valueToTree(chatService.getMessageSources(aiMessageId, FIXTURE_USER_ID))
            ));
        } catch (BusinessException e) {
            // 검증 실패 등 의도된 예외도 현재 동작의 일부로 기록한다.
            ObjectNode error = actual.putObject("businessError");
            error.put("errorCode", e.getErrorCode().name());
            error.put("message", e.getMessage());
        } catch (RuntimeException e) {
            // 예상치 못한 예외(버그 포함)도 "현재 동작"으로 기록해 두고, 리팩토링 중 바뀌면 드러나게 한다.
            ObjectNode error = actual.putObject("unexpectedError");
            error.put("type", e.getClass().getName());
            error.put("message", e.getMessage());
        }

        assertMatchesSnapshot(scenario + "__" + chip.name(), actual);
    }

    private Long newChatSession() {
        ChatSession session = chatSessionRepository.save(
                new ChatSession(FIXTURE_USER_ID, FIXTURE_TERMS_DOCUMENT_ID, "golden-master")
        );
        chatSessionPolicyRepository.save(
                new ChatSessionPolicy(session.getChatSessionId(), FIXTURE_ANALYSIS_ID)
        );
        return session.getChatSessionId();
    }

    private JsonNode withoutVolatileFields(JsonNode node) {
        if (node instanceof ObjectNode object) {
            object.remove(VOLATILE_FIELDS);
            object.forEach(this::withoutVolatileFields);
        } else if (node.isArray()) {
            node.forEach(this::withoutVolatileFields);
        }
        return node;
    }

    private void assertMatchesSnapshot(String name, JsonNode actual) throws IOException {
        Path file = SNAPSHOT_DIR.resolve(name + ".json");
        String actualJson = normalizeLineEndings(snapshotWriter.writeValueAsString(actual)) + "\n";

        if (UPDATE_SNAPSHOTS) {
            Files.createDirectories(SNAPSHOT_DIR);
            Files.writeString(file, actualJson);
            return;
        }

        if (Files.notExists(file)) {
            fail("스냅샷이 없습니다: %s%n먼저 UPDATE_SNAPSHOTS=true 로 실행해 기록하세요.", file);
        }

        String expectedJson = normalizeLineEndings(Files.readString(file));
        assertThat(actualJson)
                .as("스냅샷과 다릅니다: %s", file)
                .isEqualTo(expectedJson);
    }

    private static String normalizeLineEndings(String text) {
        return text.replace("\r\n", "\n");

    }

    @Test
    @DisplayName("남의 채팅방에는 메시지를 보낼 수 없다")
    void rejectsMessageFromNonOwner() throws IOException {
        Long ownerSessionId = newChatSession();
        Long otherUserId = 999L;

        ObjectNode input = (ObjectNode) objectMapper.readTree(
                ChatAnswerGoldenMasterTest.SCENARIO_DIR.resolve("01-fracture-cast-hospitalization.json").toFile()
        );
        input.put("questionType", QuestionType.CHIP_CLAIM.name());
        ChatMessageRequest request = strictMapper.treeToValue(input, ChatMessageRequest.class);

        assertThatThrownBy(() -> chatService.sendMessage(ownerSessionId, otherUserId, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_SESSION_FORBIDDEN);
    }

    @Test
    @DisplayName("남의 채팅방 대화 내역은 조회할 수 없다")
    void rejectsHistoryFromNonOwner() {
        Long ownerSessionId = newChatSession();

        assertThatThrownBy(() -> chatService.getMessages(ownerSessionId, 999L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_SESSION_FORBIDDEN);
    }
    @Test
    @DisplayName("남의 채팅방 답변 근거는 조회할 수 없다")
    void rejectsSourcesFromNonOwner() throws IOException {
        ObjectNode input = (ObjectNode) objectMapper.readTree(
                SCENARIO_DIR.resolve("01-fracture-cast-hospitalization.json").toFile()
        );
        input.put("questionType", QuestionType.CHIP_CLAIM.name());
        ChatMessageRequest request = strictMapper.treeToValue(input, ChatMessageRequest.class);

        // 주인(2번)이 메시지를 보내서 AI 답변을 하나 만든다
        Long aiMessageId = chatService
                .sendMessage(newChatSession(), FIXTURE_USER_ID, request)
                .getAiMessage()
                .getMessageId();

        // 999번이 그 답변의 근거를 조회하면 막혀야 한다
        assertThatThrownBy(() -> chatService.getMessageSources(aiMessageId, 999L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_SESSION_FORBIDDEN);
    }

}