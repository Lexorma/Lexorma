import { Link, useNavigate } from 'react-router-dom';
import { ShieldLockIcon } from '@primer/octicons-react';
import { useAuth } from '../../hooks/useAuth';

/** 권한 없음 (GitHub 오류 화면 형태 - Blankslate) */
export function DeniedPage({ title = '접근이 거부되었습니다.', message }: { title?: string; message?: string }) {
  const { displayName } = useAuth();
  const navigate = useNavigate();

  return (
    <div className="denied">
      <div className="denied-icon">
        <ShieldLockIcon size={32} />
      </div>

      <h1 className="denied-title">{title}</h1>

      {displayName ? (
        <p className="denied-desc">
          {message
            ? `${displayName} 님은 ${message}`
            : `${displayName} 님은 해당 페이지에 접근 권한이 없습니다. 관리자에게 접근권한을 신청하시기 바랍니다.`}
        </p>
      ) : (
        <p className="denied-desc">비정상적인 접근입니다.</p>
      )}

      <div className="denied-actions">
        <Link to="/" className="btn btn-primary">홈으로 돌아가기</Link>
        <button type="button" className="btn" onClick={() => navigate(-1)}>이전 화면</button>
      </div>
    </div>
  );
}
