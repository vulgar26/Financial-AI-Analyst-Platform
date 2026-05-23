export default function KnowledgeUpload({ disabled, uploading, result, onUpload }) {
  return (
    <section className="panel">
      <div className="panel-title">
        <h2>知识上传</h2>
        <span className="badge">.txt</span>
      </div>
      <p className="muted">上传财报、研报、公告、新闻或研究笔记后，后端会按当前登录用户写入向量库，分析时按用户隔离检索。资料和工具结果需要人工复核。</p>
      <label className="file-picker">
        <span>选择知识文件</span>
        <input
          type="file"
          accept=".txt,text/plain"
          disabled={disabled || uploading}
          onChange={(event) => {
            const file = event.target.files?.[0]
            if (file) onUpload(file)
            event.target.value = ''
          }}
        />
      </label>
      {result ? (
        <div className="result-box">
          <strong>{result.fileName || '上传结果'}</strong>
          <span>{result.chunkCount != null ? `${result.chunkCount} 个知识块` : result.message}</span>
        </div>
      ) : null}
    </section>
  )
}
