import { useState, useSyncExternalStore, type RefObject } from 'react';
import type { Rect } from '@xyflow/react';
import { useDialog } from '../../components/dialog/DialogProvider';
import { t } from '../../lib/i18n';
import { MAX_ROWS } from './model';

/**
 * ERD 이미지 내보내기 (DEC-22) : 물리·논리 ERD 공통. 화면에 보이는 영역이 아니라 지금 ERD 에 올라온 노드 전체를, 지금 배치 그대로 그린다 (관계선·레이블 포함).
 * <ul>
 *   <li>ERD 는 보이는 노드만 그리므로(onlyRenderVisibleElements) 내보내는 동안 {@link ErdExport.exporting} 으로 모두 그리게 한 뒤 몇 프레임 기다려 잰다</li>
 *   <li>뷰포트(.react-flow__viewport)를 노드 전체 경계 + 여백 크기로, 확대 1 로 다시 그려 복제한다 (html-to-image). 화면의 확대·이동은 바꾸지 않는다</li>
 *   <li>PNG 는 브라우저 캔버스 한도(한 변·넓이) 안에서 가능한 해상도(최대 2배)로, SVG 는 크기 제한 없이 벡터로 만든다. 배경은 지금 테마의 바탕색</li>
 *   <li>데이터는 이미 권한을 확인한 ERD API 로 받은 것만 쓴다 : 서버에 새 요청을 보내지 않는다</li>
 * </ul>
 * html-to-image 는 버튼을 눌렀을 때만 불러온다 (기본 번들에 넣지 않는다).
 * 크기 한도 (7차 측정 — docs/completed/model-exchange-dec22.md 9장) : 내보내기 무게 = 노드 수 + 그리는 행 수(노드당 컬럼·속성, MAX_ROWS+1 까지).
 * 그동안 화면이 멈추므로 {@link EXPORT_WARN_WEIGHT} 를 넘으면 예상 시간·크기를 알리고 확인을 받고, {@link EXPORT_MAX_WEIGHT} 를 넘으면 만들지 않는다.
 */
export type ErdImageFormat = 'png' | 'svg';

const PADDING = 40;
/**
 * 측정 (헤드리스 Chrome, 노드당 10행 논리 ERD) : 무게 1,100(100 노드) PNG 7.6초·SVG 77MB, 3,300 PNG 23초·SVG 232MB·힙 0.67GB,
 * 5,500(500 노드) PNG 38초(화면 멈춤 32초)·SVG 387MB, 11,000(1,000 노드) 은 실패 (SVG 문자열이 브라우저 문자열 한도를 넘는다)
 */
export const EXPORT_WARN_WEIGHT = 2_000;
export const EXPORT_MAX_WEIGHT = 6_000;
/** 측정에서 얻은 무게당 PNG 시간(초)·SVG 크기(MB) */
const SECONDS_PER_WEIGHT = 0.007;
const SVG_MB_PER_WEIGHT = 0.07;

/** 내보내기 무게 : 노드마다 1 + 그리는 행 수 (자세히 그릴 때 MAX_ROWS 행 + '외 n개' 한 줄) */
export function exportWeight(rowCounts: number[]): number {
  return rowCounts.reduce((sum, rows) => sum + 1 + Math.min(rows, MAX_ROWS + 1), 0);
}
/** 브라우저 캔버스 한도 : Chrome 한 변 32,767px·넓이 약 2.7억 px. 여유를 둔다 */
const MAX_SIDE = 16_000;
const MAX_AREA = 120_000_000;

/** 내보내는 중인 ERD 가 있는지 : 그동안 노드를 축소 모양(컬럼 생략)이 아니라 자세히 그린다 (useSimpleNodes) */
let exportingCount = 0;
const listeners = new Set<() => void>();
function setExportingFlag(on: boolean) {
  exportingCount += on ? 1 : -1;
  listeners.forEach((listener) => listener());
}
export function useErdExporting(): boolean {
  return useSyncExternalStore((listener) => { listeners.add(listener); return () => listeners.delete(listener); }, () => exportingCount > 0);
}

export interface ErdExport {
  /** 내보내는 중 : ERD 가 화면 밖 노드까지 그리도록 onlyRenderVisibleElements 를 끈다 */
  exporting: boolean;
  run: (format: ErdImageFormat) => Promise<void>;
}

