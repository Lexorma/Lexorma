package com.lexorma.data.physical.sync;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.common.i18n.Messages;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.table.domain.entity.TableInfo;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * 프로젝트 행을 배타로 먼저 잡는 구조 변경(물리명 일괄 적용·테이블 이름 변경·스키마 이동·원본 삭제 반영·누락 FK 보정)이 쓰는 행 잠금 (M7-FK-D06·D07,
 * docs/completed/physical-fk-correction-m7.md). 프로젝트를 잡은 뒤에는 어떤 행 잠금도 기다리지 않는다 : 일반 저장은 행을 잠근 채 커밋 직전에 프로젝트 행을
 * 공유 잠금으로 확인하므로, 기다리면 서로를 기다린다 (PostgreSQL 40P01). 바쁘면 {@link #busy()} (409 MODEL_CONFLICT) 로 끝낸다.
 * <ul>
 *   <li>{@link #tablesNoWait}·{@link #columnsNoWait}·{@link #schemasNoWait} : JPA PESSIMISTIC_WRITE + NOWAIT. PostgreSQL 에서는 {@code FOR NO KEY UPDATE NOWAIT} 다
 *       — FK 검사의 {@code FOR KEY SHARE} 와 함께 잡힌다 (다른 트랜잭션이 그 행을 부모로 하는 관계를 만드는 중이어도 막지 않는다).</li>
 *   <li>{@link #tablesForKeyChange}·{@link #columnsForKeyChange} : <b>키를 바꿀 행</b>(이름 = UNIQUE UX_TBL_01·UX_COL_01 의 열, 스키마 이동, 삭제)만
 *       네이티브 {@code FOR UPDATE NOWAIT}. 키를 바꾸는 UPDATE·DELETE 는 PostgreSQL 에서 FOR UPDATE 강도라 다른 트랜잭션의 KEY SHARE(그 행을 부모로 하는 관계 INSERT —
 *       엑셀 업로드의 청크 동기화 등)를 <b>기다린다</b>. 프로젝트 행을 쥔 채 그 대기에 들어가면 교착(S6b)이므로, 같은 강도를 미리 NOWAIT 로 잡아 바쁘면 409 로 끝낸다.</li>
 * </ul>
 * 모든 목록은 ID 오름차순으로 한 번에 잠근다 (같은 종류 안의 순서 = 공통 정책). 종류 사이 순서는 부르는 쪽이 프로젝트 → 논리 모델 → 스키마 → 테이블 → 컬럼으로 지킨다.
 */
@Component
public class ModelRowLocks {

    private static final Map<String, Object> NO_WAIT = Map.of("jakarta.persistence.lock.timeout", 0);
    private static final int CHUNK = 1000;

    @PersistenceContext
    private EntityManager entityManager;

    /** 스키마 행 (이름 공간) : 주어진 순서대로 (ObjectNamespace 순서 — 기본 스키마 먼저) */
    public void schemasNoWait(List<Long> orderedSchemaIds) {
        noWait(() -> {
            for (Long id : orderedSchemaIds) {
                if (entityManager.find(SchemaInfo.class, id, LockModeType.PESSIMISTIC_WRITE, NO_WAIT) == null) {
                    throw new CustomException(ErrorCode.SCHEMA_IS_NOT_DEFINED);
                }
            }
            return null;
        });
    }

    /** 테이블 행 (FOR NO KEY UPDATE NOWAIT), ID 순. 잠근 엔티티 목록 (없는 ID 는 빠진다) */
    public List<TableInfo> tablesNoWait(Collection<Long> tableIds) {
        return noWait(() -> {
            List<TableInfo> out = new ArrayList<>();
            for (Long id : sorted(tableIds)) {
                TableInfo t = entityManager.find(TableInfo.class, id, LockModeType.PESSIMISTIC_WRITE, NO_WAIT);
                if (t != null) out.add(t);
            }
            return out;
        });
    }

    /** 컬럼 행 (FOR NO KEY UPDATE NOWAIT), ID 순 한 번에 */
    public List<ColumnInfo> columnsNoWait(Collection<Long> columnIds) {
        TreeSet<Long> ids = sorted(columnIds);
        if (ids.isEmpty()) return List.of();
        return noWait(() -> {
            List<ColumnInfo> out = new ArrayList<>();
            for (List<Long> chunk : chunks(ids)) {
                out.addAll(entityManager.createQuery("select c from ColumnInfo c where c.id in :ids order by c.id", ColumnInfo.class).setParameter("ids", chunk)
                        .setLockMode(LockModeType.PESSIMISTIC_WRITE).setHint("jakarta.persistence.lock.timeout", 0).getResultList());
            }
            return out;
        });
    }

    /** 이름·스키마를 바꾸거나 지울 테이블 행 : FOR UPDATE NOWAIT (키 강도), ID 순 */
    public void tablesForKeyChange(Collection<Long> tableIds) {
        keyLock("select TBL_SN from TB_TBL where TBL_SN in (:ids) order by TBL_SN for update nowait", tableIds);
    }

    /**
     * 이름을 바꿀 스키마 행 : FOR UPDATE NOWAIT (키 강도 — SCHM_NM 은 UX_SCHM_01 의 열), ID 순 (M8-C4b-R1). 테이블 등록·참조 관계 동기화가 그 스키마 행에 거는 KEY SHARE 를
     * 프로젝트 행을 쥔 채 커밋 때 기다리지 않게 미리 잡는다 (테이블 이름 변경의 {@link #tablesForKeyChange} 와 같은 이유)
     */
    public void schemasForKeyChange(Collection<Long> schemaIds) {
        keyLock("select SCHM_SN from TB_SCHM where SCHM_SN in (:ids) order by SCHM_SN for update nowait", schemaIds);
    }

    /** 이름을 바꾸거나 지울 컬럼 행 : FOR UPDATE NOWAIT (키 강도), ID 순 */
    public void columnsForKeyChange(Collection<Long> columnIds) {
        keyLock("select COL_SN from TB_COL where COL_SN in (:ids) order by COL_SN for update nowait", columnIds);
    }

    private void keyLock(String sql, Collection<Long> ids) {
        TreeSet<Long> sorted = sorted(ids);
        if (sorted.isEmpty()) return;
        noWait(() -> {
            for (List<Long> chunk : chunks(sorted)) entityManager.createNativeQuery(sql).setParameter("ids", chunk).getResultList();
            return null;
        });
    }

    /** 잠금 작업 : NOWAIT 로 얻지 못하면 409. 교착 등 다른 오류는 그대로 던진다 (409 로 바꾸지 않는다) */
    public static <T> T noWait(Supplier<T> work) {
        try {
            return work.get();
        } catch (RuntimeException e) {
            if (e instanceof CustomException || !lockNotAvailable(e)) throw e;
            throw busy();
        }
    }

    /** NOWAIT 로 잠금을 바로 얻지 못했다 : PostgreSQL SQLSTATE 55P03 (40P01 교착은 아니다) */
    public static boolean lockNotAvailable(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql) return "55P03".equals(sql.getSQLState());
        }
        return false;
    }

    /** NOWAIT 잠금 실패 (409 MODEL_CONFLICT, 원인 LOCKED — M8-C4c-2 C4c-D10) : 실제로 잠금을 얻지 못한 곳에서만 */
    public static CustomException busy() {
        return CustomException.locked(ErrorCode.MODEL_CONFLICT, "다른 사용자가 같은 모델·테이블·컬럼을 바꾸는 중입니다. 잠시 뒤 다시 시도해주세요.");
    }

    /**
     * 잠근 뒤 다시 본 상태가 잠그기 전에 판단한 것과 다르다 (그 사이 다른 저장이 커밋했다 — 잠금 실패가 아니라 데이터 변경) : 원인 표시 없는 409 MODEL_CONFLICT.
     * 예 : 일반 저장으로 판단했는데 잠근 뒤 PK 구성이 바뀌어 있다(D11·R2), 동기화 대상이 다시 계산할 때마다 바뀐다(R2-D05)
     */
    public static CustomException changedMeanwhile() {
        return new CustomException(ErrorCode.MODEL_CONFLICT, "충돌한 객체 : {0}", Messages.get("다른 사용자가 같은 테이블의 구성을 먼저 바꿨습니다. 최신 내용을 확인한 뒤 다시 저장해주세요."));
    }

    private static TreeSet<Long> sorted(Collection<Long> ids) {
        TreeSet<Long> set = new TreeSet<>();
        if (ids != null) ids.stream().filter(Objects::nonNull).forEach(set::add);
        return set;
    }

    private static List<List<Long>> chunks(TreeSet<Long> ids) {
        List<List<Long>> out = new ArrayList<>();
        List<Long> all = new ArrayList<>(ids);
        for (int i = 0; i < all.size(); i += CHUNK) out.add(all.subList(i, Math.min(all.size(), i + CHUNK)));
        return out;
    }
}
