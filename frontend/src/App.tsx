import { ConfigProvider } from 'antd'

function App() {
  return (
    <ConfigProvider>
      <div style={{ padding: '24px', textAlign: 'center' }}>
        <h1>ECA System</h1>
        <p>Event-Condition-Action система для обработки авиационных сообщений</p>
      </div>
    </ConfigProvider>
  )
}

export default App
