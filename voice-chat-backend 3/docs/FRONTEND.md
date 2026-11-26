# 网页对接与语音升级说明

当前网站的输入与朗读实现位于 [app.js](../../源代码/app.js)。下文提供接口示例和可选的服务端语音扩展方案。文字输入和语音输入使用同一个对话接口。

## 1. 网页对话

1. 注册后登录，获取 Token。
2. 创建会话，或从会话列表中选择已有会话。
3. 使用 `Authorization` 请求头发送文字。
4. 展示 `reply`，有 `followUp` 时一并展示。
5. 朗读时使用 `speakText`。
6. 刷新网页后，使用同一会话 ID 查询历史并继续对话。

```javascript
async function sendText(baseUrl, token, conversationId, text, clientMessageId, source = 'text') {
  const response = await fetch(`${baseUrl}/api/conversations/${conversationId}/messages`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify({ content: text, inputSource: source, clientMessageId })
  });
  const result = await response.json();
  if (!response.ok) throw result;
  return result;
}
```

Token 不应出现在 URL 或普通日志中。首版没有刷新 Token 的接口；收到 401 后重新登录。示例中的输入文字应通过 `textContent` 展示，避免直接作为 HTML 执行。

## 2. 浏览器语音识别

浏览器识别通常需要 HTTPS 或 localhost、麦克风权限，以及浏览器相关服务的可用性。它不保证离线运行，也不保证所有浏览器和设备支持。

```javascript
const Recognition = window.SpeechRecognition || window.webkitSpeechRecognition;
if (Recognition) {
  const recognition = new Recognition();
  recognition.lang = 'zh-CN';
  recognition.interimResults = false;
  recognition.continuous = false;
  recognition.onresult = event => {
    const text = Array.from(event.results)
      .filter(result => result.isFinal)
      .map(result => result[0].transcript)
      .join('');
    // 先展示文字供用户修改或确认，再调用 sendText(..., text, 'speech')。
  };
  recognition.onerror = event => {
    // 提示麦克风权限、网络或识别错误，并保留文字输入方式。
  };
  // 在用户点击录音按钮时调用 recognition.start()。
  // 用户停止输入时调用 recognition.stop()。
}
```

不支持浏览器识别时，不应发送空文本或虚构转写结果。应继续允许打字，或在未来切换至后端音频上传方案。

## 3. 浏览器朗读、暂停和继续

```javascript
function readAnswer(speakText) {
  if (!('speechSynthesis' in window)) return false;
  window.speechSynthesis.cancel();
  const utterance = new SpeechSynthesisUtterance(speakText);
  utterance.lang = 'zh-CN';
  utterance.rate = 1;
  window.speechSynthesis.speak(utterance);
  return true;
}

function pauseReading() { window.speechSynthesis?.pause(); }
function resumeReading() { window.speechSynthesis?.resume(); }
function stopReading() { window.speechSynthesis?.cancel(); }
```

语音播放应由用户操作触发，并处理设备音色缺失、播放错误及移动浏览器限制。发送下一条语音前建议停止正在朗读的回答，避免麦克风将回答重新识别为用户输入。

暂停朗读仅控制播放，不会取消已经发往 DeepSeek 的生成请求。

## 4. 启用后端语音识别

新增一个实现 `SpeechRecognitionProvider` 的 Spring Bean，替换默认浏览器占位实现：

```java
@Component
public class CloudRecognition implements SpeechRecognitionProvider {
    public String name() { return "your-provider"; }
    public boolean available() { return true; }
    public Transcription transcribe(AudioInput input) {
        // 调用供应商：检查真实音频编码、转码、设置超时并处理认证和限流。
        // 返回供应商实际识别的文字与语言；不要返回模拟结果。
        throw new UnsupportedOperationException("Implement the provider integration");
    }
}
```

上例是扩展结构示例，不是可用的语音供应商实现。实际项目只应注册一个识别 Bean；默认 Bean 使用 `@ConditionalOnMissingBean` 自动让位。供应商密钥通过环境配置注入，错误转换为 `ApiException`，不要将上游密钥或响应原样返回给网页。

采用后端识别的客户端可先读取 `/api/capabilities`，当 `recognition.backendAvailable` 为 true 时，可使用 `MediaRecorder` 录音，将 Blob 放入 `FormData` 的 `file` 字段，上传至 `/api/speech/transcriptions`。不要手动设置 multipart boundary。获得 `text` 后仍调用原有的消息发送接口。

后端已限制单个音频 10MB，并接受 WebM、Ogg、WAV、MP3、MP4 和 FLAC 对应的 MIME 类型；供应商适配器负责内容检测、实际格式兼容、转码与供应商限制。

## 5. 启用后端语音合成

新增一个实现 `SpeechSynthesisProvider` 的 Spring Bean，返回 `AudioOutput`，其中包含实际音频字节和音频 MIME 类型。保持仅一个合成 Bean。

网页确认 `synthesis.backendAvailable` 后：

1. 将 `speakText`、可选音色和语速提交到 `/api/speech/synthesis`。
2. 将响应读取为 Blob，创建 Object URL 后交给音频播放器。
3. 用播放器的 `pause()`、`play()` 控制暂停和继续；停止时暂停并将播放位置归零。
4. 播放结束或组件销毁时释放 Object URL。

合成接口单次最多 4,000 字符。若朗读文本超过限制，网页应分段请求并按顺序播放。会话和文字对话接口不需要随供应商变化而调整。
