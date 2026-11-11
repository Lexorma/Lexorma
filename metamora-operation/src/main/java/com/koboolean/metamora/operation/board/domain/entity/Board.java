package com.koboolean.metamora.operation.board.domain.entity;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.koboolean.metamora.common.i18n.Messages;
import com.koboolean.metamora.common.jpa.BaseEntity;

import com.koboolean.metamora.core.security.domain.entity.Account;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "TB_PST")
@Builder
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class Board extends BaseEntity implements Serializable {

    /* 게시글 번호 */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_PST")
    @SequenceGenerator(name = "SQ_PST", sequenceName = "SQ_PST", allocationSize = 50)
    @Column(name = "PST_SN")
    private Long id;

    /* 프로젝트 아이디 */
    @Column(name = "PRJ_SN")
    private Long projectId;

    /* 회원 아이디 */
    @Column(name = "WRTR_ID", length = 16, nullable = false)
    private String username;

    /* 게시글 제목 */
    @Column(name = "PST_TTL", length = 100, nullable = false)
    private String title;

    /* 게시글 내용 */
    @Column(name = "PST_CN", columnDefinition = "TEXT")
    private String content;

    /* 삭제 여부 */
    @Column(name = "DEL_YN", length = 1, nullable = false)
    private char deleteYn;
    
    /* 화면에 보여줄 용도로 사용되는 컬럼.
     * BaseEntity를 상속받아 데이터 생성, 수정 날짜를 관리하고 있지만, 이는 데이터관리만의 목적이고 화면에 표시하기 위함은 아니다.
     * 화면에 표시하려면 화면 표시용의 컬럼이 있어야 하고 그것이 이 updatedTime 컬럼이다.
     * 이 방식이 아니라면 QueryDSL을 사용해야한다.
     */
    @Column(name = "PST_MDFCN_DT")
    private LocalDateTime updatedTime;

    /* 게시판 카테고리 아이디 - 연관관계에서 매핑됨 */
    // @Column(name = "CTGRY_CD", length = 6, nullable = false)
    // private String categoryId;

    /* 연관 관계 Mapping
     * Board(N) : BoardCategory(1)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "CTGRY_CD")
    private BoardCategory boardCategory;

    /* 연관 관계 Mapping
     * Board(1) : File(N)
     */
    @OneToMany(mappedBy = "board")
    private List<File> file = new ArrayList<>();

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "TB_PST_ACNT",
            joinColumns = @JoinColumn(name = "PST_SN"),
            inverseJoinColumns = @JoinColumn(name = "ACNT_SN"),
            uniqueConstraints = {
                    @UniqueConstraint(columnNames = {"PST_SN", "ACNT_SN"})
            }
    )
    private Set<Account> accounts = new HashSet<>();

    @Column(name = "PRJ_SN_LIST")
    private List<Long> projectIds;

    /* 시스템이 등록한 공지(V0 기본 안내 공지) 여부.
     * 제목·내용 모두 영어판이 등록된 글이며(EnglishMessages), 요청 언어에 맞춰 보여 주고 수정은 막는다.
     */
    public boolean isSystemNotice() {
        return Messages.hasEnglish(title) && Messages.hasEnglish(content);
    }
}
