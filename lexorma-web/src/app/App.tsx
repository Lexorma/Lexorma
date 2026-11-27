import { lazy, Suspense, useEffect, useRef } from 'react';
import { createBrowserRouter, createRoutesFromElements, Navigate, Outlet, Route, RouterProvider, useNavigate } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { DialogProvider, useDialog } from '../components/dialog/DialogProvider';
import { AppLayout } from '../components/layout/AppLayout';
import { ToastProvider, useToast } from '../components/toast/ToastProvider';
import { AuthProvider, useAuth } from '../hooks/useAuth';
import { ApiError, isLocked, setApiErrorListener } from '../lib/api';
import { GuestOnly, RECOVERY_PATH, RequireAuth, RequireMenu, RequireRecovery } from '../routes/guards';
import { LoginPage } from '../features/auth/LoginPage';
import { RecoveryPage } from '../features/auth/RecoveryPage';
import { SignupPage } from '../features/auth/SignupPage';
import { RequireSetupDone, SETUP_PATH, SetupPage } from '../features/auth/SetupPage';
import { AccountPage } from '../features/auth/AccountPage';
import { DeniedPage } from '../features/auth/DeniedPage';
import { DashboardPage } from '../features/dashboard/DashboardPage';
import { NoticeListPage } from '../features/notice/NoticeListPage';
import { HelpPage } from '../features/help/HelpPage';
import { UserManagePage } from '../features/system/UserManagePage';
import { ProjectManagePage } from '../features/system/ProjectManagePage';
import { SystemLogPage } from '../features/system/SystemLogPage';
import { AiProviderPage } from '../features/system/AiProviderPage';
import { ConnectionSecurityPage } from '../features/system/ConnectionSecurityPage';
import { LogicalModelPage } from '../features/logical/LogicalModelPage';
import { ModelMappingPage } from '../features/mapping/ModelMappingPage';
import { DictionaryPage, DictionaryRedirect } from '../features/dictionary/DataDictionaryPage';
import { SchemaManagePage } from '../features/schema/SchemaManagePage';
import { TableManagePage } from '../features/table/TableManagePage';
import { ColumnManagePage } from '../features/column/ColumnManagePage';
import { IndexManagePage } from '../features/index/IndexManagePage';
import { ViewManagePage } from '../features/view/ViewManagePage';
import { DdlExportPage } from '../features/ddl/DdlExportPage';
import { SourceCatalogPage } from '../features/source/SourceCatalogPage';
import { SourceCollectionPage } from '../features/source/SourceCollectionPage';
import { SourceConnectionPage } from '../features/source/SourceConnectionPage';
import { SourceMappingPage } from '../features/source/SourceMappingPage';
import { SourceComparePage } from '../features/source/SourceComparePage';
import { ModelHistoryPage } from '../features/history/ModelHistoryPage';
import { ModelVersionPage } from '../features/version/ModelVersionPage';
import { ModelExchangePage } from '../features/exchange/ModelExchangePage';
import { McpAccessPage } from '../features/mcp/McpAccessPage';
import { QualityInspectionPage } from '../features/quality/QualityInspectionPage';
import { ModelDiagnosticsPage } from '../features/quality/ModelDiagnosticsPage';
import { navigateWithoutGuard } from '../hooks/useUnsavedChanges';
import { t } from '../lib/i18n';

const PhysicalErdPage = lazy(() => import('../features/erd/PhysicalErdPage'));
const LogicalErdPage = lazy(() => import('../features/logical/LogicalErdPage'));
const AiDescriptionPage = lazy(() => import('../features/dictionary/ai/AiDescriptionPage'));
const AiEvaluationPage = lazy(() => import('../features/dictionary/ai/AiEvaluationPage'));
const SourceErdPage = lazy(() => import('../features/source/SourceErdPage'));

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
  const toast = useToast();
  const { clear } = useAuth();
  const navigate = useNavigate();
  const sessionAlertOpen = useRef(false);

  useEffect(() => {
    setApiErrorListener((error: ApiError) => {
      if (error.status === 401) {
        if (sessionAlertOpen.current) return;
        sessionAlertOpen.current = true;
        // 세션이 끝났으므로 이전 업무 안내는 바로 지운다 (안내를 닫으면 로그아웃 상태가 되어 범위도 바뀐다)
        toast.clear();
        const message = error.code === 'PROJECT_ACCESS_REVOKED' || error.code === 'DUPLICATE_LOGIN' ? error.message : t('세션이 만료되어 로그인 화면으로 이동합니다.');
        dialog.alert(message, () => {
          sessionAlertOpen.current = false;
          clear();
          // 세션 만료·권한 회수는 저장하지 않은 배치 확인으로 막지 않는다 (D-19)
          navigateWithoutGuard(() => navigate('/login', { replace: true }));
        });
        return;
      }
      // 잠금 충돌 (M8-C4c-2 C4c-D10) : 데이터가 바뀐 것이 아니라 다른 작업이 처리 중이다 — 비교 화면 없이 안내만, 사용자가 같은 요청을 직접 다시 보낸다
      if (isLocked(error)) {
        dialog.alert(`${t('다른 작업이 같은 데이터를 처리하고 있어 이번 요청을 처리하지 못했습니다.')}\n${error.message}\n\n${t('입력한 내용과 처음 확인한 기준은 그대로입니다. 잠시 뒤 같은 버튼으로 직접 다시 시도해 주세요. 그 사이 데이터가 바뀌었으면 변경 내용 확인 화면으로 안내합니다.')}`);
        return;
      }
      dialog.alert(error.message);
    });
    return () => setApiErrorListener(null);
  }, [dialog, toast, clear, navigate]);

  return null;
}

