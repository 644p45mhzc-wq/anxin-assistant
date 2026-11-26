# API 参考

接口前缀为 `/api`，编码 UTF-8。时间使用 ISO 8601 UTC；网页可按用户时区显示。除注册、登录、能力查询、健康检查及 API 文档外，所有接口必须携带：

```http
Authorization: Bearer <accessToken>
```

请求支持跨域，但来源必须在 `CORS_ALLOWED_ORIGINS` 中。Token 通过 Authorization 请求头传递，不使用认证 Cookie。

## 账号

### POST /api/auth/register

```json
{"username":"demo_user","password":"change_this_password"}
```

成功状态 201：

```json
{"id":"用户UUID","username":"demo_user","createdAt":"2026-01-01T00:00:00Z"}
```

用户名大小写不敏感，仅允许 3～32 位英文字母、数字或下划线。密码为 8～64 字符，UTF-8 不超过 72 字节。重复用户名返回 409。

### POST /api/auth/login

请求与注册相同，成功状态 200：

```json
{
  "accessToken":"随机登录凭证",
  "tokenType":"Bearer",
  "expiresAt":"2026-01-02T00:00:00Z",
  "user":{"id":"用户UUID","username":"demo_user","createdAt":"2026-01-01T00:00:00Z"}
}
```

凭证有效期为 24 小时，无刷新接口；过期后重新登录。用户名或密码错误返回 401。

### POST /api/auth/logout

不需要请求体，成功返回 204，无响应体。当前凭证立即失效。

## 会话

### POST /api/conversations

请求体可省略，或传入：

```json
{"title":"Java学习"}
```

标题最多 100 字符；空标题使用“新对话”，第一轮成功后自动取用户输入前 40 个 Unicode 码点作为标题。成功返回 201：

```json
{"id":"会话UUID","title":"Java学习","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"}
```

### GET /api/conversations?page=0&size=20

按最近更新时间倒序排列，同时间按 ID 倒序稳定排序。页码从 0 开始，最大 1,000,000；每页默认 20，范围 1～100。

```json
{"items":[{"id":"会话UUID","title":"Java学习","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"}],"page":0,"size":20,"totalElements":1,"totalPages":1}
```

### POST /api/conversations/{id}/messages

```json
{"content":"我想学习Java","inputSource":"text","clientMessageId":"demo-1"}
```

`content` 必填且非空，最多 4,000 个 Java UTF-16 代码单元。`inputSource` 可为 `text` 或 `speech`，省略或为 null 时默认 `text`。语音输入应传识别后的文字，不传音频或 base64。

客户端可以提供 `clientMessageId`。同一会话和接口中，重试原消息时沿用同一个编号，即可取回已保存的回答。用户发起新消息时应生成新编号。

成功返回 200：

```json
{
  "conversationId":"会话UUID",
  "userMessageId":1,
  "assistantMessageId":2,
  "reply":"可以先学习变量、条件判断和循环，再练习简单程序。",
  "followUp":"你之前接触过编程吗？",
  "speakText":"可以先学习变量、条件判断和循环，再练习简单程序。\n你之前接触过编程吗？",
  "createdAt":"2026-01-01T00:00:00Z"
}
```

无需追问时 `followUp` 为 null。`speakText` 合并回答及追问并清理常见 Markdown 标记，适合朗读；它不用于 HTML 注入，网页应使用 `textContent` 展示文字。

请求为非流式，模型超时默认 60 秒。客户端请求超时建议设置为 75～90 秒。同一会话正在生成或删除时，返回 409。模型失败不会保存本轮消息，不会自动重试。

### GET /api/conversations/{id}/messages?page=0&size=20

分页规则与会话列表相同；按消息 ID 正序排列，即从最早消息开始读取。

```json
{
  "items":[
    {"id":1,"role":"user","content":"我想学习Java","followUp":null,"speakText":null,"inputSource":"text","createdAt":"2026-01-01T00:00:00Z"},
    {"id":2,"role":"assistant","content":"可以先学习基础语法。","followUp":null,"speakText":"可以先学习基础语法。","inputSource":null,"createdAt":"2026-01-01T00:00:00Z"}
  ],
  "page":0,"size":20,"totalElements":2,"totalPages":1
}
```

### DELETE /api/conversations/{id}

删除会话及全部消息，成功返回 204。会话不存在或不属于当前用户时返回 404，忙时返回 409。

### 修改标题与聊天备忘建议

- `POST /api/conversations/{id}/title` 接受 `{"title":"我的新标题"}`，返回更新后的会话。
- `POST /api/conversations/{id}/memo-suggestions` 接受与发送消息相同的字段，保存询问是否记入备忘的对话。用户确认后，网页再调用下方创建接口。

## 备忘

以下操作均基于登录账号。时间说明保留用户填写的中文，例如“明天上午九点”。`scheduleAnchor` 记录解释相对日期所用的基准时间。

