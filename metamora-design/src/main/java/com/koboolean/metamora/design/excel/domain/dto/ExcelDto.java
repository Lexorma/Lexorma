package com.koboolean.metamora.design.excel.domain.dto;

import com.koboolean.metamora.design.code.domain.dto.CodeRuleDetailDto;
import lombok.*;

import java.util.List;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ExcelDto {

    private String formatType;
    private String formatText;

    private String type;

    private String column;
    private String searchValue;

    private List<CodeRuleDetailDto> rules;
}
