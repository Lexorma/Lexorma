package com.lexorma.data.dictionary.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.common.i18n.Messages;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.dictionary.domain.dto.ColumnSuggestionDto.Candidate;
import com.lexorma.data.dictionary.domain.dto.ColumnSuggestionDto.Suggestions;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.ColumnRow;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.Issue;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.IssueType;
import com.lexorma.data.dictionary.quality.ColumnNameSuggestions;
import com.lexorma.data.dictionary.quality.StandardNames;
import com.lexorma.data.dictionary.repository.DictionaryUsageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 비표준 컬럼명 개선 후보 (M-5 잔여, 조회 전용 추천). 판정은 {@link ColumnNameSuggestions}.
 * 컬럼 1개를 열 때만 계산한다 (목록 전체에 미리 계산하지 않는다). 컬럼명·용어 연결·용어 등록을 적용하는 API 는 없다 — 사용자가 기존 화면에서 고친다.
 * 조회 : 컬럼 1번 → (대상일 때만) 용어·이음동의어·단어·용어-단어·같은 논리명 컬럼·같은 테이블 컬럼 6번 + 프로젝트 1번.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ColumnNameSuggestionService {

    private final DictionaryUsageRepository usageRepository;
    private final ProjectRepository projectRepository;

    public Suggestions suggest(AccountDto accountDto, Long columnId) {
        Long projectId = accountDto.getProjectId();
        ColumnRow column = columnId == null ? null : usageRepository.findQualityColumn(projectId, columnId);
        if (column == null) {
            throw new CustomException(ErrorCode.COLUMN_IS_NOT_DEFINED, "현재 프로젝트에 그 컬럼이 없습니다.");
        }
        DbType db = projectRepository.findById(projectId).map(Project::getDbType).map(DbType::orDefault).orElse(DbType.DEFAULT);
        Set<IssueType> issues = EnumSet.noneOf(IssueType.class);
        StandardizationQualityService.diagnose(db, List.of(column)).stream().map(Issue::type).forEach(issues::add);

        if (!ColumnNameSuggestions.applies(issues)) {
            return suggestions(column, issues, List.of(), false,
                    Messages.get("표준 용어가 연결되어 있고 컬럼명이 영문약어와 같아 개선 후보를 계산하지 않습니다."));
        }

        String desc = StandardNames.termNameKey(column.columnDesc());
        Map<Long, Long> sameDesc = desc.isEmpty() ? Map.of() : usageRepository.countSameDescColumnsByTerm(projectId, desc, column.columnId());
        ColumnNameSuggestions.Result result = ColumnNameSuggestions.suggest(new ColumnNameSuggestions.Input(db, column, issues,
                usageRepository.findSuggestionTerms(projectId), usageRepository.findTermSynonyms(projectId), usageRepository.findQualityWords(projectId),
                usageRepository.findQualityTermWords(projectId), sameDesc, usageRepository.findTableColumns(projectId, column.tableId())));
        List<Candidate> candidates = result.candidates().stream().map(candidate -> ColumnNameSuggestions.withReason(candidate, reason(candidate))).toList();
        return suggestions(column, issues, candidates, result.truncated(),
                candidates.isEmpty() ? Messages.get("표준 사전에서 이 컬럼에 맞는 후보를 찾지 못했습니다. 표준 용어를 직접 찾거나 새 용어 등록을 검토하세요.") : null);
    }

    private static Suggestions suggestions(ColumnRow column, Set<IssueType> issues, List<Candidate> candidates, boolean truncated, String reason) {
        return new Suggestions(column.columnId(), column.columnName(), column.columnDesc(), column.dataType(), column.tableId(), column.tableName(),
                column.schemaId(), column.termId(), column.termName(), column.termAbbreviation(), List.copyOf(issues), candidates, truncated, reason);
    }

    private static String reason(Candidate candidate) {
        return switch (candidate.source()) {
            case LINKED_TERM -> Messages.get("연결된 표준 용어의 영문약어로 컬럼명을 바꾸는 후보입니다.");
            case ABBR_EXACT -> Messages.get("컬럼명이 이 표준 용어의 영문약어와 같습니다. 용어만 연결하면 됩니다.");
            case NAME_EXACT -> Messages.get("컬럼 논리명이 이 표준 용어의 용어명과 같습니다.");
            case SYNONYM -> Messages.get("컬럼 논리명이 이 표준 용어의 이음동의어와 같습니다.");
            case WORD_COMPOSE -> Messages.get("컬럼명의 약어가 이 표준 용어의 단어 구성과 같습니다.");
            case SAME_DESC_COLUMN -> Messages.get("논리명이 같은 다른 컬럼 {0}개가 이 표준 용어를 씁니다.", String.valueOf(candidate.sameDescColumns()));
            case UNREGISTERED -> Messages.get("컬럼명의 약어가 모두 표준 단어이지만 그 단어 구성의 표준 용어가 없습니다. 새 용어 등록을 검토하세요.");
        };
    }
}
