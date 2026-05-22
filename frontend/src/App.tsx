import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { Suspense, lazy } from 'react';
import { Spin } from 'antd';
import { AppLayout } from './components/layout/AppLayout';
import { ProtectedRoute } from './components/layout/ProtectedRoute';
import { LoginPage } from './components/user/LoginPage';

const Dashboard        = lazy(() => import('./components/Dashboard').then(m => ({ default: m.Dashboard })));
const SequenceList     = lazy(() => import('./components/sequence/SequenceList').then(m => ({ default: m.SequenceList })));
const SequenceForm     = lazy(() => import('./components/sequence/SequenceForm').then(m => ({ default: m.SequenceForm })));
const ExecutionList    = lazy(() => import('./components/execution/ExecutionList').then(m => ({ default: m.ExecutionList })));
const ExecutionDetail  = lazy(() => import('./components/execution/ExecutionDetail').then(m => ({ default: m.ExecutionDetail })));
const MessageSimulator = lazy(() => import('./components/message/MessageSimulator').then(m => ({ default: m.MessageSimulator })));
const MessageLog       = lazy(() => import('./components/message/MessageLog').then(m => ({ default: m.MessageLog })));
const AuditLogPage     = lazy(() => import('./components/audit/AuditLogPage').then(m => ({ default: m.AuditLogPage })));
const DemoPage         = lazy(() => import('./components/demo/DemoPage').then(m => ({ default: m.DemoPage })));
const UserManagement   = lazy(() => import('./components/user/UserManagement').then(m => ({ default: m.UserManagement })));
const ProfilePage      = lazy(() => import('./components/user/ProfilePage').then(m => ({ default: m.ProfilePage })));
const TimelinePage     = lazy(() => import('./pages/TimelinePage').then(m => ({ default: m.TimelinePage })));

const PageLoader = () => (
  <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: 300 }}>
    <Spin size="large" />
  </div>
);

function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<LoginPage />} />

        <Route
          path="/"
          element={
            <ProtectedRoute>
              <AppLayout />
            </ProtectedRoute>
          }
        >
          <Route index element={<Suspense fallback={<PageLoader />}><Dashboard /></Suspense>} />
          <Route path="sequences" element={<Suspense fallback={<PageLoader />}><SequenceList /></Suspense>} />
          <Route path="sequences/:id" element={<Suspense fallback={<PageLoader />}><SequenceForm /></Suspense>} />
          <Route path="sequences/:id/edit" element={<Suspense fallback={<PageLoader />}><SequenceForm /></Suspense>} />
          <Route path="executions" element={<Suspense fallback={<PageLoader />}><ExecutionList /></Suspense>} />
          <Route path="executions/:id" element={<Suspense fallback={<PageLoader />}><ExecutionDetail /></Suspense>} />
          <Route path="messages" element={<Suspense fallback={<PageLoader />}><MessageLog /></Suspense>} />
          <Route path="timeline" element={<Suspense fallback={<PageLoader />}><TimelinePage /></Suspense>} />
          <Route path="simulator" element={<Suspense fallback={<PageLoader />}><MessageSimulator /></Suspense>} />
          <Route path="demo" element={<Suspense fallback={<PageLoader />}><DemoPage /></Suspense>} />
          <Route path="profile" element={<Suspense fallback={<PageLoader />}><ProfilePage /></Suspense>} />
          <Route
            path="audit-log"
            element={
              <ProtectedRoute adminOnly>
                <Suspense fallback={<PageLoader />}><AuditLogPage /></Suspense>
              </ProtectedRoute>
            }
          />
          <Route
            path="users"
            element={
              <ProtectedRoute adminOnly>
                <Suspense fallback={<PageLoader />}><UserManagement /></Suspense>
              </ProtectedRoute>
            }
          />
        </Route>

        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  );
}

export default App;
