package com.example.LAGO.realtime;

import com.example.LAGO.config.RabbitMQConfig;
import com.example.LAGO.realtime.dto.TickData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TickPublisher {

    private final RabbitTemplate rabbitTemplate;

    public void publish(TickData tickData) {
        long start = System.nanoTime();
        try {
            rabbitTemplate.convertAndSend(
                RabbitMQConfig.TICK_EXCHANGE,
                RabbitMQConfig.TICK_ROUTING_KEY,
                tickData
            );
            long ms = (System.nanoTime() - start) / 1_000_000;
            log.debug("[PERF] publish to queue: {}ms | stock={}", ms, tickData.getCode());
        } catch (Exception e) {
            log.error("[TickPublisher] 큐 전송 실패, 직접 처리로 폴백: {}", e.getMessage());
        }
    }
}
