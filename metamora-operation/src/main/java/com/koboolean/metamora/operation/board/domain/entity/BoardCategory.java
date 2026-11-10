package com.koboolean.metamora.operation.board.domain.entity;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import com.koboolean.metamora.common.jpa.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "TB_PST_CTGRY")
@Getter
@AllArgsConstructor
@NoArgsConstructor
public class BoardCategory extends BaseEntity implements Serializable {

    /* 게시판 카테고리 아이디 */
    @Id
    @Column(name = "CTGRY_CD", length = 6, nullable = false)
    private String categoryId;

    /* 게시판 카테고리 명 */
    @Column(name = "CTGRY_NM", length = 30, nullable = false)
    private String categoryName;

    /* 순번 */
    @Column(name = "SORT_ORD", nullable = false)
    private int orderNumber;

    /* 사용 여부 */
    @Column(name = "USE_YN", length = 1, nullable = false)
    private char useYn;

    /**
     * 연관 관계 Mapping
     * 연관 관계의 주인은 외래 키가 있는 곳으로 지정한다. Board 테이블이 외래 키를 가지고 있기 때문에
     * board.boardCategory가 주인이된다.
     * 따라서 mappedBy = "boardCategory" 속성으로 boardCategory.board가 주인이 아님을 설정한다.
     * 여기서 사용된 mappedBy = "boardCategory" 속성의 boardCategory는 board.boardCategory를
     * 말한다.
     */
    @OneToMany(mappedBy = "boardCategory")
    private List<Board> board = new ArrayList<>();
}
