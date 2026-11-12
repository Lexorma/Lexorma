import Markdown from 'react-markdown';
import remarkBreaks from 'remark-breaks';
import remarkGfm from 'remark-gfm';

/**
 * 공지사항 본문 (Markdown) 렌더링
 * - GFM(표, 체크리스트, 취소선, 자동 링크) 지원, 줄바꿈은 그대로 줄바꿈으로 표시한다.
 * - 본문에 포함된 HTML 태그는 실행하지 않고 문자 그대로 보여 준다.
 */
export function NoticeMarkdown({ content }: { content: string }) {
  return (
    <div className="notice-markdown">
      <Markdown
        remarkPlugins={[remarkGfm, remarkBreaks]}
        components={{
          a: ({ node: _node, ...props }) => <a {...props} target="_blank" rel="noopener noreferrer" />,
        }}
      >
        {content}
      </Markdown>
    </div>
  );
}
