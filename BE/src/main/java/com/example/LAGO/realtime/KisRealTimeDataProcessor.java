package com.example.LAGO.realtime;

import com.example.LAGO.realtime.dto.TickData;
// import com.example.LAGO.service.RealtimeTradingService;  // 서비스 제거됨
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.time.*;
import java.time.format.DateTimeFormatter;

// 국내주식 실시간 체결가 데이터 파싱

@Service
@Slf4j
public class KisRealTimeDataProcessor {

    private final RealtimeDataService realtimeDataService;
    private final TickPublisher tickPublisher;
    private final ObjectMapper objectMapper;
    private final RealTimeDataBroadcaster broadcaster;
    // private final RealtimeTradingService realtimeTradingService;  // 서비스 제거됨
    
    // KIS API 데이터 스펙 상수
    private static final int EXPECTED_MIN_FIELDS = 13; // 최소 필요 필드 수
    // 클래스 상단에 상수 추가
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HHmmss");
    private static final DateTimeFormatter ISO_OFFSET = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    // KST 변환 유틸 메서드 추가 (날짜가 따로 없으므로 "해당 데이터가 속한 KST 날짜"를 넣어야 함)
    // 실시간이면 today(KST), 과거 재처리면 '그 날'을 외부에서 넘겨 써야 더 정확합니다.
    private String toKstIso8601FromHhmmss(String hhmmss) {
        if (hhmmss == null || hhmmss.isBlank()) return null;
        LocalTime time = LocalTime.parse(hhmmss, HHMMSS);
        LocalDate dateKst = LocalDate.now(KST);  // ⚠️ 과거 데이터면 해당 영업일자를 써야 함
        ZonedDateTime kstZdt = ZonedDateTime.of(dateKst, time, KST);
        return kstZdt.format(ISO_OFFSET); // 예: 2025-08-12T12:30:20+09:00
    }

    // (선택) Instant로도 필요하면 함께 만들 수 있음
    private Instant toKstInstantFromHhmmss(String hhmmss) {
        if (hhmmss == null || hhmmss.isBlank()) return null;
        LocalTime time = LocalTime.parse(hhmmss, HHMMSS);
        LocalDate dateKst = LocalDate.now(KST);
        return ZonedDateTime.of(dateKst, time, KST).toInstant(); // UTC 기준 절대시각
    }


    public KisRealTimeDataProcessor(RealtimeDataService realtimeDataService, TickPublisher tickPublisher,
                                    ObjectMapper objectMapper, RealTimeDataBroadcaster broadcaster) {
        this.realtimeDataService = realtimeDataService;
        this.tickPublisher = tickPublisher;
        this.objectMapper = objectMapper;
        this.broadcaster = broadcaster;
        // this.realtimeTradingService = realtimeTradingService;  // 서비스 제거됨
    }
    
    // KIS 실시간 데이터 컬럼 인덱스 (필수 데이터만)
    private static final int MKSC_SHRN_ISCD = 0;  // 종목코드
    private static final int STCK_CNTG_HOUR = 1;  // 체결시간
    private static final int STCK_PRPR = 2;       // 현재가
    private static final int PRDY_VRSS = 4;     // 전일 대비
    private static final int PRDY_CTRT = 5;     // 등락률
    private static final int STCK_OPRC = 7;       // 시가
    private static final int STCK_HGPR = 8;       // 고가
    private static final int STCK_LWPR = 9;       // 저가
    private static final int CNTG_VOL = 12;       // 거래량
    
    /**
     * 웹소켓에서 받은 원시 메시지를 처리하는 메인 메서드
     * 하트비트, JSON 시스템 메시지 등을 필터링하고 실제 틱 데이터만 처리
     * 다중 데이터 지원: 0|H0STCNT0|004|...에서 004는 4건의 데이터를 의미
     * @param rawMessage 웹소켓 원시 메시지
     */
    public void processStockData(String rawMessage) {
        // 1. 원시 메시지 파싱 (하트비트 및 시스템 메시지 필터링)
        Optional<List<TickData>> tickDataListOpt = parseRawMessage(rawMessage);
        
        // 2. 파싱 결과가 없으면 (하트비트, 시스템 메시지 등) 무시
        if (tickDataListOpt.isEmpty()) {
            return;
        }
        
        // 3. 파싱된 틱 데이터 리스트 처리
        List<TickData> tickDataList = tickDataListOpt.get();
        int processedCount = 0;
        
        for (TickData tickData : tickDataList) {
            if (tickData != null && tickData.isValid()) {
                processTickData(tickData);
                processedCount++;
            } else {
                log.warn("Invalid tick data in batch: {}", (tickData != null ? tickData.getCode() : "null"));
            }
        }
        
        log.info("Processed {}/{} stock data records from batch", processedCount, tickDataList.size());
    }
    