/**
 * 화면 공통 틀 : 대화상자 내용도 로그인 정보(useAuth)를 쓰므로 AuthProvider 가 DialogProvider 를 감싼다.
 * 토스트는 로그인 범위(사용자·프로젝트)에 묶이고 대화상자 안에서도 부르므로 그 사이에 둔다. 라우터 안에 있어야 대화상자·오류 처리에서 이동할 수 있다
 */
function Root() {
  return (
    <AuthProvider>
      <ToastProvider>
        <DialogProvider>
          <ApiErrorBridge />
          <AppLayout>
            <Outlet />
          </AppLayout>
        </DialogProvider>
      </ToastProvider>
    </AuthProvider>
  );
}

/**
 * 데이터 라우터 (D-19 : 저장하지 않은 배치가 있을 때 사이드바·링크·뒤로/앞으로 이동을 확인하려고 useBlocker 를 쓴다).
 * 경로 정의·주소는 예전 BrowserRouter 의 Routes 그대로이고 loader·action 은 쓰지 않는다. 모듈에서 한 번만 만든다 (다시 그려도 새로 만들지 않는다)
 */
const router = createBrowserRouter(createRoutesFromElements(
  <Route element={<Root />}>
    {/* 계정이 하나도 없으면 로그인·회원가입 대신 최초 관리자 계정 만들기 */}
    <Route path="/login" element={<GuestOnly><RequireSetupDone><LoginPage /></RequireSetupDone></GuestOnly>} />
    <Route path="/signup" element={<GuestOnly><RequireSetupDone><SignupPage /></RequireSetupDone></GuestOnly>} />
    <Route path={SETUP_PATH} element={<GuestOnly><SetupPage /></GuestOnly>} />
    <Route path={RECOVERY_PATH} element={<RequireRecovery><RecoveryPage /></RequireRecovery>} />
    <Route path="/help" element={<HelpPage />} />
    <Route path="/denied" element={<RequireAuth><DeniedPage /></RequireAuth>} />
    <Route path="/account" element={<RequireAuth><AccountPage /></RequireAuth>} />

    <Route path="/" element={<RequireMenu menuId="dashboard"><DashboardPage /></RequireMenu>} />
    <Route path="/notice" element={<RequireMenu menuId="notice"><NoticeListPage /></RequireMenu>} />
    <Route path="/userManage" element={<RequireMenu menuId="user-manage"><UserManagePage /></RequireMenu>} />
    <Route path="/projectManage" element={<RequireMenu menuId="project-manage"><ProjectManagePage /></RequireMenu>} />
    <Route path="/systemLog" element={<RequireMenu menuId="system-log"><SystemLogPage /></RequireMenu>} />
    <Route path="/connectionSecurity" element={<RequireMenu menuId="connection-security"><ConnectionSecurityPage /></RequireMenu>} />
    <Route path="/aiProvider" element={<RequireMenu menuId="ai-provider"><AiProviderPage /></RequireMenu>} />
    <Route path="/standardDomain" element={<RequireMenu menuId="standard-domain"><DictionaryPage key="standardDomains" table="standardDomains" /></RequireMenu>} />
    <Route path="/standardWord" element={<RequireMenu menuId="standard-word"><DictionaryPage key="standardWords" table="standardWords" /></RequireMenu>} />
    <Route path="/standardTerm" element={<RequireMenu menuId="standard-term"><DictionaryPage key="standardTerms" table="standardTerms" /></RequireMenu>} />
    <Route path="/aiDictionary" element={<RequireMenu menuId="ai-dictionary"><AiDictionaryRoute /></RequireMenu>} />
    <Route path="/aiEvaluation" element={<RequireMenu menuId="ai-evaluation"><Suspense fallback={<p>{t('불러오는 중…')}</p>}><AiEvaluationPage /></Suspense></RequireMenu>} />
    {/* 예전 주소 (/dataDictionary?tab=…) : 탭에 해당하는 화면으로 옮긴다 */}
    <Route path="/dataDictionary" element={<RequireMenu menuId="data-dictionary"><DictionaryRedirect /></RequireMenu>} />
    <Route path="/logicalModel" element={<RequireMenu menuId="logical-model"><LogicalModelPage /></RequireMenu>} />
    <Route path="/logicalErd" element={<RequireMenu menuId="logical-erd"><Suspense fallback={<p>{t('ERD 화면을 불러오는 중…')}</p>}><LogicalErdPage /></Suspense></RequireMenu>} />
    <Route path="/modelMapping" element={<RequireMenu menuId="model-mapping"><ModelMappingPage /></RequireMenu>} />
    <Route path="/schemaManage" element={<RequireMenu menuId="schema-manage"><SchemaManagePage /></RequireMenu>} />
    <Route path="/tableManage" element={<RequireMenu menuId="table-manage"><TableManagePage /></RequireMenu>} />
    <Route path="/physicalErd" element={<RequireMenu menuId="physical-erd"><Suspense fallback={<p>{t('ERD 화면을 불러오는 중…')}</p>}><PhysicalErdPage /></Suspense></RequireMenu>} />
    <Route path="/columnManage" element={<RequireMenu menuId="column-manage"><ColumnManagePage /></RequireMenu>} />
    <Route path="/indexManage" element={<RequireMenu menuId="index-manage"><IndexManagePage /></RequireMenu>} />
    <Route path="/viewManage" element={<RequireMenu menuId="view-manage"><ViewManagePage /></RequireMenu>} />
    <Route path="/ddlExport" element={<RequireMenu menuId="ddl-export"><DdlExportPage /></RequireMenu>} />
    <Route path="/sourceConnection" element={<RequireMenu menuId="source-connection"><SourceConnectionPage /></RequireMenu>} />
    <Route path="/sourceCollection" element={<RequireMenu menuId="source-collection"><SourceCollectionPage /></RequireMenu>} />
    <Route path="/sourceCatalog" element={<RequireMenu menuId="source-catalog"><SourceCatalogPage /></RequireMenu>} />
    <Route path="/sourceMapping" element={<RequireMenu menuId="source-mapping"><SourceMappingPage /></RequireMenu>} />
    <Route path="/sourceCompare" element={<RequireMenu menuId="source-compare"><SourceComparePage /></RequireMenu>} />
    <Route path="/sourceErd" element={<RequireMenu menuId="source-erd"><Suspense fallback={<p>{t('ERD 화면을 불러오는 중…')}</p>}><SourceErdPage /></Suspense></RequireMenu>} />
    <Route path="/standardizationQuality" element={<RequireMenu menuId="standardization-quality"><QualityInspectionPage /></RequireMenu>} />
    <Route path="/modelDiagnostics" element={<RequireMenu menuId="model-diagnostics"><ModelDiagnosticsPage /></RequireMenu>} />

    <Route path="/modelHistory" element={<RequireMenu menuId="model-history"><ModelHistoryPage /></RequireMenu>} />
    <Route path="/modelVersion" element={<RequireMenu menuId="model-version"><ModelVersionPage /></RequireMenu>} />
    <Route path="/modelExchange" element={<RequireMenu menuId="model-exchange"><ModelExchangePage /></RequireMenu>} />
    <Route path="/mcpAccess" element={<RequireMenu menuId="mcp-access"><McpAccessPage /></RequireMenu>} />

    <Route path="*" element={<Navigate to="/" replace />} />
  </Route>,
));

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  );
}

/** AI 설명 제안 (/aiDictionary). 예전 품질 평가 탭 주소 ?tab=evaluation 은 AI 관리 › AI 품질 평가(/aiEvaluation)로 옮긴다 */
function AiDictionaryRoute() {
  if (new URLSearchParams(window.location.search).get('tab') === 'evaluation') return <Navigate to="/aiEvaluation" replace />;
  return <Suspense fallback={<p>{t('불러오는 중…')}</p>}><AiDescriptionPage /></Suspense>;
}
