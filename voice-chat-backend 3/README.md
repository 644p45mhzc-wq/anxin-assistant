# 网页智能语音对话后端

Java REST API，供网页接入多用户智能对话。支持注册登录、独立会话、持久历史、DeepSeek 简短回答与主动追问，并返回可供网页朗读的纯文本。

语音识别、朗读、暂停及继续默认由浏览器执行。后端预留可替换的识别和合成接口，未来可接入云端或离线语音服务。

## 1. 启动

需要 JDK 21 或以上版本，以及下载 Maven 依赖所需的网络。无需单独安装 Maven。项目采用 Spring Boot 3.5.7 和 Maven 3.9.11。

在 PowerShell 中执行：

```powershell
Set-Location E:\voice-chat-backend
$env:DEEPSEEK_API_KEY = '填写你的DeepSeek密钥'
.\start.ps1
```

脚本先构建并运行测试，再启动服务。首次构建会下载工具和依赖。若已完成验证，可使用 `./start.ps1 -SkipTests`。

默认访问地址：

- 存活检查：<http://localhost:8080/api/health>
- Swagger UI：<http://localhost:8080/swagger-ui.html>
- OpenAPI JSON：<http://localhost:8080/v3/api-docs>
- 语音能力：<http://localhost:8080/api/capabilities>

也可分别构建和运行：

```powershell
.\mvnw.cmd -B -ntp verify
java -jar .\target\voice-chat-backend-1.0.0.jar
```

默认绑定 `127.0.0.1:8080`。若需要通过反向代理或其他设备访问，配置 `SERVER_ADDRESS`，同时正确设置允许的网页来源；正式服务应经 HTTPS 对外提供。

未配置模型密钥时，注册、登录、会话和历史功能仍可运行；发送消息返回 `503 MODEL_NOT_CONFIGURED`，不会伪造回答。

## 2. 配置

`.env.example` 仅作为配置参考，应用和启动脚本不会自动读取 `.env`。通过环境变量或 Spring Boot 外部配置传入实际值。

| 环境变量 | 默认值 | 含义 |
|---|---|---|
| `DEEPSEEK_API_KEY` | 空 | DeepSeek 密钥 |
| `DEEPSEEK_BASE_URL` | `https://api.deepseek.com` | 模型服务根地址；程序追加 `/chat/completions` |
| `DEEPSEEK_MODEL` | `deepseek-chat` | 对话模型名称 |
| `DEEPSEEK_JSON_MODE` | `false` | 是否强制模型使用 JSON 模式 |
| `DEEPSEEK_TIMEOUT` | `60s` | 单次模型请求超时 |
| `SERVER_ADDRESS` | `127.0.0.1` | 监听地址 |
| `SERVER_PORT` | `8080` | 监听端口 |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173,http://localhost:3000` | 允许的网页来源，逗号分隔 |
| `DATABASE_URL` | `jdbc:h2:file:./data/voicechat;DB_CLOSE_ON_EXIT=FALSE` | 数据库地址 |
| `DATABASE_USERNAME` | `sa` | 数据库账号 |
| `DATABASE_PASSWORD` | 空 | 数据库密码 |

Token 有效期默认 24 小时。更多 Spring 配置见 `src/main/resources/application.yml`，例如 `app.token-ttl`、`app.context-rounds`、`app.context-max-chars`。

数据库路径相对于服务启动目录。使用启动脚本时，数据位于项目的 `data/`。保持启动目录和数据库地址一致，避免误以为历史记录丢失。备份应在停止服务后进行，并保护数据库文件的访问权限。

## 3. 最小调用流程

以下 PowerShell 示例不输出密码、Token 或模型密钥：

```powershell
$base = 'http://localhost:8080'
$credentials = @{username='demo_user'; password='change_this_password'} | ConvertTo-Json
Invoke-RestMethod "$base/api/auth/register" -Method Post -ContentType 'application/json' -Body $credentials | Out-Null
$login = Invoke-RestMethod "$base/api/auth/login" -Method Post -ContentType 'application/json' -Body $credentials
$headers = @{Authorization="Bearer $($login.accessToken)"}
$conversation = Invoke-RestMethod "$base/api/conversations" -Method Post -Headers $headers -ContentType 'application/json' -Body '{}'
$body = @{content='我想学习Java'; inputSource='text'} | ConvertTo-Json
$answer = Invoke-RestMethod "$base/api/conversations/$($conversation.id)/messages" -Method Post -Headers $headers -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($body))
$answer.reply
$answer.followUp
Invoke-RestMethod "$base/api/conversations/$($conversation.id)/messages" -Headers $headers
```

详细请求与响应见 [接口文档](docs/API.md)，网页语音接入见 [前端对接说明](docs/FRONTEND.md)。

## 4. 设计与边界

- 用户名为 3～32 位英文字母、数字或下划线，统一按小写保存；密码为 8～64 字符且 UTF-8 长度不超过 72 字节。
- 密码使用 BCrypt（成本 12），Token 使用安全随机数生成，数据库仅保存 SHA-256 摘要。退出仅撤销当前 Token，其他登录不受影响。
- 所有会话按用户隔离；不存在和无权访问统一返回 404。
- 每次生成最多发送最近 20 轮完整历史，历史和当前输入合计不超过 16,000 个 Java UTF-16 代码单元，不包含系统提示。单条输入最多 4,000。旧消息保留在数据库，但不会全部进入模型。
- 模型被要求使用中文、简短回答，必要时提出一个追问。回答属于模型生成内容；实际表达由模型决定。
- 模型适配器兼容 JSON、带代码围栏的 JSON 和纯文本回答；纯文本回答的 `followUp` 为 null，追问可能直接包含于 `reply`。默认不强制 JSON 模式，避免该模式偶发空白输出；空回答、截断回答及错误结构仍会被拒绝。
- 成功后在一个事务内保存用户和助手消息。网络调用期间不占用数据库事务，失败后不保存半轮消息。
- 同一会话生成与删除共用非阻塞锁，忙时返回 409；不同会话可并行。并发控制适用于单服务实例。
- 模型请求不自动重试，避免重复费用；浏览器超时应长于模型超时。网络中断后先查历史，避免将已成功的消息重复提交。
- 数据库通过 Flyway 创建结构，Hibernate 校验结构。不要删除迁移历史或使用 `ddl-auto=create`。
- 默认日志仅记录请求 ID、方法、状态、耗时和错误类型，不记录 Authorization、密码、完整消息或上游响应。
- H2 文件数据库适合单实例使用。扩展至多实例前，需要 PostgreSQL 驱动与迁移、数据迁移验证、分布式会话锁以及共享 Token 存储。
- 注册为开放注册，尚未加入验证码、密码找回或账号管理。公网开放前应配置入口限流与访问策略。

## 5. 验证

```powershell
.\mvnw.cmd -B -ntp verify
```

测试包括：认证与凭证摘要、过期退出、用户隔离、多轮上下文、分页和长度校验、并发生成与删除冲突、模型错误与保存事务回滚、文件数据库重启恢复、真实 HTTP 模型适配器、未启用语音接口，以及自定义语音适配器替换。

自动化测试不调用付费模型服务。真实模型联调结果及交付验证记录见 `docs/VERIFICATION.md`。

项目不包含网页页面、备忘功能、提醒功能或自动云部署。
