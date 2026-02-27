package com.scribeai.summarize.infra;

import com.scribeai.summarize.application.SummarizeProvider;
import com.scribeai.summarize.application.SummaryResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(prefix = "summarize", name = "provider", havingValue = "mock", matchIfMissing = true)
public class MockSummarizeProvider implements SummarizeProvider {

    // Mock 요약 생성
    @Override
    public SummaryResult summarize(String transcript, String titleHint) {
        return new SummaryResult(
                "AUTO",
                "STANDARD",
                titleHint == null || titleHint.isBlank() ? "[MOCK] 강의/대화 요약" : "[MOCK] " + titleHint,
                "입력 텍스트의 핵심 흐름을 짧게 정리했습니다.",
                List.of(
                        new SummaryResult.SectionNote("도입", "주제와 맥락을 정리했습니다."),
                        new SummaryResult.SectionNote("핵심 내용", "중요 개념과 근거를 항목별로 정리했습니다."),
                        new SummaryResult.SectionNote("마무리", "결론과 다음 확인 포인트를 정리했습니다.")
                ),
                "전체 흐름을 기준으로 핵심 내용을 간결한 문단으로 정리했습니다. "
                        + "주요 개념과 근거를 우선순위에 따라 다시 확인하고, 다음 실행 항목을 분리해 점검하면 이해도를 높일 수 있습니다.",
                List.of("핵심 개념", "요점 정리", "최종 요약"),
                List.of(
                        "영상에서 핵심 결론을 뒷받침한 근거는 무엇인가요?",
                        "설명된 방법을 실제 상황에 적용하려면 어떤 순서가 좋을까요?",
                        "비교된 개념의 차이를 한 문장으로 정리하면 어떻게 말할 수 있나요?"
                ),
                "우주 탐사와 심해 탐사는 둘 다 극한 환경에서 생존 조건을 검증한다는 공통점이 있습니다. "
                        + "실제로 극지·심해 연구 기술은 우주 생명과학 실험 설계에 자주 참고됩니다."
        );
    }
}
