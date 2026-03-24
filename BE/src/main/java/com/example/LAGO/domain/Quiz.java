package com.example.LAGO.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.Builder;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "quiz")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Quiz {
    /**
     * 퀴즈 엔티티
     * 연동된 EC2 DB QUIZ 테이블과 완전 일치
     * 
     * 테이블 구조:
     * - quiz_id: PK (integer)
     * - question: 문제 (text)
     * - answer: 정답 (boolean)
     * - daily_date: 일일 퀴즈 날짜 (timestamp)
     * - explanation: 해설 (text)
     * - term_id: 투자 용어 ID (integer)
     */

    /**
     * 퀴즈 고유 ID (PK)
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "quiz_id")
    private Integer quizId;

    /**
     * 문제
     */
    @Column(name = "question")
    private String question;

    /**
     * 정답 (true/false)
     */
    @Column(name = "answer")
    private Boolean answer;

    /**
     * 일일 퀴즈 날짜
     */
    @Column(name = "daily_date")
    private LocalDateTime dailyDate;

    /**
     * 해설
     */
    @Column(name = "explanation")
    private String explanation;

    /**
     * 투자 용어 ID (외래키)
     */
    @Column(name = "term_id")
    private Integer termId;
}