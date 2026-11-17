import { useMemo, useState, type FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { ChevronDownIcon } from '@primer/octicons-react';
import { PageHeader } from '../../components/layout/PageHeader';
import { useAuth, type MenuId } from '../../hooks/useAuth';
import { locale, t } from '../../lib/i18n';

/** 화면 가이드 (DEC-30) : /jsons/help/guide.{ko,en}.json — 정적 파일, 사용자 입력이 아니다. 글은 텍스트로만 그린다 (HTML 아님) */
interface Guide {
  menuId: MenuId;
  path: string;
  title: string;
  sections: { heading: string; items: string[] }[];
  /** 예제 프로젝트(LEXORMA) 화면 이미지 (/images/help/…, 실제 업무 데이터·인증 정보 없음). 없으면 글만 */
  image?: string;
}

/** 메뉴 권한에 맞는 화면 가이드만 보인다 (볼 수 없는 화면의 가이드는 숨긴다). ?topic=메뉴ID 로 하나를 펼친다 */
function ScreenGuides() {
  const { canView } = useAuth();
  const [searchParams] = useSearchParams();
  const topic = searchParams.get('topic');
  const [keyword, setKeyword] = useState('');
  const { data = [], isError } = useQuery({
    queryKey: ['help-guide', locale],
    queryFn: async () => {
      const res = await fetch(locale === 'en' ? '/jsons/help/guide.en.json' : '/jsons/help/guide.ko.json');
      if (!res.ok) throw new Error('guide');
      const json = await res.json();
      return (Array.isArray(json) ? json : []) as Guide[];
    },
  });
  const word = keyword.toLowerCase().trim();
  const guides = data.filter((g) => canView(g.menuId))
    .filter((g) => !word || g.title.toLowerCase().includes(word) || g.sections.some((s) => s.heading.toLowerCase().includes(word) || s.items.some((i) => i.toLowerCase().includes(word))));
  return (
    <div className="faq-content">
      <div className="faq-search" role="search">
        <label htmlFor="guide-search" className="visually-hidden">{t('가이드 검색')}</label>
        <input id="guide-search" type="search" className="form-control" placeholder={t('화면 이름 또는 내용 검색')} autoComplete="off" value={keyword} onChange={(e) => setKeyword(e.target.value)} />
      </div>
      {isError ? <div className="faq-blank"><p>{t('도움말을 불러오지 못했습니다.')}</p></div>
        : guides.length === 0 ? <div className="faq-blank"><p>{t('볼 수 있는 화면 가이드가 없습니다.')}</p></div>
        : (
          <div className="faq-list">
            {guides.map((g) => (
              <details className="faq-item" key={g.menuId} open={topic === g.menuId || undefined}>
                <summary>
                  <span className="faq-question">{g.title}</span>
                  <ChevronDownIcon className="faq-chevron" />
                </summary>
                <div className="faq-answer">
                  {g.image && <img src={g.image} alt={t('{0} 화면 (예제 프로젝트)', g.title)} loading="lazy" style={{ maxWidth: '100%', border: '1px solid var(--gh-border)', borderRadius: 6 }} />}
                  {g.sections.map((section) => (
                    <section key={section.heading}>
                      <h4>{section.heading}</h4>
                      <ul>{section.items.map((item) => <li key={item}>{item}</li>)}</ul>
                    </section>
                  ))}
                  <p><Link to={g.path}>{t('이 화면으로 이동')}</Link></p>
                </div>
              </details>
            ))}
          </div>
        )}
    </div>
  );
}

interface FaqCategory {
  category: string;
  questions: { question: string; answer: string }[];
}

/**
 * 도움말 : 화면 가이드(DEC-30, 메뉴 권한별) + 자주 묻는 질문(/jsons/faq.json - 분류 탭 + 펼침 목록).
 * FAQ 답변은 관리자가 작성한 정적 HTML 이다. (사용자 입력이 아님)
 */
export function HelpPage() {
  const [view, setView] = useState<'guide' | 'faq'>('guide');
  return (
    <>
      <PageHeader category={t('운영 관리')} title={t('도움말')} description={t('화면별 가이드와 자주 묻는 질문을 확인할 수 있습니다. 볼 수 있는 화면의 가이드만 보입니다.')} />
      <ul className="nav nav-tabs" role="tablist">
        <li className="nav-item" role="presentation">
          <a className={`nav-link${view === 'guide' ? ' active' : ''}`} role="tab" href="#" aria-selected={view === 'guide'} onClick={(e) => { e.preventDefault(); setView('guide'); }}>{t('화면 가이드')}</a>
        </li>
        <li className="nav-item" role="presentation">
          <a className={`nav-link${view === 'faq' ? ' active' : ''}`} role="tab" href="#" aria-selected={view === 'faq'} onClick={(e) => { e.preventDefault(); setView('faq'); }}>{t('자주 묻는 질문')}</a>
        </li>
      </ul>
      {view === 'guide' ? <ScreenGuides /> : <Faq />}
    </>
  );
}

function Faq() {
  const { data = [], isError } = useQuery({
    queryKey: ['faq'],
    queryFn: async () => {
      // 영어는 faq.en.json, 한국어는 faq.json
      const res = await fetch(locale === 'en' ? '/jsons/faq.en.json' : '/jsons/faq.json');
      if (!res.ok) throw new Error('faq');
      const json = await res.json();
      return (Array.isArray(json) ? json : []) as FaqCategory[];
    },
  });
  const [input, setInput] = useState('');
  const [keyword, setKeyword] = useState('');
  const [active, setActive] = useState(0);

  const filtered = useMemo(() => {
    if (!keyword) return data;
    return data
      .map((item) => ({
        ...item,
        questions: item.questions.filter((q) => q.question.toLowerCase().includes(keyword) || q.answer.toLowerCase().includes(keyword)),
      }))
      .filter((item) => item.questions.length > 0);
  }, [data, keyword]);

  const onSearch = (e: FormEvent) => {
    e.preventDefault();
    setKeyword(input.toLowerCase().trim());
    setActive(0);
  };

  const current = filtered[active] ?? filtered[0];

  return (
    <>
      <form id="faq-form" className="faq-search" role="search" onSubmit={onSearch}>
        <label htmlFor="faq-search" className="visually-hidden">{t('질문 검색')}</label>
        <input id="faq-search" type="search" className="form-control" placeholder={t('질문 또는 답변 검색')} autoComplete="off" value={input} onChange={(e) => setInput(e.target.value)} />
        <button type="submit" className="btn" id="faq-search-btn">{t('검색')}</button>
      </form>

      <ul className="nav nav-tabs" id="faqTabMenu" role="tablist">
        {filtered.map((item, index) => (
          <li className="nav-item" role="presentation" key={index}>
            <a className={`nav-link${index === active ? ' active' : ''}`} role="tab" href="#" aria-selected={index === active}
              onClick={(e) => { e.preventDefault(); setActive(index); }}>
              <span>{item.category}</span>
              <span className="faq-counter">{item.questions.length}</span>
            </a>
          </li>
        ))}
      </ul>

      <div id="faqTabContent" className="faq-content">
        {isError || !current ? (
          <div className="faq-blank">
            <p>{isError ? t('도움말을 불러오지 못했습니다.') : keyword ? t('"{0}"에 대한 검색 결과가 없습니다.', keyword) : t('등록된 도움말이 없습니다.')}</p>
          </div>
        ) : (
          <div className="faq-list">
            {current.questions.map((q, i) => (
              <details className="faq-item" key={i}>
                <summary>
                  <span className="faq-q">Q</span>
                  <span className="faq-question">{q.question}</span>
                  <ChevronDownIcon className="faq-chevron" />
                </summary>
                <div className="faq-answer" dangerouslySetInnerHTML={{ __html: q.answer }} />
              </details>
            ))}
          </div>
        )}
      </div>
    </>
  );
}