| 请求 | 请求体或行为 |
|---|---|
| `GET /api/memos` | 返回当前账号的备忘列表。 |
| `POST /api/memos` | 提交 `id`、`created` 和 `fields`。fields 包含 text、when、details、scheduleAnchor。 |
| `PUT /api/memos/{id}` | 提交 `revision` 和更新后的 `fields`。 |
| `DELETE /api/memos/{id}?revision=当前版本` | 删除指定版本的备忘。 |

重复创建同一条备忘会返回已有记录。已删除的编号返回 410，编辑或删除过期版本返回 409。

## 语音与健康检查

### GET /api/capabilities

无需登录。初始配置返回：

```json
{
  "recognition":{"mode":"browser","backendAvailable":false,"provider":null},
  "synthesis":{"mode":"browser","backendAvailable":false,"provider":null},
  "browserPlayback":{"pause":true,"resume":true,"stop":true},
  "limits":{
    "maxInputCharacters":4000,
    "maxAudioBytes":10485760,
    "acceptedAudioTypes":["audio/flac","audio/mp4","audio/mpeg","audio/ogg","audio/wav","audio/webm","audio/x-flac","audio/x-wav"]
  }
}
```

`browserPlayback` 表示网页应提供的播放操作，不能替代对浏览器实际能力的检测。`maxAudioBytes` 和音频格式表示后端启用识别后使用的上传限制，不代表当前已启用音频上传。

### POST /api/speech/transcriptions

需登录，使用 `multipart/form-data`：

- `file`：音频文件；启用服务时必填，最大 10MB。
- `language`：语言，默认 `zh-CN`，最多 35 字符。

未配置后端语音识别时返回 `501 SPEECH_PROVIDER_NOT_CONFIGURED`。启用后，成功响应约定为：

```json
{"text":"识别得到的文字","language":"zh-CN"}
```

上传格式按 Content-Type 初步校验，适配器还应检查实际文件编码及内容。转写不自动发送消息或保存会话；网页可供用户确认后调用原对话接口。

### POST /api/speech/synthesis

需登录，使用 JSON：

```json
{"text":"需要朗读的文字","voice":null,"speed":1.0}
```

文字必填、最多 4,000 个 UTF-16 代码单元；音色可选、最多 100 字符；语速默认 1.0，范围 0.5～2.0。音色的有效值由所接入供应商定义。

未配置后端语音合成时返回 `501 SPEECH_PROVIDER_NOT_CONFIGURED`。启用后直接返回音频字节，Content-Type 为 `audio/*`，设置不缓存，不返回 JSON 包裹的 base64。

### GET /api/health

无需登录，成功返回 `{"status":"UP"}`。此接口只检查服务存活，不调用付费模型，也不代表外部模型可用。

## 错误响应

业务错误统一返回：

```json
{"code":"MODEL_TIMEOUT","message":"回答生成超时，请稍后重试","requestId":"请求UUID"}
```

所有请求响应附带 `X-Request-ID`，可用于查询服务日志。无效 JSON、未知请求字段、超长输入和非法分页返回 400。

| HTTP | 典型错误码 | 含义 |
|---|---|---|
| 400 | `VALIDATION_ERROR` | 参数无效 |
| 401 | `UNAUTHORIZED` / `INVALID_CREDENTIALS` | 未登录、凭证失效或登录失败 |
| 403 | `CORS_ORIGIN_DENIED` / `FORBIDDEN` | 网页来源未被允许或操作被禁止 |
| 404 | `CONVERSATION_NOT_FOUND` | 会话不存在或无权访问 |
| 409 | `USERNAME_EXISTS` / `CONVERSATION_BUSY` | 用户名重复或会话处理中 |
| 413 | `UPLOAD_TOO_LARGE` | 音频超过限制 |
| 415 | `UNSUPPORTED_AUDIO_TYPE` / `UNSUPPORTED_MEDIA_TYPE` | 音频或请求内容类型不受支持 |
| 429 | `MODEL_RATE_LIMITED` | 模型服务限流 |
| 501 | `SPEECH_PROVIDER_NOT_CONFIGURED` | 后端语音服务未配置 |
| 502 | `MODEL_UPSTREAM_ERROR` / `MODEL_INVALID_RESPONSE` / `MODEL_CONNECTION_FAILED` | 模型服务或响应异常 |
| 503 | `MODEL_NOT_CONFIGURED` / `MODEL_AUTH_FAILED` / `MODEL_CONFIGURATION_ERROR` / `MODEL_INTERRUPTED` | 模型未配置、认证失败或请求中断 |
| 504 | `MODEL_TIMEOUT` | 模型请求超时 |
| 500 | `INTERNAL_ERROR` | 未预期服务错误 |

对于网关或浏览器自身拦截的错误，响应格式可能不受后端控制。
