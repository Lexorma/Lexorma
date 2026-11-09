import {registerRowDecorator, setLabel, setMono} from "../../common/grid_cells.js";

/**
 * 코드규칙 그리드(codeRuleGrid) 행 꾸미기
 * - 변환된 메소드 명 · 입출력값은 코드 글꼴, 템플릿 명은 라벨, 그룹명은 보라 라벨
 */
export function registerCodeRuleDecorator(tableId = "codeRuleGrid") {
    registerRowDecorator(tableId, (row, data, cellOf) => {
        if (data.functionGroup) setLabel(cellOf("functionGroup"), data.functionGroup, "done");
        if (data.templateName) setLabel(cellOf("templateName"), data.templateName);
        ["methodName", "input", "output", "exception"].forEach(field => {
            if (data[field]) setMono(cellOf(field), data[field]);
        });
    });
}

