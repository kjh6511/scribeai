package com.scribeai.realtime.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    /**
     *   1. 영상 듣기 시작
     *   - WS 연결 후 /app/realtime/{documentId}/audio로 청크 전송
     *   - 서버가 자막을 /topic/realtime/{documentId}/captions로 즉시 push
     *   2. 듣기 중단
     *   - /app/realtime/{documentId}/finish 전송
     *   - 이후 청크가 와도 append 차단
     *   3. 요약하기
     *   - POST /api/realtime/documents/{documentId}/summaries
     *   - 응답으로 요약 JSON 받고, 동시에 /topic/realtime/{documentId}/summaries로도 push
     * **/

    // STOMP 엔드포인트 등록
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
    }

    // STOMP 브로커 경로 설정
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.enableSimpleBroker("/topic");
    }

    // 오디오 청크 전송을 위한 WS/STOMP 메시지 크기 한도 확장
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registry) {
        registry.setMessageSizeLimit(2 * 1024 * 1024);
        registry.setSendBufferSizeLimit(4 * 1024 * 1024);
        registry.setSendTimeLimit(20_000);
    }
}
