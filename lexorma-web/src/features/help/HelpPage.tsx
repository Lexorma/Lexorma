import { useMemo, useState, type FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ChevronDownIcon } from '@primer/octicons-react';
import { PageHeader } from '../../components/layout/PageHeader';
import { locale, t } from '../../lib/i18n';

interface FaqCategory {
  category: string;
  questions: { question: string; answer: string }[];
}

/**
 * 도움말 (FAQ) : /jsons/faq.json - 분류 탭 + 펼침 목록
 * 답변은 관리자가 작성한 정적 HTML 이다. (사용자 입력이 아님)
 */
export function HelpPage() {
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
      <PageHeader category={t('운영 관리')} title={t('도움말')} description={t('자주 묻는 질문을 분류별로 확인하거나 검색할 수 있습니다.')} />

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