/**
 * @param canvas    ReactFlow 를 감싼 요소
 * @param bounds    지금 노드 전체의 경계 (잰 크기 기준, 노드가 없으면 null) : 모두 그린 뒤에 부른다
 * @param fileBase  확장자를 뺀 파일 이름
 * @param rowCounts 지금 노드마다의 컬럼·속성 수 (크기 한도 판단)
 */
export function useErdExport(canvas: RefObject<HTMLElement | null>, bounds: () => Rect | null, fileBase: () => string, rowCounts: () => number[]): ErdExport {
  const [exporting, setExporting] = useState(false);
  const dialog = useDialog();
  const run = async (format: ErdImageFormat) => {
    const rows = rowCounts();
    const weight = exportWeight(rows);
    if (weight > EXPORT_MAX_WEIGHT) {
      dialog.alert(t('ERD 가 너무 커서 이미지로 만들 수 없습니다 (노드 {0}개, 그릴 행 {1}개 — 한도 {2}). 검색·스키마·페이지로 범위를 줄이거나 모델을 나눠 내보내세요.',
        rows.length, weight.toLocaleString(), EXPORT_MAX_WEIGHT.toLocaleString()));
      return;
    }
    if (weight > EXPORT_WARN_WEIGHT) {
      const seconds = Math.round(weight * SECONDS_PER_WEIGHT);
      const message = format === 'svg'
        ? t('큰 ERD 입니다 (노드 {0}개). SVG 를 만드는 동안 약 {1}초 화면이 멈출 수 있고 파일은 약 {2}MB 입니다. 계속하시겠습니까?', rows.length, seconds, Math.round(weight * SVG_MB_PER_WEIGHT))
        : t('큰 ERD 입니다 (노드 {0}개). PNG 를 만드는 동안 약 {1}초 화면이 멈출 수 있습니다. 계속하시겠습니까?', rows.length, seconds);
      dialog.confirm(message, () => void render(format));
      return;
    }
    await render(format);
  };
  const render = async (format: ErdImageFormat) => {
    setExporting(true);
    setExportingFlag(true);
    try {
      // 모든 노드를 자세히 그리고 잴 때까지 (노드 크기는 축소 모양과 같아 경계는 그대로다)
      await frames(6);
      const viewport = canvas.current?.querySelector<HTMLElement>('.react-flow__viewport');
      const box = bounds();
      if (!viewport || !box || box.width <= 0 || box.height <= 0) return;
      const width = Math.ceil(box.width + PADDING * 2);
      const height = Math.ceil(box.height + PADDING * 2);
      const background = getComputedStyle(document.documentElement).getPropertyValue('--gh-canvas').trim() || '#ffffff';
      const options = {
        backgroundColor: background,
        width,
        height,
        skipFonts: true,
        pixelRatio: format === 'png' ? pngRatio(width, height) : 1,
        style: { width: `${width}px`, height: `${height}px`, transform: `translate(${PADDING - box.x}px, ${PADDING - box.y}px) scale(1)` },
      };
      const { toPng, toSvg } = await import('html-to-image');
      const url = format === 'png' ? await toPng(viewport, options) : await toSvg(viewport, options);
      download(url, `${fileBase()}.${format}`);
    } finally {
      setExportingFlag(false);
      setExporting(false);
    }
  };
  return { exporting, run };
}

/** 큰 ERD 가 잘리지 않게 : 한도를 넘으면 해상도를 낮춘다 (1 미만이면 축소). 작은 ERD 는 2배로 선명하게 */
export function pngRatio(width: number, height: number): number {
  return Math.min(2, MAX_SIDE / Math.max(width, height), Math.sqrt(MAX_AREA / (width * height)));
}

/** 파일 이름에 쓸 수 없는 글자를 바꾼다 (한글은 그대로) */
export function erdFileName(...parts: (string | null | undefined)[]): string {
  const stamp = new Date().toISOString().slice(0, 16).replace(/[-:T]/g, '');
  return [...parts.filter(Boolean), stamp].join('_').replace(/[\\/:*?"<>|\s]+/g, '-');
}

function frames(count: number): Promise<void> {
  return new Promise((resolve) => {
    const step = (left: number) => (left <= 0 ? resolve() : requestAnimationFrame(() => step(left - 1)));
    step(count);
  });
}

function download(url: string, name: string) {
  const link = document.createElement('a');
  link.href = url;
  link.download = name;
  document.body.appendChild(link);
  link.click();
  link.remove();
}
