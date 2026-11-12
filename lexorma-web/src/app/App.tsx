import { lazy, Suspense, useEffect, useRef } from 'react';
import { BrowserRouter, Navigate, Route, Routes, useNavigate } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { DialogProvider, useDialog } from '../components/dialog/DialogProvider';
import { AppLayout } from '../components/layout/AppLayout';
import { AuthProvider, useAuth } from '../hooks/useAuth';
import { ApiError, setApiErrorListener } from '../lib/api';
import { GuestOnly, RECOVERY_PATH, RequireAuth, RequireMenu, RequireRecovery } from '../routes/guards';
import { LoginPage } from '../features/auth/LoginPage';
import { RecoveryPage } from '../features/auth/RecoveryPage';
import { SignupPage } from '../features/auth/SignupPage';
import { AccountPage } from '../features/auth/AccountPage';
import { DeniedPage } from '../features/auth/DeniedPage';
import { DashboardPage } from '../features/dashboard/DashboardPage';
import { NoticeListPage } from '../features/notice/NoticeListPage';
import { HelpPage } from '../features/help/HelpPage';
import { UserManagePage } from '../features/system/UserManagePage';
import { ProjectManagePage } from '../features/system/ProjectManagePage';
import { SystemLogPage } from '../features/system/SystemLogPage';
import { LogicalModelPage } from '../features/logical/LogicalModelPage';
import { ModelMappingPage } from '../features/mapping/ModelMappingPage';
import { DictionaryPage, DictionaryRedirect } from '../features/dictionary/DataDictionaryPage';
import { SchemaManagePage } from '../features/schema/SchemaManagePage';
import { TableManagePage } from '../features/table/TableManagePage';
import { ColumnManagePage } from '../features/column/ColumnManagePage';
import { ModelHistoryPage } from '../features/history/ModelHistoryPage';
import { ModelVersionPage } from '../features/version/ModelVersionPage';
import { StandardizationQualityPage } from '../features/quality/StandardizationQualityPage';
import { t } from '../lib/i18n';

const PhysicalErdPage = lazy(() => import('../features/erd/PhysicalErdPage'));
const LogicalErdPage = lazy(() => import('../features/logical/LogicalErdPage'));

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // 오류 알림은 api() 가 띄우므로 같은 요청을 다시 보내지 않는다
      retry: false,
      refetchOnWindowFocus: false,
    },
  },
});

/**
 * API 공통 오류 처리 (예전 setupAjaxCsrf 의 error 처리)
 * - 401 : 세션 만료 안내 후 로그인 화면 (여러 요청이 동시에 실패해도 한 번만)
 * - 그 외 : 서버 메시지 알림 (네트워크 오류는 세션 만료로 보지 않는다)
 */
function ApiErrorBridge() {
  const dialog = useDialog();
  const { clear } = useAuth();
  const navigate = useNavigate();
  const sessionAlertOpen = useRef(false);

  useEffect(() => {
    setApiErrorListener((error: ApiError) => {
      if (error.status === 401) {
        if (sessionAlertOpen.current) return;
        sessionAlertOpen.current = true;
        const message = error.code === 'PROJECT_ACCESS_REVOKED' || error.code === 'DUPLICATE_LOGIN' ? error.message : t('세션이 만료되어 로그인 화면으로 이동합니다.');
        dialog.alert(message, () => {
          sessionAlertOpen.current = false;
          clear();
          navigate('/login', { replace: true });
        });
        return;
      }
      dialog.alert(error.message);
    });
    return () => setApiErrorListener(null);
  }, [dialog, clear, navigate]);

  return null;
}

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        {/* 대화상자 내용도 로그인 정보(useAuth)를 쓰므로 AuthProvider 가 DialogProvider 를 감싼다 */}
        <AuthProvider>
          <DialogProvider>
            <ApiErrorBridge />
            <AppLayout>
              <Routes>
                <Route path="/login" element={<GuestOnly><LoginPage /></GuestOnly>} />
                <Route path="/signup" element={<GuestOnly><SignupPage /></GuestOnly>} />
                <Route path={RECOVERY_PATH} element={<RequireRecovery><RecoveryPage /></RequireRecovery>} />
                <Route path="/help" element={<HelpPage />} />
                <Route path="/denied" element={<RequireAuth><DeniedPage /></RequireAuth>} />
                <Route path="/account" element={<RequireAuth><AccountPage /></RequireAuth>} />

                <Route path="/" element={<RequireMenu menuId="dashboard"><DashboardPage /></RequireMenu>} />
                <Route path="/notice" element={<RequireMenu menuId="notice"><NoticeListPage /></RequireMenu>} />
                <Route path="/userManage" element={<RequireMenu menuId="user-manage"><UserManagePage /></RequireMenu>} />
                <Route path="/projectManage" element={<RequireMenu menuId="project-manage"><ProjectManagePage /></RequireMenu>} />
                <Route path="/systemLog" element={<RequireMenu menuId="system-log"><SystemLogPage /></RequireMenu>} />
                <Route path="/standardDomain" element={<RequireMenu menuId="standard-domain"><DictionaryPage key="standardDomains" table="standardDomains" /></RequireMenu>} />
                <Route path="/standardWord" element={<RequireMenu menuId="standard-word"><DictionaryPage key="standardWords" table="standardWords" /></RequireMenu>} />
                <Route path="/standardTerm" element={<RequireMenu menuId="standard-term"><DictionaryPage key="standardTerms" table="standardTerms" /></RequireMenu>} />
                {/* 예전 주소 (/dataDictionary?tab=…) : 탭에 해당하는 화면으로 옮긴다 */}
                <Route path="/dataDictionary" element={<RequireMenu menuId="data-dictionary"><DictionaryRedirect /></RequireMenu>} />
                <Route path="/logicalModel" element={<RequireMenu menuId="logical-model"><LogicalModelPage /></RequireMenu>} />
                <Route path="/logicalErd" element={<RequireMenu menuId="logical-erd"><Suspense fallback={<p>{t('ERD 화면을 불러오는 중…')}</p>}><LogicalErdPage /></Suspense></RequireMenu>} />
                <Route path="/modelMapping" element={<RequireMenu menuId="model-mapping"><ModelMappingPage /></RequireMenu>} />
                <Route path="/schemaManage" element={<RequireMenu menuId="schema-manage"><SchemaManagePage /></RequireMenu>} />
                <Route path="/tableManage" element={<RequireMenu menuId="table-manage"><TableManagePage /></RequireMenu>} />
                <Route path="/physicalErd" element={<RequireMenu menuId="physical-erd"><Suspense fallback={<p>{t('ERD 화면을 불러오는 중…')}</p>}><PhysicalErdPage /></Suspense></RequireMenu>} />
                <Route path="/columnManage" element={<RequireMenu menuId="column-manage"><ColumnManagePage /></RequireMenu>} />
                <Route path="/standardizationQuality" element={<RequireMenu menuId="standardization-quality"><StandardizationQualityPage /></RequireMenu>} />

                <Route path="/modelHistory" element={<RequireMenu menuId="model-history"><ModelHistoryPage /></RequireMenu>} />
                <Route path="/modelVersion" element={<RequireMenu menuId="model-version"><ModelVersionPage /></RequireMenu>} />

                <Route path="*" element={<Navigate to="/" replace />} />
              </Routes>
            </AppLayout>
          </DialogProvider>
        </AuthProvider>
      </BrowserRouter>
    </QueryClientProvider>
  );
}
