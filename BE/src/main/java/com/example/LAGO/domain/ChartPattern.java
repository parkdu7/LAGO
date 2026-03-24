package com.example.LAGO.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

/**
 * 차트 패턴 엔티티
 * 지침서 명세 CHART_PATTERN 테이블과 완전 일치
 * EC2 데이터베이스 테이블명 CHART_PATTERN (대문자) 사용
 */
@Entity
@Table(name = "chart_pattern")
@Getter 
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChartPattern {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "pattern_id")
    private Integer patternId;

    @Column(name = "name")
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "chart_img")
    private String chartImg;
}