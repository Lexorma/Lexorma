package com.lexorma.data.dictionary.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.data.physical.sync.ModelRowLocks;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * 표준 사전 삭제의 잠금 (M8-C4c-1 C4c-D02, docs/ongoing/modeling-concurrency-m8.md 20장).
 * <ol>
 *   <li>프로젝트 행 배타 (맨 먼저) : 같은 사전 항목을 새로 참조하는 쓰기 — 표준 용어 등록·키를 바꾸는 용어 수정(도메인·단어를 참조), 도메인 등록 — 가 같은 행을 먼저 잡는다 (DICT-2·M-5 5차).
 *       삭제가 참조 검사를 한 뒤 지울 때까지 새 참조가 끼어들지 않는다</li>
 *   <li>지울 행 FOR UPDATE NOWAIT (ID 순) : 프로젝트를 쥔 뒤에는 기다리지 않는다 (ModelRowLocks 규칙). 모델 쓰기(속성·컬럼 저장)가 그 용어를 참조하는 FK 검사(KEY SHARE)를
 *       쥐고 있거나 다른 트랜잭션이 그 행을 수정·승인 중이면 409 — 모델 쓰기는 행을 쥔 채 커밋 때 프로젝트 공유 잠금을 기다리므로 기다리면 교착한다</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class DictionaryRowLocks {

    private final ProjectRepository projectRepository;
    private final EntityManager entityManager;

    /** table·key 는 이 클래스 안의 고정 값만 (아래 상수) */
    public void lockForDeletion(Long projectId, Target target, Collection<Long> ids) {
        projectRepository.findByIdForUpdate(projectId).orElseThrow(() -> new CustomException(ErrorCode.PROJECT_NOT_FOUND));
        TreeSet<Long> sorted = new TreeSet<>(ids.stream().filter(Objects::nonNull).toList());
        if (sorted.isEmpty()) return;
        List<Long> list = new ArrayList<>(sorted);
        ModelRowLocks.noWait(() -> {
            for (int from = 0; from < list.size(); from += 1000) {
                entityManager.createNativeQuery("select " + target.key + " from " + target.table + " where " + target.key + " in (:ids) and PRJ_SN = :projectId order by "
                                + target.key + " for update nowait")
                        .setParameter("ids", list.subList(from, Math.min(from + 1000, list.size()))).setParameter("projectId", projectId).getResultList();
            }
            return null;
        });
    }

    public enum Target {
        DOMAIN("TB_STD_DMN", "STD_DMN_SN"), WORD("TB_STD_WORD", "STD_WORD_SN"), TERM("TB_STD_TRM", "STD_TRM_SN");

        final String table;
        final String key;

        Target(String table, String key) {
            this.table = table;
            this.key = key;
        }
    }
}
