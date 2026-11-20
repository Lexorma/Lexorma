package com.lexorma.data.erd.service;

import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.erd.domain.dto.ErdDto;

/** 물리 ERD : 조회 전용 (물리 모델을 바꾸지 않는다) + 내 레이아웃 저장 */
public interface ErdService {

    /** 조회 한 번에 담는 최대 테이블 수 (목록 size, 이웃 탐색 결과) */
    int MAX_TABLES = 300;

    /** 이웃 탐색 최대 단계 */
    int MAX_DEPTH = 3;

    /** 레이아웃에 저장할 수 있는 최대 노드 수 */
    int MAX_LAYOUT_NODES = 5000;

    /** 테이블 찾기 최대 건수 (M-2 개선 2차 D-02) */
    int MAX_FINDER = 50;

    /** 테이블별 관계 목록 최대 페이지 크기 (목록 조회 정책과 같은 100) */
    int MAX_RELATION_PAGE = 100;

    /** 조건에 맞는 테이블 목록 페이지 (스키마 → 테이블명 순) */
    ErdDto.Graph selectGraph(AccountDto accountDto, ErdDto.Search search, int page, int size, ErdDto.RelationScope relationScope);

    /** 선택 테이블에서 관계로 depth 단계까지 이어진 테이블 (최대 MAX_TABLES). 탐색은 relationScope 와 무관하게 모든 관계를 따라간다 */
    ErdDto.Graph selectNeighbors(AccountDto accountDto, Long tableId, int depth, ErdDto.RelationScope relationScope);

    /** 예전 호출 (relationScope ALL : 범위 테이블이 한쪽인 관계 전부) */
    default ErdDto.Graph selectGraph(AccountDto accountDto, ErdDto.Search search, int page, int size) {
        return selectGraph(accountDto, search, page, size, ErdDto.RelationScope.ALL);
    }

    default ErdDto.Graph selectNeighbors(AccountDto accountDto, Long tableId, int depth) {
        return selectNeighbors(accountDto, tableId, depth, ErdDto.RelationScope.ALL);
    }

    /** 테이블 찾기 : 범위 조회와 같은 검색 조건(물리명·논리명·스키마명, 스키마), 이름순 최대 size(1~MAX_FINDER)개 + 전체 수 */
    ErdDto.TableHits findTables(AccountDto accountDto, ErdDto.Search search, int size);

    /** 테이블 하나의 관계 목록 (관계 ID 순 페이지, 컬럼 쌍 전부 + 카디널리티) */
    ErdDto.RelationPage selectTableRelations(AccountDto accountDto, Long tableId, int page, int size);

    /** 내 레이아웃. 없으면 version·viewport null, 삭제된 테이블의 좌표는 뺀다 */
    ErdDto.Layout selectLayout(AccountDto accountDto);

    /** 내 레이아웃 저장 (기존 좌표에 합침). version 이 다르면 ERD_LAYOUT_CONFLICT */
    ErdDto.Layout saveLayout(AccountDto accountDto, ErdDto.Layout layout);
}