    /**
     * TickData 직접 처리
     * @param tickData 파싱된 틱 데이터
     */
    private void processTickData(TickData tickData) {
        // 1. RabbitMQ 큐에 전달 (비동기 처리)
        tickPublisher.publish(tickData);

        // 2. 실시간 프론트엔드 전송 (큐와 별개로 즉시 전송)
        broadcaster.sendRealTimeData(tickData);
        
        // 3. 실시간 매매 처리 (AutoTradingBotService로 교체됨)
        // realtimeTradingService.processRealtimeOrders(tickData);
        
        // TODO: 4. 1분봉 집계 서비스로 전달
        // minuteCandleService.addTick(tickData);
        
        log.debug("Processed and saved to Redis: {} - {}", tickData.getCode(), tickData.getClosePrice());
    }
    
    /**
     * 하트비트 메시지 식별
     * @param rawMessage 원시 메시지
     * @return 하트비트 여부
     */
    private boolean isHeartbeat(String rawMessage) {
        if (rawMessage == null || rawMessage.isEmpty()) return false;
        if (rawMessage.charAt(0) != '{') return false; // JSON이 아니면 하트비트 아님
        
        try {
            JsonNode node = objectMapper.readTree(rawMessage);
            String trId = node.path("header").path("tr_id").asText("");
            return "PINGPONG".equalsIgnoreCase(trId);
        } catch (Exception e) {
            log.debug("Failed to parse potential heartbeat message: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * 원시 메시지를 파싱하여 TickData 리스트 반환
     * 하트비트 및 시스템 메시지는 Optional.empty() 반환
     * @param rawMessage 원시 메시지
     * @return 파싱된 TickData 리스트 (Optional)
     */
    private Optional<List<TickData>> parseRawMessage(String rawMessage) {
        // 1. 하트비트 체크 - 즉시 무시
        if (isHeartbeat(rawMessage)) {
            log.debug("WS heartbeat received - ignored");
            return Optional.empty();
        }
        
        // 2. JSON vs 파이프프레임 구분
        if (rawMessage != null && rawMessage.startsWith("{")) {
            // JSON 형태의 시스템 메시지 - 무시
            log.debug("Non-tick JSON message ignored: {}", rawMessage.substring(0, Math.min(100, rawMessage.length())));
            return Optional.empty();
        }
        
        // 3. 파이프프레임 유효성 검증
        String[] parts = rawMessage.split("\\|", -1);
        if (parts.length < 4) {
            log.debug("Malformed pipe frame (parts < 4): {}", rawMessage);
            return Optional.empty();
        }
        
        // 4. 실시간 체결가 데이터 여부 확인
        KisRawMessage parsed = new KisRawMessage(parts[0], parts[1], parts[2], parts[3]);
        if (!isRealTimeTickData(parsed)) {
            log.debug("Non-tick data message ignored: messageType={}, trId={}", parts[0], parts[1]);
            return Optional.empty();
        }
        
        // 5. CSV 필드 개수 검증
        String[] fields = parts[3].split("\\^", -1);
        if (fields.length < EXPECTED_MIN_FIELDS) {
            log.debug("Insufficient fields in CSV data: {} (need at least {})", fields.length, EXPECTED_MIN_FIELDS);
            return Optional.empty();
        }
        
        // 6. 다중 데이터 파싱
        List<TickData> tickDataList = parseMultipleTickData(parsed);
        return Optional.of(tickDataList);
    }
    
    /**
     * 실시간 체결가 데이터인지 확인
     * @param message 파싱된 메시지
     * @return 체결가 데이터 여부
     */
    private boolean isRealTimeTickData(KisRawMessage message) {
        return "0".equals(message.getMessageType()) && 
               "H0STCNT0".equals(message.getTrId());
    }
    
    /**
     * 다중 데이터를 포함한 메시지를 파싱하여 TickData 리스트 반환
     * @param parsed 파싱된 원시 메시지
     * @return TickData 리스트
     */
    private List<TickData> parseMultipleTickData(KisRawMessage parsed) {
        List<TickData> tickDataList = new ArrayList<>();
        
        try {
            // 1. 데이터 개수 파싱 (metadata에서 추출)
            int dataCount = parseDataCount(parsed.getMetadata());
            System.out.println("Processing " + dataCount + " tick data records in batch");
            
            // 2. CSV 데이터 전체를 ^ 기준으로 분할
            String[] allFields = parsed.getCsvData().split("\\^");
            
            // 3. KIS API 데이터 스펙: 각 체결 데이터는 고정 필드 수를 가짐
            int fieldsPerRecord = calculateFieldsPerRecord(allFields.length, dataCount);
            System.out.println("Fields per record: " + fieldsPerRecord + ", Total fields: " + allFields.length);
            
            // 4. 각 데이터별로 분할하여 TickData 생성
            for (int i = 0; i < dataCount; i++) {
                int startIndex = i * fieldsPerRecord;
                int endIndex = startIndex + fieldsPerRecord;
                
                if (endIndex <= allFields.length) {
                    // 해당 레코드의 필드만 추출
                    String[] recordFields = Arrays.copyOfRange(allFields, startIndex, endIndex);
                    TickData tickData = parseToTickData(recordFields);
                    
                    if (tickData != null) {
                        tickDataList.add(tickData);
                    }
                } else {
                    System.err.println("Insufficient fields for record " + (i + 1) + ": expected " + endIndex + ", but got " + allFields.length);
                }
            }
            
        } catch (Exception e) {
            log.error("Error parsing multiple tick data: {}", e.getMessage(), e);
        }
        
        return tickDataList;
    }
    
    /**
     * 메타데이터에서 데이터 개수 파싱
     * @param metadata 메타데이터 (예: "004")
     * @return 데이터 개수
     */
    private int parseDataCount(String metadata) {
        try {
            // 메타데이터가 숫자 형태인 경우 (예: "004" -> 4)
            return Integer.parseInt(metadata.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid metadata format for data count: {}, defaulting to 1", metadata);
            return 1; // 기본값: 단일 데이터
        }
    }
    
    /**
     * 레코드당 필드 수 계산
     * @param totalFields 전체 필드 수
     * @param dataCount 데이터 개수
     * @return 레코드당 필드 수
     */
    private int calculateFieldsPerRecord(int totalFields, int dataCount) {
        if (dataCount == 0) {
            log.warn("Data count is 0, cannot calculate fields per record");
            return totalFields; // 전체를 하나로 처리
        }
        
        int fieldsPerRecord = totalFields / dataCount;
        
        // 나머지가 있는 경우 경고
        if (totalFields % dataCount != 0) {
            log.warn("Total fields ({}) not evenly divisible by data count ({})", totalFields, dataCount);
        }
        
        return fieldsPerRecord;
    }
    
    /**
     * 단일 레코드의 필드 배열을 TickData 객체로 변환
     * @param recordFields 단일 레코드의 필드 배열
     * @return TickData 객체
     */
    private TickData parseToTickData(String[] recordFields) {
        try {
            // 최소 필드 수 검증 (거래량이 12번째 인덱스이므로 13개 이상 필요)
            if (recordFields.length < EXPECTED_MIN_FIELDS) {
                log.debug("Insufficient fields in record data: {} (need at least {})", recordFields.length, EXPECTED_MIN_FIELDS);
                return null;
            }

            // 기존 HHmmss 원본
            String hhmmss = parseString(recordFields[STCK_CNTG_HOUR]);

            // ✅ KST로 변환한 ISO-8601(+09:00) 문자열
            String kstIso = toKstIso8601FromHhmmss(hhmmss);
            
            return TickData.builder()
                    .code(parseString(recordFields[MKSC_SHRN_ISCD]))
                    .date(parseString(recordFields[STCK_CNTG_HOUR]))    //.date(kstIso) : KST로 변환한 경우
                    .closePrice(parseInteger(recordFields[STCK_PRPR]))
                    .openPrice(parseInteger(recordFields[STCK_OPRC]))
                    .highPrice(parseInteger(recordFields[STCK_HGPR]))
                    .lowPrice(parseInteger(recordFields[STCK_LWPR]))
                    .volume(parseInteger(recordFields[CNTG_VOL]))
                    .fluctuationRate((parseDecimal(recordFields[PRDY_CTRT])))
                    .previousDay((parseInteger(recordFields[PRDY_VRSS])))   // 전일 대비
                    .build();
                    
        } catch (Exception e) {
            log.error("Error parsing single record data: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * 문자열 파싱 (null/empty 체크)
     * @param value 원시 값
     * @return 파싱된 문자열
     */
    private String parseString(String value) {
        return (value != null && !value.trim().isEmpty()) ? value.trim() : null;
    }
    
    /**
     * 정수 파싱 (null/empty 체크)
     * @param value 원시 값
     * @return 파싱된 정수
     */
    private Integer parseInteger(String value) {
        try {
            if (value == null || value.trim().isEmpty()) {
                return null;
            }
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * BigDecimal 파싱(등락률)
     * @param value 원시 값
     * @return 파싱된 BigDecimal
     */
    private BigDecimal parseDecimal(String value) {
        if (value == null) return null;
        String s = value.trim();
        if (s.isEmpty()) return null;
        try {
            // 문자열 기반 생성 (new BigDecimal(double) 금지)
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }


    /**
     * 원시 메시지를 담는 내부 클래스
     */
    private static class KisRawMessage {
        private final String messageType;
        private final String trId;
        private final String metadata;
        private final String csvData;
        
        public KisRawMessage(String messageType, String trId, String metadata, String csvData) {
            this.messageType = messageType;
            this.trId = trId;
            this.metadata = metadata;
            this.csvData = csvData;
        }
        
        public String getMessageType() { return messageType; }
        public String getTrId() { return trId; }
        public String getMetadata() { return metadata; }
        public String getCsvData() { return csvData; }
    }
}