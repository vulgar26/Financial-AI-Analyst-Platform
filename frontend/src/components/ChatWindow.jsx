export default function ChatWindow({
  conversationId,
  query,
  messages,
  streaming,
  loading,
  onConversationChange,
  onQueryChange,
  onNewConversation,
  onSend,
  onStop,
}) {
  return (
    <section className="panel chat-panel">
      <div className="panel-title">
        <h2>会话 / 聊天</h2>
        <span className={streaming ? 'badge streaming' : 'badge'}>{streaming ? 'streaming' : 'ready'}</span>
      </div>
      <div className="conversation-row">
        <label>
          conversationId
          <input value={conversationId} onChange={(event) => onConversationChange(event.target.value)} />
        </label>
        <button type="button" className="secondary" onClick={onNewConversation}>新建</button>
      </div>
      <div className="messages" aria-live="polite">
        {messages.length === 0 ? (
          <p className="empty">登录、创建会话并上传研究资料后，即可发起财报、研报、公告、新闻或市场数据相关的分析问题。输出不构成投资建议，关键结论需要人工复核。</p>
        ) : (
          messages.map((message) => (
            <article className={`message ${message.role}`} key={message.id}>
              <span>{message.role === 'user' ? '你' : '助手'}</span>
              <p>{message.content || (message.role === 'assistant' ? '正在生成...' : '')}</p>
            </article>
          ))
        )}
      </div>
      <label>
        问题
        <textarea
          rows={3}
          value={query}
          disabled={loading}
          onChange={(event) => onQueryChange(event.target.value)}
        />
      </label>
      <div className="actions">
        <button type="button" disabled={loading || !query.trim()} onClick={onSend}>发送</button>
        <button type="button" className="secondary" disabled={!streaming} onClick={onStop}>停止</button>
      </div>
    </section>
  )
}
