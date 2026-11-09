import { api, enc } from '../../lib/api';

/** 컬럼 화면에서 쓰는 표준용어 (GET /api/selectColumn/term/{type}/{keyword} 의 termDtos) */
export interface Term {
  id: number;
  commonStandardTermName: string;
  commonStandardTermAbbreviation: string;
  commonStandardDomainName?: string;
  dataType?: string;
  isApprovalYn: string;
}

export type TermSearchType = 'termName' | 'termAbbreviation';

export async function searchTerms(type: TermSearchType, keyword: string): Promise<Term[]> {
  const response = await api<{ termDtos?: Term[] }>(`/api/selectColumn/term/${enc(type)}/${enc(keyword)}`);
  return response.termDtos || [];
}

/** 용어명은 "_" 로 단어를 잇는다 : 화면에는 공백으로 보여준다 */
export const termLabel = (term: { commonStandardTermName?: string }) => String(term.commonStandardTermName ?? '').replaceAll('_', ' ');
