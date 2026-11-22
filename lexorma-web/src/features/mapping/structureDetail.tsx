import { Mono, Muted } from '../../components/grid/cells';
import { useAuth } from '../../hooks/useAuth';
import { enc } from '../../lib/api';
import { t } from '../../lib/i18n';
import { TableLinks } from '../dictionary/DictionaryUsage';
import type { QualityRow, QualityStructure, StructureColumn } from './model';

/**
 * 구조 진단 상세 (M-7 구조 정합성 — 논리 관계 ↔ FK, 보조 식별자 ↔ UNIQUE, 물리 전용 FK·UNIQUE). 복합 키는 자리마다 1줄로 순서대로 보여 준다 (첫 컬럼만 보이지 않는다).
 * 서버가 준 판정만 보여 주며 아무것도 고치지 않는다.
 */

const CODE_LABELS: Record<string, string> = {
  FK_MATCH: t('구성·순서 일치'),
  FK_MISSING: t('대응 FK 없음'),
  FK_ORDER: t('FK 컬럼 순서 불일치'),
  FK_TARGET: t('참조 대상 테이블 불일치'),
  FK_COLUMNS: t('FK 컬럼 구성 불일치'),
  FK_AMBIGUOUS: t('후보 FK 여러 개'),
  FK_SHARED: t('같은 FK 를 다른 관계가 사용'),
  FK_NOT_EXPECTED: t('FK 생성 대상 아님'),
  INHERITANCE_FK_OVERLAP: t('상속 FK 와 같은 구성'),
  PAIRS_MISSING: t('관계 속성 짝 없음'),
  PAIRS_INCOMPLETE: t('관계 속성 짝 일부만'),
  PAIRS_INVALID: t('관계 속성 짝이 식별자와 맞지 않음'),
  PARENT_KEY_MISSING: t('부모 주 식별자 없음'),
  ENTITY_UNMAPPED: t('엔터티 매핑 없음'),
  ATTRIBUTE_UNMAPPED: t('속성 매핑 일부 없음'),
  UNIQUE_MATCH: t('구성·순서 일치'),
  UNIQUE_MISSING: t('대응 UNIQUE 없음'),
  UNIQUE_ORDER: t('UNIQUE 컬럼 순서 불일치'),
  UNIQUE_COLUMNS_MISSING: t('UNIQUE 에 컬럼 누락'),
  UNIQUE_COLUMNS_EXTRA: t('UNIQUE 에 컬럼 추가'),
  UNIQUE_COLUMNS_DIFFER: t('UNIQUE 컬럼 구성 불일치'),
  UNIQUE_AMBIGUOUS: t('후보 UNIQUE 여러 개'),
  ALTERNATE_KEY_IS_PRIMARY_KEY: t('보조 식별자 = PK'),
  PHYSICAL_ONLY_FK: t('대응 논리 관계 없음'),
  FK_PARENT_UNMAPPED: t('참조받는 테이블 매핑 없음'),
  PHYSICAL_ONLY_UNIQUE: t('대응 보조 식별자 없음'),
  UNIQUE_COLUMN_UNMAPPED: t('UNIQUE 컬럼 매핑 일부 없음'),
};

export const structureCodeLabel = (code: string) => CODE_LABELS[code] ?? code;

const name = (value: string | null) => (value ? <Mono>{value}</Mono> : <Muted>-</Muted>);

/** 자리 비교 : 기대(매핑) 컬럼과 물리 컬럼이 다르면 표시 */
const differs = (expected: number | null, physical: number | null) => expected != null && physical != null && expected !== physical;

