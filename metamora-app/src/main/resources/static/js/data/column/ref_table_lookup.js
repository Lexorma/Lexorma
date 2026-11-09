/**
 * 참조 테이블명 입력칸 : 입력하는 동안 승인된 테이블을 조회해 자동완성(datalist) 목록으로 보여준다.
 * (컬럼 등록 팝업, 컬럼 일괄 편집에서 사용)
 *
 * - 목록은 제안일 뿐이라 직접 입력도 그대로 저장된다. (서버가 대문자로 맞춘다)
 * - 입력값이 승인된 테이블과 같으면 "선택됨", 없으면 DDL 에서 FK 가 만들어지지 않는다고 안내한다.
 * - 테이블 조회 API 는 컬럼 등록과 같은 GET /api/selectColumn/table/{keyword} (승인된 테이블, 이름 부분 일치)
 */
let listSeq = 0;

/**
 * @param $input 참조 테이블명 input (jQuery)
 * @returns 안내 문구 요소 (jQuery) : 호출한 쪽에서 input 아래에 붙인다.
 */
export function bindRefTableLookup($input) {
    const listId = `refTableOptions-${++listSeq}`;
    const $list = $("<datalist></datalist>").attr("id", listId);
    const $status = $("<div></div>").addClass("table-name-words ref-table-status").attr("aria-live", "polite");
    $input.attr({list: listId, autocomplete: "off"}).after($list);

    let timer = null;
    let seq = 0;
    let tables = [];

    $input.on("input", () => {
        clearTimeout(timer);
        const keyword = $input.val().trim();
        if (!keyword) {
            seq++;
            tables = [];
            $list.empty();
            $status.empty();
            return;
        }
        timer = setTimeout(() => lookup(keyword), 300);
    });

    // 기존 값이 있으면(수정/일괄 편집) 처음부터 상태를 보여준다
    if ($input.val().trim()) lookup($input.val().trim());

    function lookup(keyword) {
        const mySeq = ++seq;
        $.ajax({
            url: `/api/selectColumn/table/${encodeURIComponent(keyword)}`,
            type: "GET",
            success: (response) => {
                if (mySeq !== seq) return; // 늦게 도착한 이전 응답 무시
                tables = response.tableInfos || [];
                $list.empty();
                tables.forEach(t => $list.append($("<option></option>").val(t.tableName).text(t.tableKorName || "")));
                renderStatus();
            }
        });
    }

    function renderStatus() {
        $status.empty();
        const name = $input.val().trim().toUpperCase();
        if (!name) return;

        const matched = tables.find(t => t.tableName === name);
        if (matched) {
            $status.append($("<span></span>").addClass("gh-label gh-label-success")
                .append($("<span></span>").addClass("table-name-abbr").text(matched.tableName))
                .append($("<span></span>").text(matched.tableKorName ? `${matched.tableKorName} · 선택됨` : "선택됨")));
        } else {
            const text = tables.length
                ? `후보 ${tables.length}건 · 목록에서 선택하세요.`
                : "승인된 테이블에 없습니다 · DDL 에서 FK 를 만들지 않습니다.";
            $status.append($("<p></p>").addClass("form-help").text(text));
        }
    }

    return $status;
}
