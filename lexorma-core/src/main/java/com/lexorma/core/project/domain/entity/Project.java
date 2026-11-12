package com.lexorma.core.project.domain.entity;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.common.jpa.BaseEntity;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.security.domain.entity.Account;
import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Entity
@Table(name = "TB_PRJ")
@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Project extends BaseEntity implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_PRJ")
    @SequenceGenerator(name = "SQ_PRJ", sequenceName = "SQ_PRJ", allocationSize = 50)
    @Column(name = "PRJ_SN")
    private Long id;

    @Column(name = "PRJ_NM")
    private String projectName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "MNGR_ACNT_SN")
    private Account account; // 프로젝트 관리자

    @Column(name = "ACTVTN_YN")
    private Boolean isActive;

    @OneToMany(mappedBy = "project")
    private List<ProjectMember> projectMember = new ArrayList<>();

    @Column(name = "AUTO_APRV_YN")
    private Boolean isAutoActive; // 자동 승인여부

    @Column(name = "DIC_ABBR_USE_YN")
    private Boolean isDicAbbrUsed; // 데이터 사전 약어 사용 여부

    @Column(name = "TBL_PFX_NM", length = 20)
    private String tablePrefix; // 테이블명 접두어 (예: TB_) - 표준 단어 기준 테이블명 생성 시 사용

    @Enumerated(EnumType.STRING)
    @Column(name = "DB_TYPE_CD", length = 20, nullable = false)
    private DbType dbType; // 대상 DB - 테이블 DDL 생성 기준 (없으면 DbType.DEFAULT)

    /**
     * 대상 DB 를 지정하지 않고 저장하면 기본값(PostgreSQL)으로 저장한다. (Builder 는 필드 초기값을 쓰지 않는다)
     */
    @PrePersist
    @PreUpdate
    void applyDefaults() {
        if (dbType == null) dbType = DbType.DEFAULT;
    }

    private static final Pattern TABLE_PREFIX_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    /**
     * 테이블명 접두어를 정규화한다 : 공백 제거, 대문자, 끝에 '_' 보장 (tb → TB_). 빈 값이면 접두어를 사용하지 않는다(null).
     * 영문으로 시작하고 영문/숫자/'_'만 허용하며 '_' 포함 20자까지 가능하다.
     */
    public static String normalizeTablePrefix(String tablePrefix) {
        if (tablePrefix == null || tablePrefix.isBlank()) {
            return null;
        }

        String prefix = tablePrefix.trim().toUpperCase();
        if (!prefix.endsWith("_")) {
            prefix = prefix + "_";
        }

        if (!TABLE_PREFIX_PATTERN.matcher(prefix).matches() || prefix.length() > 20) {
            throw new CustomException(ErrorCode.INVALID_TABLE_PREFIX, tablePrefix);
        }

        return prefix;
    }
}
