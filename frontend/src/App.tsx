import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AppLayout } from './components/layout/AppLayout';
import { ProtectedRoute } from './components/layout/ProtectedRoute';
import { LoginPage } from './components/user/LoginPage';
import { UserManagement } from './components/user/UserManagement';
import { ProfilePage } from './components/user/ProfilePage';
import { Dashboard } from './components/Dashboard';
import { SequenceList } from './components/sequence/SequenceList';
import { SequenceForm } from './components/sequence/SequenceForm';
import { ExecutionList } from './components/execution/ExecutionList';
import { ExecutionDetail } from './components/execution/ExecutionDetail';
import { MessageSimulator } from './components/message/MessageSimulator';
import { MessageLog } from './components/message/MessageLog';
import { AuditLogPage } from './components/audit/AuditLogPage';
import { DemoPage } from './components/demo/DemoPage';

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
          <Route index element={<Dashboard />} />
          <Route path="sequences" element={<SequenceList />} />
          <Route path="sequences/:id" element={<SequenceForm />} />
          <Route path="sequences/:id/edit" element={<SequenceForm />} />
          <Route path="executions" element={<ExecutionList />} />
          <Route path="executions/:id" element={<ExecutionDetail />} />
          <Route path="messages" element={<MessageLog />} />
          <Route path="simulator" element={<MessageSimulator />} />
          <Route path="demo" element={<DemoPage />} />
          <Route path="profile" element={<ProfilePage />} />
          <Route
            path="audit-log"
            element={
              <ProtectedRoute adminOnly>
                <AuditLogPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="users"
            element={
              <ProtectedRoute adminOnly>
                <UserManagement />
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
