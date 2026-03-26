package com.example.LAGO.scheduler;

import com.example.LAGO.dto.response.HistoryChallengeDataResponse;
import com.example.LAGO.exception.ErrorResponse;
import com.example.LAGO.service.HistoryChallengeService;
import com.example.LAGO.service.HistoryChallengeServiceImpl;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

@Component
@RequiredArgsConstructor
public class ChallengeScheduler {

    private static final Logger log = LoggerFactory.getLogger(HistoryChallengeServiceImpl.class);

    private final HistoryChallengeService historyChallengeService;
    private final SimpMessagingTemplate messagingTemplate;

    // 1초마다 메소드 실행
    @Scheduled(fixedRate = 1000)
    public void triggerChallengeDataSend() {
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Seoul"));
        LocalTime startTime = LocalTime.of(15, 0); // 오후 3시
        LocalTime endTime = LocalTime.of(21, 0);   // 오후 9시

        // 오후 3시와 9시 사이일 때만 데이터 전송
        // 테스트 및 시연을 위해 24시간으로 변경
//        if (now.isAfter(startTime) && now.isBefore(endTime)) {
            HistoryChallengeDataResponse latestData = historyChallengeService.getLatestData();
            if (latestData != null) {
                messagingTemplate.convertAndSend("/topic/history-challenge", latestData);
            }
//        }
    }
}
