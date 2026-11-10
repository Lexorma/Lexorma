package com.koboolean.metamora.data.erd.service;

import com.koboolean.metamora.core.security.domain.dto.AccountDto;
import com.koboolean.metamora.data.erd.domain.dto.ErdDto;

/** 물리 ERD : 조회 전용 (물리 모델을 바꾸지 않는다) + 내 레이아웃 저장 */
public interface ErdService {

    /** 조회 한 번에 담는 최대 테이블 수 (목록 size, 이웃 탐색 결과) */
    int MAX_TABLES = 300;

    /** 이웃 탐색 최대 단계 */
    int MAX_DEPTH = 3;

    /** 레이아웃에 저장할 수 있는 최대 노드 수 */
    int MAX_LAYOUT_NODES = 5000;

    /** 조건에 맞는 테이블 목록 페이지 (스키마 → 테이블명 순) */
    ErdDto.Graph selectGraph(AccountDto accountDto, ErdDto.Search search, int page, int size);

    /** 선택 테이블에서 관계로 depth 단계까지 이어진 테이블 (최대 MAX_TABLES) */
    ErdDto.Graph selectNeighbors(AccountDto accountDto, Long tableId, int depth);

    /** 내 레이아웃. 없으면 version·viewport null, 삭제된 테이블의 좌표는 뺀다 */
    ErdDto.Layout selectLayout(AccountDto accountDto);

    /** 내 레이아웃 저장 (기존 좌표에 합침). version 이 다르면 ERD_LAYOUT_CONFLICT */
    ErdDto.Layout saveLayout(AccountDto accountDto, ErdDto.Layout layout);
}