function ForeignKeyColumns({ columns }: { columns: StructureColumn[] }) {
  return (
    <table className="term-relink-table structure-table">
      <thead>
        <tr>
          <th scope="col" style={{ width: '7%' }}>#</th>
          <th scope="col">{t('논리 짝 (부모 → 자식 속성)')}</th>
          <th scope="col">{t('매핑 기준 (자식 → 부모 컬럼)')}</th>
          <th scope="col">{t('물리 FK (자식 → 부모 컬럼)')}</th>
        </tr>
      </thead>
      <tbody>
        {columns.map((c) => {
          const bad = differs(c.expectedColumnId, c.physicalColumnId) || differs(c.expectedParentColumnId, c.physicalParentColumnId)
            || (c.expectedColumnId != null && c.physicalColumnId == null) || (c.expectedColumnId == null && c.physicalColumnId != null && c.logicalAttributeName != null);
          return (
            <tr key={c.order} className={bad ? 'structure-diff' : undefined}>
              <td>{c.order}</td>
              <td>{c.parentAttributeName || c.logicalAttributeName ? <>{c.parentAttributeName ?? '-'} → {c.logicalAttributeName ?? '-'}</> : <Muted>-</Muted>}</td>
              <td>{c.expectedColumnName || c.expectedParentColumnName ? <>{name(c.expectedColumnName)} → {name(c.expectedParentColumnName)}</> : <Muted>-</Muted>}</td>
              <td>{c.physicalColumnName ? <>{name(c.physicalColumnName)} → {name(c.physicalParentColumnName)}</> : <Muted>-</Muted>}</td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

function UniqueColumns({ columns, reverse }: { columns: StructureColumn[]; reverse: boolean }) {
  return (
    <table className="term-relink-table structure-table">
      <thead>
        <tr>
          <th scope="col" style={{ width: '7%' }}>#</th>
          <th scope="col">{reverse ? t('매핑된 논리 속성') : t('보조 식별자 속성')}</th>
          {!reverse && <th scope="col">{t('매핑 기준 컬럼')}</th>}
          <th scope="col">{t('물리 UNIQUE 컬럼')}</th>
        </tr>
      </thead>
      <tbody>
        {columns.map((c) => (
          <tr key={c.order} className={!reverse && (differs(c.expectedColumnId, c.physicalColumnId) || (c.expectedColumnId == null) !== (c.physicalColumnId == null)) ? 'structure-diff' : undefined}>
            <td>{c.order}</td>
            <td>{c.logicalAttributeName ?? <Muted>-</Muted>}</td>
            {!reverse && <td>{name(c.expectedColumnName)}</td>}
            <td>{name(c.physicalColumnName)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

export function StructureView({ row, structure, onLeave, onClose }: { row: QualityRow; structure: QualityStructure; onLeave: (path: string) => void; onClose: () => void }) {
  const { canView } = useAuth();
  const fk = structure.kind === 'RELATIONSHIP' || structure.kind === 'PHYSICAL_FK';
  const reverse = structure.kind === 'PHYSICAL_FK' || structure.kind === 'PHYSICAL_UNIQUE';
  const entityPath = (entityId: number) => `/logicalModel?modelId=${enc(row.modelId)}&entityId=${enc(entityId)}`;
  const erdPath = (entityId: number) => `/logicalErd?modelId=${enc(row.modelId)}&entityId=${enc(entityId)}`;
  return (
    <section className="structure-detail" aria-label={t('구조 비교')}>
      <h4 className="mapping-subtitle">
        {t('구조 비교')} <span className="gh-muted">· {structureCodeLabel(structure.code)} <span className="gh-mono">({structure.code})</span></span>
      </h4>
      <dl className="dict-detail">
        <dt>{structure.kind === 'ALTERNATE_KEY' ? t('논리 보조 식별자') : structure.kind === 'RELATIONSHIP' ? t('논리 관계') : t('논리 쪽')}</dt>
        <dd>{structure.logicalName ?? (reverse ? t('없음 (물리에만 있는 구조)') : '-')}</dd>
        {fk && <><dt>{t('부모 (참조받는 쪽)')}</dt><dd>{structure.parentEntityName ?? '-'}{structure.parentTableName ? <> · <Mono>{structure.parentTableName}</Mono></> : null}</dd></>}
        <dt>{fk ? t('물리 FK') : t('물리 UNIQUE')}</dt>
        <dd>{structure.physicalName ? <Mono>{structure.physicalName}</Mono> : <Muted>{t('대응 없음')}</Muted>}</dd>
      </dl>
      {structure.columns.length > 0
        ? (fk ? <ForeignKeyColumns columns={structure.columns} /> : <UniqueColumns columns={structure.columns} reverse={reverse} />)
        : <p className="gh-muted">{t('비교할 컬럼 구성이 없습니다.')}</p>}
      {structure.candidates.length > 0 && (
        <>
          <p className="form-help">{t('대응을 정하지 못한 후보')}</p>
          <ul className="structure-candidates">{structure.candidates.map((c) => <li key={c}><Mono>{c}</Mono></li>)}</ul>
        </>
      )}
      <div className="structure-links">
        {canView('logical-model') && <button type="button" className="btn btn-sm" onClick={() => onLeave(entityPath(row.entityId))}>{t('논리 엔터티 상세')}</button>}
        {canView('logical-erd') && <button type="button" className="btn btn-sm" onClick={() => onLeave(erdPath(row.entityId))}>{t('논리 ERD')}</button>}
        {structure.parentEntityId != null && structure.parentEntityId !== row.entityId && canView('logical-erd') && (
          <button type="button" className="btn btn-sm" onClick={() => onLeave(erdPath(structure.parentEntityId!))}>{t('부모 엔터티 ERD')}</button>
        )}
        {row.tableId != null && row.schemaId != null && <TableLinks tableId={row.tableId} schemaId={row.schemaId} onNavigate={onClose} />}
        {row.tableId == null && <span className="gh-muted">{t('물리 테이블이 없어 물리 쪽으로 이동할 수 없습니다.')}</span>}
      </div>
    </section>
  );
}
