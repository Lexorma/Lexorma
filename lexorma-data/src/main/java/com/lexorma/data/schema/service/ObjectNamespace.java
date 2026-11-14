package com.lexorma.data.schema.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.repository.SchemaInfoRepository;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.data.view.repository.PhysicalViewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;

/**
 * 스키마의 TABLE·VIEW 이름 공간 (M-15, 2026-11-13 스키마 단위로 변경). DBMS 처럼 같은 스키마의 테이블과 VIEW 는 이름을 나눠 쓰고(대문자 비교),
 * 다른 스키마에는 같은 이름이 있을 수 있다. 각 저장 구조의 UNIQUE(UX_TBL_01·UX_VIEW_01 = 스키마 + 이름)는 같은 종류끼리만 막으므로,
 * 이름을 만들거나 바꾸는 쓰기는 {@link #lock} 으로 그 스키마의 이름 공간을 잠근 뒤 {@link #conflict} 로 확인한다.
 * <p>
 * 잠금 = 스키마 행(배타). 한 트랜잭션에서 필요한 스키마를 <b>한 번에</b> 잠그고, 순서는 기본 스키마 → 나머지 스키마 ID 오름차순이다 (교착 없음).
 * 그다음에 테이블·VIEW 행을 잠근다. 기본 스키마 행은 프로젝트의 <b>VIEW 참조 그래프</b> 잠금도 겸한다 :
 * VIEW 의 등록·수정·변경 요청 처리·삭제와 테이블 삭제는 스키마와 관계없이 기본 스키마 행을 함께 잠근다 (스키마를 넘는 순환 검사·참조 대상 삭제를 직렬화).
 * 테이블의 등록·이름 변경·이동·엑셀 업로드는 자기 스키마만 잠그므로 다른 스키마의 등록은 서로 기다리지 않는다.
 * 프로젝트 행은 여기서 잠그지 않는다 : 커밋 직전 {@code ProjectWriteGuard} 가 확인한다.
 */
@Component
@RequiredArgsConstructor
public class ObjectNamespace {

    public enum Kind { TABLE, VIEW }

    private final SchemaService schemaService;
    private final SchemaInfoRepository schemaInfoRepository;
    private final TableInfoRepository tableInfoRepository;
    private final PhysicalViewRepository viewRepository;

    /**
     * 이 트랜잭션이 끝날 때까지 스키마들의 이름 공간을 잠근다 (쓰기 트랜잭션 안에서만).
     * @param viewGraph 프로젝트의 VIEW 참조 그래프도 잠근다 (= 기본 스키마 행)
     * @param schemaIds 이름을 쓰거나 확인할 스키마 (null 은 건너뛴다). 다른 프로젝트 스키마면 SCHEMA_IS_NOT_DEFINED
     */
    public void lock(Long projectId, boolean viewGraph, Collection<Long> schemaIds) {
        // 기본 스키마가 아직 없으면 먼저 만든다 (만들 때 프로젝트 행을 잠가 직렬화한다)
        SchemaInfo defaultSchema = schemaService.resolveSchema(projectId, null);
        TreeSet<Long> others = new TreeSet<>(schemaIds.stream().filter(Objects::nonNull).toList());
        boolean lockDefault = viewGraph || others.remove(defaultSchema.getId());
        List<Long> order = new ArrayList<>();
        if (lockDefault) order.add(defaultSchema.getId());
        order.addAll(others);
        for (Long id : order) {
            if (schemaInfoRepository.findForUpdate(id, projectId).isEmpty()) throw new CustomException(ErrorCode.SCHEMA_IS_NOT_DEFINED);
        }
    }

    public void lock(Long projectId, boolean viewGraph, Long... schemaIds) {
        lock(projectId, viewGraph, Arrays.asList(schemaIds));
    }

    /** 스키마에서 같은 이름(대문자)을 이미 쓰는 객체 종류. selfTableId·selfViewId 는 이름을 바꾸는 자기 자신 (비교에서 뺀다). 없으면 null */
    public Kind conflict(Long schemaId, String name, Long selfTableId, Long selfViewId) {
        String upper = name.toUpperCase(Locale.ROOT);
        if (tableInfoRepository.findAllBySchema_IdAndTableName(schemaId, upper).stream().anyMatch(t -> !Objects.equals(t.getId(), selfTableId))) return Kind.TABLE;
        return viewRepository.findFirstBySchema_IdAndViewName(schemaId, upper).filter(v -> !Objects.equals(v.getId(), selfViewId)).isPresent() ? Kind.VIEW : null;
    }
}
