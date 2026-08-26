import { Component, type ErrorInfo, type ReactNode } from 'react';
import { Button, Result } from 'antd';
import { EDITOR_DICTS } from '../i18n/dict';

interface ErrorBoundaryProps {
  children: ReactNode;
}

interface ErrorBoundaryState {
  hasError: boolean;
}

const d = EDITOR_DICTS.ru;

/**
 * Ловит ошибки рендера lazy-роутов (в т.ч. сбой загрузки чанка при деплое) —
 * без неё необработанное исключение белым экраном роняет всё приложение.
 */
export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { hasError: false };

  static getDerivedStateFromError(): ErrorBoundaryState {
    return { hasError: true };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    console.error('[ErrorBoundary]', error, info.componentStack);
  }

  private handleReload = (): void => {
    window.location.reload();
  };

  render(): ReactNode {
    if (this.state.hasError) {
      return (
        <Result
          status="error"
          title={d.errorBoundaryTitle}
          subTitle={d.errorBoundarySubtitle}
          extra={
            <Button type="primary" onClick={this.handleReload}>
              {d.errorBoundaryReload}
            </Button>
          }
        />
      );
    }

    return this.props.children;
  }
}
