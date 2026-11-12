package com.lexorma.data.dictionary.domain.entity;

import com.lexorma.common.jpa.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(name = "TB_STD_TRM_WORD")
public class StandardTermWordMapping extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_STD_TRM_WORD")
    @SequenceGenerator(name = "SQ_STD_TRM_WORD", sequenceName = "SQ_STD_TRM_WORD", allocationSize = 50)
    @Column(name = "STD_TRM_WORD_SN")
    private Long id;

    @ManyToOne
    @JoinColumn(name = "STD_TRM_SN")
    private StandardTerm standardTerm;

    @ManyToOne
    @JoinColumn(name = "STD_WORD_SN")
    private StandardWord standardWord;

    @Column(name = "SORT_ORD")
    private int orderIndex; // 사용자_이름 → 사용자(1), 이름(2)
}
