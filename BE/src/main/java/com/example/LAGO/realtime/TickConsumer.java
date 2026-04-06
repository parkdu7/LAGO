package com.example.LAGO.realtime;

import com.example.LAGO.config.RabbitMQConfig;
import com.example.LAGO.realtime.dto.TickData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TickConsumer {

    private final RealtimeDataService realtimeDataService;

    @RabbitListener(queues = RabbitMQConfig.TICK_QUEUE)
    public void handle(TickData tickData) {
        long start = System.nanoTime();
        try {
            realtimeDataService.saveTickData(tickData);
            long ms = (System.nanoTime() - start) / 1_000_000;
            log.debug("[PERF-AFTER] consumer processing: {}ms | stock={}", ms, tickData.getCode());
        } catch (Exception e) {
            log.error("[TickConsumer] 처리 실패: {}", e.getMessage(), e);
        }
    }
}
