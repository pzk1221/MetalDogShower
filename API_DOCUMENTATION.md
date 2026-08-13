# API 记录

协议证据主要来自金属狗官网分发的 Android APK 4.1.52 静态代码，以及研究阶段的一次不带认证的公开设备映射。用户曾反馈上一版在现场完成过正常开启和关闭，但当时没有保留可审计的脱敏响应与精确请求计数；因此本文把它记作使用反馈，不用它推断 token 寿命、完整响应 wrapper 或所有业务错误码。v0.2 本轮没有安装或发起任何真实网络、OTP、状态或控制请求。

示例中的标识符、手机号与 token 都是占位符。

## 共通约定

- Base URL：`https://api.sbooy.com/`
- 官方 APK 中 `brand_id=1041`
- 官方 APK 静态代码中的超时：8 秒；本项目 Real 客户端为 connect/read/write 10 秒、整次 call 15 秒
- 认证：自定义请求头 `token: <REDACTED>`，不是 Bearer
- 项目只给受保护方法附加 token，并要求其长度大于 100；内部认证标记在发出网络请求前删除，公开方法绝不附带 token
- 真实凭据只允许通过 HTTPS；二维码中的明文 HTTP 地址只在本地解析，不携带 token 访问
- 已验证的官方代码在 401 后删除 token；未发现 refresh token、刷新端点或轮换流程
- token 的生产寿命未知；项目没有“硬 5 分钟 token TTL”，手机和手表都以官方 401 或用户退出作为失效依据
- 项目关闭连接失败自动重试、HTTP/HTTPS 重定向和 HTTP body/header 日志

## 设备短码解析

```http
GET /device/0/0/public/info?dev_id=<QR_SHORT_CODE>
```

认证：无。

研究阶段已实时验证一次，TLS 校验开启、无 Cookie/token、无重试、无重定向。响应可以直接包含：

```json
{
  "dev_id": "<CANONICAL_DEVICE_ID>",
  "dev_name": "淋浴1",
  "stadium_id": 1234,
  "type": "shower"
}
```

该次请求的精确 HTTP 状态当时未单独记录，只能确认 `curl --fail-with-body` 成功且 JSON 解析成功，不写成猜测的 200。真实短码、canonical device id 和 stadium id 不进入源码或公开文档。

v0.2 可分别解析浴室1和浴室2。每次用户点击“识别并保存”最多执行一次公开设备映射 GET；必须核对 `type=shower`，两个槽位不得使用相同 canonical `deviceId`。修改已登录会话中的路由不会重新登录：手机保留 token，但轮换 `credentialId` 并要求重新同步手表。

## 验证码标记

```http
GET /user/0/0/public/validateCodeMark?phone=<PHONE>
```

认证：无。代码级响应提供 `mark`；完整生产响应包裹结构没有在 v0.2 本轮重新验证。

## 发送短信验证码

```http
GET /user/0/0/public/validateCode?phone=<PHONE>&app_id=null&key=<MD5_KEY>
```

认证：无。`key` 的精确算法见 [AUTH_FLOW.md](AUTH_FLOW.md)。只有用户主动点击后允许发送；不自动重试，也不为测试主动多发验证码。项目按官方调用把 `app_id` 序列化为字面字符串 `null`。

一次“请求验证码”动作严格由一组 `mark GET + send-code GET` 构成。UI 另有 60 秒本地再次发送冷却；它与 token 生命周期无关。

## 短信验证码登录

```http
POST /user/0/0/public/yzmLogin
Content-Type: application/json
```

代码级请求对象：

```json
{
  "app_id": null,
  "code": null,
  "phone": "<PHONE>",
  "validate_code": "<OTP>",
  "nick_name": "网页用户",
  "avatar_url": null,
  "gender": null,
  "city": null
}
```

官方代码从成功响应字段 `token` 取值。v0.2 将该 token 用手机 AndroidKeyStore 加密持久化，并在用户手动同步时把同一个 token 端到端加密发送给手表，由手表用自己的 Keystore 密钥重新加密。服务端没有已验证的 refresh 能力；任一端收到 401 后，本端必须删除凭据并要求重新 OTP。

## 当前用户（当前项目不调用）

```http
GET /user/0/0/protect/getInfo
token: <REDACTED>
```

