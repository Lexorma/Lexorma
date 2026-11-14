package com.lexorma.data.sourcemapping.compare;

import com.lexorma.data.sourcemapping.domain.dto.SourceCompareDto.AttributeDiff;
import com.lexorma.data.sourcemapping.domain.dto.SourceCompareDto.AttributeResult;
import com.lexorma.data.sourcemapping.domain.dto.SourceCompareDto.Note;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class SourceCommentCompareTest {

    private static Map<String, AttributeDiff> attributes(String sourceComment, String description, String sourceCommentField) {
        return SourceModelComparator.commentAttributes(sourceComment, "description", description, Note.DESCRIPTION, sourceCommentField).stream()
                .collect(Collectors.toMap(AttributeDiff::attribute, a -> a));
    }

    @Test
    @DisplayName("COMMENT ↔ 모델 설명 : 공백과 '_' 를 같게 보고(앞뒤·연속 공백 무시) 상태를 정한다. 원본 COMMENT 칸과의 차이는 NOT_COMPARED (가져오기 대상, 상태에 넣지 않음)")
    void descriptionComparison() {
        Map<String, AttributeDiff> same = attributes("수정자 아이디", "수정자_아이디", null);
        assertThat(same.get("description")).extracting(AttributeDiff::result, AttributeDiff::note).containsExactly(AttributeResult.SAME, Note.DESCRIPTION);
        // 표시 값은 원문 그대로 (정규화는 비교에만)
        assertThat(same.get("description")).extracting(AttributeDiff::source, AttributeDiff::model).containsExactly("수정자 아이디", "수정자_아이디");
        assertThat(same.get("comment")).extracting(AttributeDiff::result, AttributeDiff::model, AttributeDiff::note)
                .containsExactly(AttributeResult.NOT_COMPARED, null, Note.SOURCE_COMMENT);
        assertThat(SourceModelComparator.status(List.copyOf(same.values()))).isEqualTo(com.lexorma.data.sourcemapping.domain.dto.SourceCompareDto.CompareStatus.SAME);

        assertThat(attributes(" 수정자  아이디 ", "수정자__아이디", null).get("description").result()).isEqualTo(AttributeResult.SAME);
        assertThat(attributes("수정자 아이디", "수정자 ID", null).get("description").result()).isEqualTo(AttributeResult.DIFFERENT);
        assertThat(attributes(null, "설명만 있음", null).get("description").result()).isEqualTo(AttributeResult.DIFFERENT);
        assertThat(attributes("원본만 있음", null, null).get("description").result()).isEqualTo(AttributeResult.DIFFERENT);
        assertThat(attributes("원본", "원본", "원본").get("comment").result()).isEqualTo(AttributeResult.SAME);
        // 원본 COMMENT 칸은 원문 그대로 비교한다 (가져온 값이 원본과 같은지)
        assertThat(attributes("수정자 아이디", "수정자 아이디", "수정자_아이디").get("comment").result()).isEqualTo(AttributeResult.NOT_COMPARED);
        // 원본에도 모델에도 COMMENT 가 없으면 속성을 만들지 않는다
        assertThat(attributes(null, null, null)).isEmpty();
        assertThat(attributes(null, "   ", null).get("description").result()).isEqualTo(AttributeResult.SAME);
        assertThat(SourceModelComparator.normalizeComment(" a_b\tc ")).isEqualTo("a b c");
        // TABLE 은 한글명과 비교한다 (속성 korName)
        assertThat(SourceModelComparator.commentAttributes("계정", "korName", "계정", Note.KOR_NAME, null).getFirst())
                .extracting(AttributeDiff::attribute, AttributeDiff::result, AttributeDiff::note).containsExactly("korName", AttributeResult.SAME, Note.KOR_NAME);
    }
}
