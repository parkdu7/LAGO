package com.example.LAGO.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.Builder;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "know_term")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KnowTerm {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "know_id")
    private Integer knowId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "term_id", nullable = false)
    private Integer termId;

    @Column(name = "correct")
    private Boolean correct;
}