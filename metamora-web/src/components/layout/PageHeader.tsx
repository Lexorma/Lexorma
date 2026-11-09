import type { ReactNode } from 'react';

/** 화면 타이틀 영역 (GitHub Subhead 스타일) : 상위 메뉴 / 타이틀 + 설명 */
export function PageHeader({ category, title, description }: { category: string; title: string; description?: ReactNode }) {
  return (
    <>
      <div className="page-header">
        <nav className="page-breadcrumb" aria-label="메뉴 경로">
          <span>{category}</span>
        </nav>
        <h2 className="page-title">{title}</h2>
      </div>
      {description && <p className="page-desc">{description}</p>}
    </>
  );
}
