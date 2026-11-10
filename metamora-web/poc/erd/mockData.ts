import type { ErdColumn, ErdModel, ErdRelation, ErdTable, RelationKind } from './model';

/** 시드 고정 난수 (mulberry32) - 같은 규모는 항상 같은 데이터 → 조합 간 동일 조건 측정 */
function mulberry32(seed: number) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const SCHEMAS = ['cmn', 'crm', 'erp', 'hr', 'fin', 'log'];
const TYPES = ['VARCHAR(100)', 'VARCHAR(20)', 'NUMERIC(10)', 'NUMERIC(18,2)', 'TIMESTAMP', 'DATE', 'CHAR(1)', 'TEXT', 'BIGINT'];

/** 관계 없는 테이블 (관계를 만들지도, 참조되지도 않는다) */
export const isIsolated = (index: number) => index % 10 === 7;

/**
 * 테스트용 Mock ERD (실제 서비스 데이터와 무관).
 * - 테이블 10% 관계 없음, 8% 복합 PK(→ 복합 FK), 컬럼 4~14개(10% 는 최대 42개)
 * - 허브 테이블(전체의 1%)이 FK 의 30% 를 받는다 → 관계가 매우 많은 테이블
 * - 30개마다 자기 참조, 100개마다 3개 테이블 순환(A→B→C→A)
 * - 관계 유형: FK 80% / 모델 7% / AI 추정 8% / 승인 5%
 */
export function generateModel(tableCount: number, seed = 20261110): ErdModel {
  const rand = mulberry32(seed + tableCount);
  const int = (min: number, max: number) => min + Math.floor(rand() * (max - min + 1));
  const pick = <T,>(items: T[]) => items[Math.floor(rand() * items.length)];

  const tables: ErdTable[] = [];
  for (let i = 0; i < tableCount; i++) {
    const id = `t${i}`;
    const schema = SCHEMAS[i % SCHEMAS.length];
    const columns: ErdColumn[] = [];
    const composite = i % 13 === 5;
    if (composite) {
      columns.push({ id: `${id}.c0`, name: 'org_id', dataType: 'BIGINT', pk: true, nullable: false });
      columns.push({ id: `${id}.c1`, name: 'seq_no', dataType: 'NUMERIC(10)', pk: true, nullable: false });
    } else {
      columns.push({ id: `${id}.c0`, name: 'id', dataType: 'BIGINT', pk: true, nullable: false });
    }
    const extra = rand() < 0.1 ? int(20, 40) : int(2, 12);
    for (let j = 0; j < extra; j++) {
      columns.push({ id: `${id}.c${columns.length}`, name: `attr_${j}_nm`, dataType: pick(TYPES), pk: false, nullable: rand() < 0.6 });
    }
    tables.push({ id, schema, name: `${schema}_tbl_${String(i).padStart(4, '0')}`, columns });
  }

  const relations: ErdRelation[] = [];
  const kindOf = (): RelationKind => {
    const r = rand();
    return r < 0.8 ? 'FK' : r < 0.87 ? 'MODEL' : r < 0.95 ? 'AI_INFERRED' : 'APPROVED';
  };
  // 대상 테이블의 PK 마다 원본 테이블에 FK 컬럼을 추가 → PK 가 2개면 복합 FK
  const relate = (src: ErdTable, tgt: ErdTable, kind = kindOf()) => {
    const pairs = tgt.columns.filter((c) => c.pk).map((pk) => {
      const col: ErdColumn = {
        id: `${src.id}.c${src.columns.length}`,
        name: src === tgt ? `parent_${pk.name}` : `${tgt.name}_${pk.name}`,
        dataType: pk.dataType,
        pk: false,
        nullable: src === tgt || rand() < 0.5,
      };
      src.columns.push(col);
      return { sourceColumnId: col.id, targetColumnId: pk.id };
    });
    relations.push({ id: `r${relations.length}`, name: `fk_${src.name}_${relations.length}`, kind, sourceTableId: src.id, targetTableId: tgt.id, columns: pairs });
  };

  const hubCount = Math.max(2, Math.ceil(tableCount * 0.01));
  for (let i = 0; i < tableCount; i++) {
    if (isIsolated(i)) continue;
    const fkCount = int(0, 3);
    for (let k = 0; k < fkCount; k++) {
      let t: number;
      if (rand() < 0.3) t = int(0, hubCount - 1);
      else t = Math.min(tableCount - 1, Math.max(0, i + int(-40, 40)));
      if (t === i || isIsolated(t)) continue;
      relate(tables[i], tables[t]);
    }
    if (i % 30 === 11) relate(tables[i], tables[i], 'FK'); // 자기 참조 (계층 구조)
    if (i % 100 === 20 && i + 2 < tableCount) {
      // 순환 관계
      relate(tables[i], tables[i + 1], 'FK');
      relate(tables[i + 1], tables[i + 2], 'FK');
      relate(tables[i + 2], tables[i], 'FK');
    }
  }
  return { tables, relations };
}

/** 측정 환경 기록용 통계 */
export function modelStats(model: ErdModel) {
  const columns = model.tables.reduce((sum, t) => sum + t.columns.length, 0);
  const pairs = model.relations.reduce((sum, r) => sum + r.columns.length, 0);
  const inbound = new Map<string, number>();
  model.relations.forEach((r) => inbound.set(r.targetTableId, (inbound.get(r.targetTableId) ?? 0) + 1));
  const linked = new Set(model.relations.flatMap((r) => [r.sourceTableId, r.targetTableId]));
  return {
    tables: model.tables.length,
    columns,
    avgColumns: +(columns / model.tables.length).toFixed(1),
    relations: model.relations.length,
    columnEdges: pairs,
    composite: model.relations.filter((r) => r.columns.length > 1).length,
    selfRef: model.relations.filter((r) => r.sourceTableId === r.targetTableId).length,
    isolated: model.tables.length - linked.size,
    maxInbound: Math.max(0, ...inbound.values()),
  };
}
