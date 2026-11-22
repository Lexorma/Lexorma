package com.lexorma.data.column.service;

import com.lexorma.common.exception.CustomException;
import jakarta.persistence.EntityManager;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 같은 테이블 안 물리 컬럼명 유일성의 비교 기준 (M7-U01 · M7-U06 B3, V5 — docs/completed/column-name-uniqueness-m7.md).
 * <p>
 * 기준은 앞뒤 공백(U+0020 만 — 탭·줄바꿈·전각 공백은 지우지 않는다)을 지우고 <b>로캘 중립 Unicode 확장 대문자</b>로 바꾼 값이다 :
 * {@code String.toUpperCase(Locale.ROOT)} — 문자열 전체 변환이라 글자 수가 늘 수 있다 (ß → SS, ﬁ → FI, ΐ → Ϊ́). JVM 기본 로캘(터키어 i → İ 등)에 의존하지 않는다.
 * Unicode Case Folding · NFC · NFKC 정규화가 아니다 (é 와 e + U+0301 은 다르고, ＡＢＣ 와 ABC 도 다르다).
 * DB 는 같은 뜻의 생성 컬럼 {@code TB_COL.COL_NM_CMPR_VL} (varchar(765)) 과 UNIQUE {@code UX_COL_01 (TBL_SN, COL_NM_CMPR_VL)} 로 최종 방어한다 :
 * PostgreSQL {@code upper(trim(COL_NM) collate "und-x-icu")} (DB 기본 로캘과 무관, 컬럼은 collate "C"), H2 {@code FN_UPPER_ROOT(trim(COL_NM))}
 * (= H2 의 {@code StringUtils.toUpperEnglish}, Locale.ENGLISH — 대소문자 변환은 ROOT 와 같다).
 * 이 값은 비교에만 쓴다 — 저장된 컬럼명(COL_NM)을 바꾸지 않는다.
 * <p>
 * DB 에 있는 컬럼과 비교할 때는 DB 가 계산해 둔 {@code COL_NM_CMPR_VL} 을 이 값과 비교한다 ({@code ColumnInfoRepository.findSameName}).
 * Java(21 = Unicode 15.0)와 PostgreSQL ICU(Docker postgres:18.6 = ICU 76, Unicode 16.0)는 Unicode 15.0 이후 새로 생긴 대소문자 짝 27개 글자에서 다르다
 * (문서 14장) — 그런 글자는 애플리케이션 검사가 놓칠 수 있고 DB UNIQUE 가 최종 판정한다 ({@link #flush} 가 같은 중복 오류로 바꾼다).
 */
public final class ColumnNameKey {

    /** DB UNIQUE 제약 이름 (V5) */
    public static final String CONSTRAINT = "UX_COL_01";

    private ColumnNameKey() {
    }

    /** 비교 값. null 은 null (DB 의 UNIQUE 도 NULL 을 비교하지 않는다) */
    public static String of(String name) {
        if (name == null) return null;
        int begin = 0;
        int end = name.length();
        while (begin < end && name.charAt(begin) == ' ') begin++;
        while (end > begin && name.charAt(end - 1) == ' ') end--;
        return name.substring(begin, end).toUpperCase(Locale.ROOT);
    }

    /**
     * 빈 컬럼명인가 (M7-U05) : null, 빈 문자열, 또는 ASCII 공백류(U+0020 · 탭 · LF · VT · FF · CR)만으로 된 이름. DB 의 CK_COL_01
     * ({@code trim(translate(COL_NM, 탭·LF·VT·FF·CR, 공백 5개)) <> ''}, V5) 과 같은 기준이다 — PostgreSQL·H2 가 같은 뜻으로 계산한다.
     * NBSP·전각 공백 같은 다른 유니코드 공백만으로 된 이름은 빈 이름으로 보지 않는다 (기존 식별자 허용 범위 그대로 — 비교 값의 trim 범위(U+0020)와는 다른 판단이다)
     */
    public static boolean isBlankName(String name) {
        if (name == null) return true;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c != ' ' && c != '\t' && c != '\n' && c != 0x0B && c != '\f' && c != '\r') return false;
        }
        return true;
    }

    /** 같은 테이블에서 두 이름이 같은 이름인가 (둘 중 하나라도 null 이면 아니다) */
    public static boolean same(String a, String b) {
        return a != null && b != null && Objects.equals(of(a), of(b));
    }

    /** 이 예외가 컬럼명 UNIQUE(UX_COL_01) 위반인가 — FK·NOT NULL·다른 UNIQUE 위반은 아니다 */
    public static boolean isNameConflict(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException c && mentions(c.getConstraintName())) return true;
            if (t instanceof SQLException s && mentions(s.getMessage())) return true;
        }
        return false;
    }

    /**
     * 지금까지의 쓰기를 DB 로 내보낸다. 컬럼명 UNIQUE 위반이면 conflict 의 예외로 바꾸고 (트랜잭션은 롤백된다 — 일부만 반영하지 않는다), 다른 오류는 그대로 던진다.
     * 애플리케이션 검사는 테이블 행 잠금 뒤에 같은 기준으로 하므로 보통은 여기까지 오지 않는다 (DB 와 Java 의 대문자 규칙이 다른 글자, 잠그지 않은 쓰기)
     */
    public static void flush(EntityManager entityManager, Supplier<CustomException> conflict) {
        try {
            entityManager.flush();
        } catch (RuntimeException e) {
            if (isNameConflict(e)) throw conflict.get();
            throw e;
        }
    }

    private static boolean mentions(String text) {
        return text != null && text.toUpperCase(Locale.ROOT).contains(CONSTRAINT);
    }
}
