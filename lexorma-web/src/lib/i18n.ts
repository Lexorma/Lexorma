import { createElement, Fragment, type ReactNode } from 'react';
import { en } from '../locales/en';

/**
 * 국제화 (한국어 / 영어)
 * - 언어는 브라우저(시스템) 언어 설정을 따른다. 한국어가 아니면 영어로 표시한다.
 * - 코드에는 한국어 원문을 그대로 쓰고, 영어는 locales/en.ts 에서 원문을 키로 찾는다.
 *   (원문이 en.ts 에 없으면 타입 오류가 난다)
 * - 언어는 화면을 여는 시점에 한 번 정해지므로 t() 는 모듈 최상위에서도 쓸 수 있다.
 */
export type Locale = 'ko' | 'en';
export type MessageKey = keyof typeof en;

function detectLocale(): Locale {
  const language = typeof navigator === 'undefined' ? 'ko' : navigator.language || navigator.languages?.[0] || 'ko';
  return language.toLowerCase().startsWith('ko') ? 'ko' : 'en';
}

export const locale: Locale = detectLocale();

if (typeof document !== 'undefined') document.documentElement.lang = locale;

const dictionary: Record<string, string> | null = locale === 'en' ? en : null;

const PLACEHOLDER = /\{(\d+)\}/g;

/** 문구 번역. {0}, {1} 자리에 args 를 순서대로 넣는다. */
export function t(key: MessageKey, ...args: unknown[]): string {
  const text = dictionary?.[key] ?? key;
  if (!args.length) return text;
  return text.replace(PLACEHOLDER, (match, index) => (Number(index) < args.length ? String(args[Number(index)] ?? '') : match));
}

/** 같은 한국어를 문맥에 따라 다르게 번역할 때 : tc('버튼', '승인') → en.ts 의 '버튼|승인' */
export function tc<C extends string, T extends string>(context: C, text: `${C}|${T}` extends MessageKey ? T : never, ...args: unknown[]): string {
  return t((dictionary ? `${context}|${text}` : text) as MessageKey, ...args);
}

/** 서버가 내려준 값(확인/미확인, 버튼 이름 등) 번역. 사전에 없으면 값을 그대로 보여 준다. */
export function tv(value: string | null | undefined): string {
  if (value === null || value === undefined) return '';
  return dictionary?.[value] ?? value;
}

/** 문구 안에 요소를 넣을 때 : tx('{0} 님, 안녕하세요', <span>{name}</span>) */
export function tx(key: MessageKey, ...nodes: ReactNode[]): ReactNode {
  const text = dictionary?.[key] ?? key;
  return text.split(/(\{\d+\})/).map((part, i) => {
    const matched = /^\{(\d+)\}$/.exec(part);
    return createElement(Fragment, { key: i }, matched ? nodes[Number(matched[1])] : part);
  });
}