官方客户端可用它读取当前账号信息。生产响应中会员有效字段、场馆范围和余额语义仍没有形成可审计证据，因此 v0.2 不调用该端点，也不会仅凭本地存在 token 显示“会员有效”；状态/控制端点继续由服务端执行权限判断。

## 查询淋浴状态

```http
GET /device/{brand_id}/{stadium_id}/protect/shower/info?_route_dev_id=<CANONICAL_DEVICE_ID>
token: <REDACTED>
```

官方代码消费：

```json
{
  "shower": {
    "is_opened": 1,
    "rest_time": 1200
  }
}
```

`is_opened=1` 表示已开启，`0` 表示关闭；其他值被当作协议异常。`rest_time` 作为秒数显示，客户端不通过每秒网络轮询倒计时。

v0.2 中的调用者：

- 手机选择浴室或手动刷新：对该具体路由执行一次 GET；
- 手机主界面在每次可能发送控制 POST 前：再执行一次新鲜、路由绑定的 GET；
- 小组件只有在本地显示状态已知且确认时间不超过 2 分钟时才允许进入确认页；用户确认后仍必须再执行一次新鲜 GET，旧显示状态不能直接授权 POST；
- 手表选择具体浴室后必须先刷新到已知状态，才能显示可用控制动作；
- 任何不明确控制结果最多再执行一次 GET 核对，不自动重复 POST。

## 开启或关闭

```http
POST /device/0/0/protect/shower/switch
token: <REDACTED>
Content-Type: application/json
```

```json
{
  "_route_dev_id": "<CANONICAL_DEVICE_ID>",
  "switch": 1
}
```

- `switch=1`：开启
- `switch=0`：关闭

官方代码在 POST 成功后执行一次状态 GET，并忽略 POST 成功 body。未发现 Idempotency-Key 支持；客户端只能做本进程单飞，不能使手机和手表两个进程形成原子事务。

手机主界面与小组件的单次用户确认流程：

```text
1 × 新鲜状态 GET
  → 已是目标状态：0 × POST
  → 状态不同：最多 1 × switch POST + 1 × 确认 GET
```

手表必须先有该所选浴室的已知状态；一次控制最多发送 1 个 POST，并由 repository 做 1 个确认 GET。控制 POST 绝不自动重试。超时、断连或 408/5xx 可能发生在服务端已经执行之后，因此只做一次状态观察；仍无法确认时锁定该路由为未知，要求用户现场确认并手动刷新。

手机主界面/小组件共享一个手机进程互斥锁，手表有自己的互斥锁；两者无法跨设备互斥。用户不得同时从手机和手表操作同一间浴室。

## 401、403 与临时故障

- 401：不重试原请求；本端按当前 `credentialId` 删除本地凭据，并对发送当时已连接的另一端节点做一轮 secret-free best-effort 失效通知，每节点最多尝试一次；没有 refresh。
- 403、会员拒绝、设备不可用、余额不足：不自动重试。
- 429：显示稍后手动重试，不绕过限流，也不自动重放。
- 明确的非 408 4xx 控制拒绝：不做自动重放；不把它写成控制成功。
- 408、5xx 或连接故障：状态 GET 不自动重试；控制 POST 可能不明确时最多一次状态 GET 核对，绝不重复 POST。

双向失效通知使用 Wear Data Layer，不是 HTTP API：手机到手表为 `/session/clear`，手表到手机为 `/session/invalid`。它们只发送到当前已连接节点、每节点最多一次、无 ACK/重试/离线队列，所以跨设备清除不是原子的；`credentialId` 精确匹配仅用于防止迟到旧消息误删新会话。

## 当前证据与本轮边界

仍需在用户本人明确授权的正常使用中确认或重新确认：

- token 的实际格式、寿命、风控和第三方个人客户端兼容性；
- `getInfo` 的会员字段；
- v0.2 双路由在生产响应中的完整 wrapper 与业务错误码；
- v0.2 手机上的状态、开启、确认、关闭；
- v0.2 手表上的双浴室同步与控制；
- 在线与离线情况下的双向失效收敛。

研究阶段的脱敏账本固定为 4 次公开/信任检查 HTTP 请求，详见 [RESEARCH_EVIDENCE.md](RESEARCH_EVIDENCE.md)。用户后来对上一版的正常使用不属于该研究账本，精确次数未知。v0.2 本轮明确为：0 次安装、0 次真实网络、0 次 OTP、0 次真实状态、0 次真实控制。
