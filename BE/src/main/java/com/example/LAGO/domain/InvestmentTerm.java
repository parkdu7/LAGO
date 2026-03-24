package com.example.LAGO.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.Builder;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "investment_term")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvestmentTerm {
    /**
     * 투자 용어 엔티티
     * 연동된 EC2 DB INVESTMENT_TERM 테이블과 완전 일치
     * 
     * 테이블 구조:
     * - term_id: PK (integer)
     * - term: 용어 (text)
     * - definition: 정의 (text)
     * - description: 설명 (text)
     */

    /**
     * 투자 용어 고유 ID (PK)
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "term_id")
    private Integer termId;

    /**
     * 용어
     */
    @Column(name = "term")
    private String term;

    /**
     * 정의
     */
    @Column(name = "definition")
    private String definition;

    /**
     * 설명
     */
    @Column(name = "description")
    private String description;
}