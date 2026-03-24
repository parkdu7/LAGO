package com.example.LAGO.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.Builder;
import lombok.NoArgsConstructor;
import java.time.LocalDate;

@Entity
@Table(name = "daily_solved")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DailySolved {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "solved_id")
    private Integer solvedId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "quiz_id", nullable = false)
    private Integer quizId;

    @Column(name = "score")
    private Integer score;

    @Column(name = "solved_at", nullable = false)
    private LocalDate solvedAt;

    @Column(name = "streak")
    private Integer streak;

    @Column(name = "solved_time_seconds")
    private Integer solvedTimeSeconds;

    @Column(name = "ranking")
    private Integer ranking;

    @Column(name = "bonus_amount")
    @Builder.Default
    private Integer bonusAmount = 0;
}